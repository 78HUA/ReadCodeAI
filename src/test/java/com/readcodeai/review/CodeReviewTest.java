package com.readcodeai.review;

import com.readcodeai.agent.ScriptedLlmClient;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.FileContentService;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.review.model.ReviewReport;
import com.readcodeai.review.model.ReviewReport.MachineFinding;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 代码审查的验收：**规则那部分是算出来的，模型那部分每条都要能核对**。
 *
 * <p>要钉住的三件事：
 * <ol>
 *   <li>规则查出来的问题**每条都指向真实存在的文件与行号**（不是"看起来像"的意见）</li>
 *   <li>模型的意见如果引用了**材料里没有的位置**，这条意见要被丢掉并计数 ——
 *       审查工具最容易退化成"说得像那么回事"，丢掉的数量就是它可信度的刻度</li>
 *   <li>没配模型时规则部分照常可用（可降级，与其它路径一致）</li>
 * </ol>
 */
@SpringBootTest
class CodeReviewTest {

    @Autowired
    private CodeReviewService reviewService;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private FileContentService fileContentService;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private com.readcodeai.index.store.SymbolQueryRepository repository;

    @Test
    void machineFindingsAllPointAtRealFilesAndLines() {
        RepoView repo = corpus();
        SymbolView target = classOfMostCalledMethod(repo.id());

        // 只验规则与材料，用脚本模型（不起网络、不花 token、快）
        CodeReviewService service = new CodeReviewService(queries, fileContentService, evidenceVerifier,
                ScriptedLlmClient.lines("{\"findings\":[]}"));
        ReviewReport report = service.review(repo.id(), target.qualifiedName(), null);

        System.out.printf("%n[代码审查·规则] %s · 成员 %d · 调用者 %d · 对外调用 %d（未解析 %d）· 规则命中 %d 条%n",
                target.qualifiedName(), report.material().memberCount(), report.material().callerCount(),
                report.material().calleeCount(), report.material().unresolvedCalls(),
                report.machineFindings().size());
        report.machineFindings().forEach(finding -> System.out.printf("    [%s] %s%n      %s%n",
                finding.rule(), finding.message(), finding.location()));

        assertThat(report.material().memberCount()).isGreaterThan(0);
        assertThat(report.material().sourceLines()).as("类源码必须真读出来给模型看").isGreaterThan(0);

        // 规则意见的每条位置都要能在磁盘上核对通过（① 层有效）
        if (!report.machineFindings().isEmpty()) {
            assertThat(evidenceVerifier.verify(java.nio.file.Path.of(repo.rootPath()),
                    report.machineFindings().stream()
                            .map(finding -> new com.readcodeai.agent.model.AskEvidence(
                                    finding.file(), finding.startLine(), finding.endLine(), "", finding.rule()))
                            .toList())
                    .failed()).as("规则给的位置必须真实存在").isZero();
        }
    }

    @Test
    void findingsThatCiteLocationsOutsideTheMaterialAreDroppedAndCounted() {
        RepoView repo = corpus();
        SymbolView target = classOfMostCalledMethod(repo.id());

        // 脚本模型故意引一个**真实存在、但材料里没给**的位置（同一文件的第 1 行，在类声明之前），
        // 再给一条落在材料里的 —— 前者必须被丢掉并计数，后者必须保留
        String outside = finding("high", "引用材料外的位置", target.filePath(), 1, 1);
        String inside = finding("medium", "引用材料里的位置", target.filePath(),
                target.startLine(), target.startLine());
        ScriptedLlmClient client = ScriptedLlmClient.lines("{\"findings\":[" + outside + "," + inside + "]}");
        CodeReviewService service = new CodeReviewService(queries, fileContentService,
                evidenceVerifier, client);

        ReviewReport report = service.review(repo.id(), target.qualifiedName(), null);

        System.out.printf("%n[代码审查·模型意见] 采纳 %d 条 · 丢弃 %d 条 · 理由：%s%n",
                report.findings().size(), report.dropped(), report.dropReasons());

        assertThat(report.findings()).as("只有落在材料里的那条该被采纳").hasSize(1);
        assertThat(report.findings().get(0).title()).isEqualTo("引用材料里的位置");
        assertThat(report.dropped()).as("材料外的那条必须被丢掉并计数").isEqualTo(1);
        assertThat(report.dropReasons()).isNotEmpty();
        assertThat(report.machineFindings()).as("规则部分不受影响").isNotNull();
    }

    @Test
    void refusesAmbiguousOrNonTypeTargetsInsteadOfGuessing() {
        RepoView repo = corpus();
        SymbolView method = queries.locate(repo.id(), "read", 20).stream()
                .filter(symbol -> "METHOD".equals(symbol.kind())).findFirst().orElseThrow();

        assertThatThrownBy(() -> reviewService.review(repo.id(), method.qualifiedName() + "-not-exist", null))
                .isInstanceOf(com.readcodeai.retrieve.NotFoundException.class)
                .hasMessageContaining("索引里没有这个类");

        assertThatThrownBy(() -> reviewService.review(repo.id(), String.valueOf(method.id()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("要审查的是一个类");
    }

    @Test
    void reviewRulesAreExplainableOnCraftedSources() {
        // 纯函数部分：空 catch 的识别（源码里最常见、也最值得指出的一类问题）
        String source = """
                try {
                    load();
                } catch (IOException e) {
                }
                try {
                    save();
                } catch (Exception e) {
                    log.warn("保存失败", e);
                }
                """;
        SymbolView target = new SymbolView(1L, "CLASS", "Demo", "demo.Demo", "class Demo", "a/Demo.java",
                10, 30, null, null, null);

        List<MachineFinding> findings = ReviewToolkit.emptyCatchBlocks(source, 100, target);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).rule()).isEqualTo("EMPTY_CATCH");
        assertThat(findings.get(0).startLine()).as("行号要换算回真实文件行号（首行 = 传入的行号）")
                .isEqualTo(102);

        // 参数计数：泛型里的逗号不能被当成参数分隔符
        assertThat(ReviewToolkit.parameterCount("public <K, V> void put(K key, V value)")).isEqualTo(2);
        assertThat(ReviewToolkit.parameterCount("public void run()")).isZero();
        assertThat(ReviewToolkit.parameterCount(
                "public void go(Map<String, List<Integer>> table, int n, String name, boolean flag)")).isEqualTo(4);
    }

    /** 拼一条模型意见（引用位置由参数给定）—— 把"故意引错位置"这件事写得一眼可见。 */
    private static String finding(String severity, String title, String file, int start, int end) {
        return "{\"severity\":\"" + severity + "\",\"title\":\"" + title + "\",\"detail\":\"脚本给的\","
                + "\"evidence\":[{\"file\":\"" + file + "\",\"startLine\":" + start
                + ",\"endLine\":" + end + ",\"snippet\":\"\",\"why\":\"脚本\"}]}";
    }

    /** 审查目标：被调用最多的那个方法所属的类 —— 任何语料都成立，且这种类通常有内容可看。 */
    private SymbolView classOfMostCalledMethod(long repoId) {
        SymbolView method = repository.mostCalledMethods(repoId, 1).get(0);
        return method.parentId() == null ? method : queries.requireSymbol(method.parentId());
    }

    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, queries);
        assumeThat(resolved).as("语料不存在时跳过：" + TestCorpus.SAMPLE).isPresent();
        return resolved.get();
    }
}
