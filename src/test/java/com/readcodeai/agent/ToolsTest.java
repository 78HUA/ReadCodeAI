package com.readcodeai.agent;

import com.readcodeai.agent.tools.ToolContext;
import com.readcodeai.agent.tools.ToolResult;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 工具集的验收：**每个工具给出的证据都必须能过磁盘核验**。
 *
 * <p>这是多跳循环可信的前提：工具是确定性的，模型只是决定调哪个；
 * 如果工具给的证据本身就对不上磁盘，那"每一跳都带证据"就成了一句空话。
 *
 * <p>另一条同样重要：**歧义时不许猜**。裸方法名在真实仓库里普遍不唯一，
 * 挑一个出来就是猜，而猜错的代价是整条链指向别的符号 —— 所以宁可返回候选让模型限定。
 */
@SpringBootTest
class ToolsTest {

    /** 只为一个用途：那条偶发红的断言留下现场（见 textSearchFindsCodeByKeywordAndEvidenceVerifies）。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolsTest.class);

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private ProjectIndexer indexer;

    @Test
    void findDefinitionReturnsTheDefinitionAndItsEvidenceVerifies() {
        RepoView repo = corpus();
        SymbolView target = mostCalled(repo.id());

        ToolResult result = toolRegistry.execute(context(repo), "findDefinition",
                Map.of("symbol", target.qualifiedName()));

        assertThat(result.found()).isTrue();
        assertThat(result.subjects()).contains(target.qualifiedName());
        assertThat(result.observation()).contains(target.filePath());
        assertThat(verify(repo, result).failed()).isZero();
    }

    @Test
    void findCallersReturnsCallSitesThatAllExistOnDisk() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());

        ToolResult result = toolRegistry.execute(context(repo), "findCallers",
                Map.of("symbol", target.qualifiedName()));

        assertThat(result.found()).as("这个符号是挑过的，必有调用点").isTrue();
        assertThat(result.subjects()).allSatisfy(subject ->
                assertThat(subject).as("调用者应当是限定名").contains("."));
        assertThat(result.observation()).contains("调用点");
        assertThat(verify(repo, result).failed())
                .as("调用点行号必须真实存在（① 层核验）").isZero();
    }

    @Test
    void readSymbolReturnsNumberedSourceWhoseSnippetMatchesTheDisk() {
        RepoView repo = corpus();
        SymbolView target = mostCalled(repo.id());

        ToolResult result = toolRegistry.execute(context(repo), "readSymbol",
                Map.of("symbol", target.qualifiedName()));

        assertThat(result.found()).isTrue();
        assertThat(result.observation()).contains("=== ").containsPattern("\\d+: ");
        EvidenceVerifier.Report report = verify(repo, result);
        assertThat(report.failed()).isZero();
        assertThat(report.evidence())
                .as("readSymbol 的片段是从磁盘读出来的，**必须**通过 ② 层内容核验")
                .allSatisfy(verified -> assertThat(verified.snippetMatches()).isEqualTo(Boolean.TRUE));
    }

    @Test
    void doesNotGuessWhenABareNameBelongsToSeveralTypes() {
        RepoView repo = corpus();
        String ambiguous = findAmbiguousName(repo.id());
        assumeTrue(ambiguous != null, "语料里没有跨类型重名的符号，跳过");

        ToolResult result = toolRegistry.execute(context(repo), "findDefinition",
                Map.of("symbol", ambiguous));

        assertThat(result.found()).as("歧义时不许替模型挑一个").isFalse();
        assertThat(result.subjects()).isEmpty();
        assertThat(result.observation()).as("要把候选与限定方式告诉模型").contains("限定");
    }

    @Test
    void unknownToolAndMissingArgumentsBecomeObservationsInsteadOfFailures() {
        RepoView repo = corpus();

        ToolResult unknown = toolRegistry.execute(context(repo), "dropDatabase", Map.of());
        assertThat(unknown.found()).isFalse();
        assertThat(unknown.observation()).contains("没有这个工具").contains("findCallers");

        ToolResult missingArg = toolRegistry.execute(context(repo), "findCallers", Map.of());
        assertThat(missingArg.found()).isFalse();
        assertThat(missingArg.observation()).as("必须告诉模型正确用法，否则它只会乱试").contains("正确用法");
    }

    @Test
    void toolNameVariantsResolve() {
        assertThat(toolRegistry.find("find_callers")).as("模型爱写下划线风格").isPresent();
        assertThat(toolRegistry.find("FIND-CALLERS")).as("连字符风格也要认").isPresent();
        assertThat(toolRegistry.find("textSearch")).isPresent();
        assertThat(toolRegistry.names()).contains("findDefinition", "readSymbol", "textSearch");
    }

    @Test
    void textSearchFindsCodeByKeywordAndEvidenceVerifies() {
        RepoView repo = corpus();
        SymbolView target = mostCalled(repo.id());

        ToolResult result = toolRegistry.execute(context(repo), "textSearch",
                Map.of("query", target.name()));

        assertThat(result.found()).as("拿一个真实存在的方法名去搜，应当命中").isTrue();
        assertThat(result.observation()).contains("命中");
        EvidenceVerifier.Report report = verify(repo, result);
        if (report.failed() > 0) {
            // 这条断言偶发红过一次（顺序相关）而当时没留下现场。把"搜的是谁、哪几条对不上、为什么"打全 ——
            // 下次一冒头就能直接定位，不用再靠复现碰运气。
            log.warn("全文检索证据核验未过：target={}（{}）· 仓库={} · 失败分布={} · 未通过明细={}",
                    target.name(), target.location(), repo.rootPath(), report.failureCounts(),
                    report.evidence().stream().filter(e -> !e.passed())
                            .map(e -> e.evidence().location() + " " + e.failureKind() + " " + e.detail())
                            .toList());
        }
        assertThat(report.failed())
                .as("全文检索的证据也要过磁盘核验（索引过期就该判失败）；target=%s；失败分布=%s",
                        target.location(), report.failureCounts())
                .isZero();
    }

    private EvidenceVerifier.Report verify(RepoView repo, ToolResult result) {
        return evidenceVerifier.verify(Path.of(repo.rootPath()), result.evidence());
    }

    private ToolContext context(RepoView repo) {
        return new ToolContext(repo.id(), Path.of(repo.rootPath()));
    }

    private SymbolView mostCalled(long repoId) {
        return repository.mostCalledMethods(repoId, 1).get(0);
    }

    private SymbolView firstWithCallers(long repoId) {
        List<SymbolView> candidates = repository.mostCalledMethods(repoId, 20);
        return candidates.stream()
                .filter(symbol -> !queries.callers(symbol.id()).isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("语料里找不到有调用点的符号"));
    }

    /** 找一个「同一个名字落在多个不同类型上」的符号 —— 这是最容易让工具猜错的输入。 */
    private String findAmbiguousName(long repoId) {
        for (SymbolView symbol : repository.mostCalledMethods(repoId, 40)) {
            long owners = queries.locate(repoId, symbol.name(), 100).stream()
                    .filter(item -> item.name().equals(symbol.name()))
                    .map(item -> item.parentId() == null ? item.id() : item.parentId())
                    .distinct()
                    .count();
            if (owners > 1) {
                return symbol.name();
            }
        }
        return null;
    }

    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, queries);
        assumeTrue(resolved.isPresent(), "语料 " + TestCorpus.SAMPLE + " 不存在，跳过");
        return resolved.get();
    }
}
