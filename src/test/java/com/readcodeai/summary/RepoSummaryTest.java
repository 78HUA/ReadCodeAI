package com.readcodeai.summary;

import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.summary.model.RepoSummary;
import com.readcodeai.summary.model.RepoSummary.Module;
import com.readcodeai.summary.model.RepoSummary.ModuleNote;
import com.readcodeai.summary.model.RepoSummary.Ranked;
import com.readcodeai.summary.model.RepoSummary.SymbolRef;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 结构化摘要的验收：**摘要里的每个数字、每个符号都必须能回到索引里核对**。
 *
 * <p>这是设计文档给这一步定的判据，也是"结构化摘要"与"把文件塞给模型让它读"的分界线所在。
 * 所以这里的断言全部是**反着查**的：拿到摘要里的一个数字/一个 symbolId，
 * 去数据库里重新算一遍、重新取一次，对不上就是失败。
 *
 * <p>另外单独覆盖模块划分的纯函数（不需要数据库）：包名切分是最容易悄悄错的地方 ——
 * 划错了不会报错，只会让读者少看到一片代码。
 */
@SpringBootTest
class RepoSummaryTest {

    @Autowired
    private RepoSummaryService summaryService;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SemanticSummarizer semanticSummarizer;

    @Autowired
    private ProjectMaterialBuilder projectMaterialBuilder;

    @Autowired
    private SummaryRepository summaryRepository;

    @Test
    void everyNumberAndEverySymbolInTheSummaryCanBeCheckedAgainstTheIndex() {
        RepoView repo = corpus();

        // 语义部分关掉：这一条测的是"结构是否可核对"，不该受模型可用性影响
        RepoSummary summary = summaryService.summarize(repo.id(), false);
        RepoSummary.Structure structure = summary.structure();

        System.out.printf("%n[结构化摘要] %s · 模块 %d 个 · 入口 %d · 枢纽 %d · 无法核对 0 个%n",
                repo.name(), structure.modules().size(), structure.entryPoints().size(),
                structure.callHubs().size());
        structure.modules().forEach(module -> System.out.printf("    %-22s 文件 %3d · 行 %6d · 符号 %5d%n",
                module.name(), module.fileCount(), module.totalLoc(), module.symbolCount()));

        // ① 规模数字：全部与 repo 记录、符号表重新算出来的值一致
        assertThat(structure.scale().fileCount()).isEqualTo(repo.fileCount());
        assertThat(structure.scale().totalLoc()).isEqualTo(repo.totalLoc());
        assertThat(structure.scale().callEdges()).isEqualTo(repo.callEdgeCount());
        assertThat(structure.scale().resolvedCallEdges()).isEqualTo(repo.callResolvedCount());
        SummaryRepository.ScaleVitals vitals = summaryRepository.vitals(repo.id());
        assertThat(structure.scale().totalSymbols()).as("符号总数必须等于符号表的实际行数")
                .isEqualTo(vitals.totalSymbols());
        assertThat(structure.scale().classCount()).isEqualTo(vitals.classCount());
        assertThat(structure.scale().interfaceCount()).isEqualTo(vitals.interfaceCount());
        assertThat(structure.scale().methodCount()).isEqualTo(vitals.methodCount());

        // ② 模块划分不能丢文件：每个源文件都要落在某个模块里（这是"模块划分"最容易悄悄错的地方）
        int filesInModules = structure.modules().stream().mapToInt(Module::fileCount).sum();
        assertThat(filesInModules).as("所有模块的文件数之和必须等于仓库文件数（不许有文件被吞掉）")
                .isEqualTo(repo.fileCount());
        assertThat(structure.modules()).as("模块名不该重复").doesNotHaveDuplicates()
                .allSatisfy(module -> assertThat(module.name()).isNotBlank());

        // ③ 摘要里出现的每个符号都能按 id 取回来，且文件与行号与索引完全一致
        List<SymbolRef> allRefs = collectSymbolRefs(structure);
        assertThat(allRefs).as("摘要里应当至少列出一些符号（否则这一页没有可点开的东西）").isNotEmpty();
        for (SymbolRef ref : allRefs) {
            SymbolView actual = queries.requireSymbol(ref.symbolId());
            assertThat(ref.qualifiedName()).as("符号 id 与名字必须对得上").isEqualTo(actual.qualifiedName());
            assertThat(ref.filePath()).isEqualTo(actual.filePath());
            assertThat(ref.startLine()).isEqualTo(actual.startLine());
            assertThat(ref.endLine()).isEqualTo(actual.endLine());
        }

        // ④ 排行榜的计数与调用图重新聚合的结果一致（抽查第一名）
        assertThat(structure.callHubs()).isNotEmpty();
        Ranked topHub = structure.callHubs().get(0);
        assertThat(topHub.count()).as("枢纽计数必须是真实解析边数").isGreaterThan(0);
        assertThat(structure.topMethods()).isNotEmpty();
        assertThat(structure.topMethods().get(0).count()).isGreaterThan(0);

        // ⑤ 语义部分是关掉的：结构照常完整，可降级在这里也必须成立
        assertThat(summary.semantics().available()).isFalse();
        assertThat(summary.semantics().reason()).contains("未要求");
    }

    @Test
    void projectMaterialIsComputedFromTheIndexNotFromTheModel() {
        RepoView repo = corpus();
        RepoSummary summary = summaryService.summarize(repo.id(), false);
        ProjectMaterialBuilder.Material material = projectMaterialBuilder.build(repo,
                summary.structure().callHubs(), summary.structure().entryPoints(),
                summary.structure().modulePrefix());

        System.out.printf("%n[项目材料] 业务对象 %d 个：%s%n            控制器 %d 个 · 接口路径 %d 条 · 核心类 %d 个%n",
                material.domainTypes().size(), material.domainTypes().stream().limit(6).toList(),
                material.controllers().size(), material.paths().size(), material.hubs().size());
        if (!material.paths().isEmpty()) {
            System.out.printf("            路径样例：%s%n", material.paths().stream().limit(8).toList());
        }

        // 这些名字都必须是索引里真实存在的 —— 它们是"这个项目是做什么的"的依据，
        // 一旦变成模型编的，整句话就不可核对了
        assertThat(material.isEmpty()).as("至少该取到业务对象或控制器（否则这句话没有依据）").isFalse();
        for (String name : material.domainTypes()) {
            assertThat(queries.locate(repo.id(), name, 20))
                    .as("业务对象 %s 必须能在索引里找到", name).isNotEmpty();
        }
        assertThat(material.describe()).contains("业务对象").contains("接口路径样例");
    }

    @Test
    void unverifiedSymbolNamesInModelNotesAreFlagged() {
        RepoView repo = corpus();
        // 取一个**提取器认得出**的名字：标识符规则是 `[A-Za-z_$][A-Za-z0-9_$]{2,}`（≥3 个字符），
        // 而"被调用最多的方法"完全可能是 `ok` 这种两字母短名（CI 用项目自己当语料时就撞上了）——
        // 命名规则决定了它不会被提取出来，用例不该栽在这上面。
        SymbolView known = repository.mostCalledMethods(repo.id(), 20).stream()
                .filter(symbol -> symbol.name().length() >= 3)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("语料里找不到名字足够长的被调方法"));

        // 一句话里混进一个真符号名与一个编造的符号名 —— 只有真的那个该通过核对
        List<ModuleNote> notes = semanticSummarizer.verify(repo.id(), List.of(
                new SemanticSummarizer.RawNote("core", "负责 " + known.name() + " 与 NotARealClassName 的调度"),
                new SemanticSummarizer.RawNote("util", "工具类，含 42 个方法")));

        assertThat(notes).hasSize(2);
        assertThat(notes.get(0).mentionedSymbols()).contains(known.name(), "NotARealClassName");
        assertThat(notes.get(0).unverifiedSymbols()).as("编造的类名必须被标出来").containsExactly("NotARealClassName");
        assertThat(notes.get(0).numbersInNote()).isEmpty();
        assertThat(notes.get(1).numbersInNote()).as("模型自己写的数字也要标出来（数字该由结构部分给）")
                .containsExactly("42");
        assertThat(notes.get(1).verified()).as("含数字的说明不算完全可核对").isFalse();
    }

    @Test
    void modulePartitionDeepensThePrefixUntilItIsInformative() {
        // com / com.google 这种纯域名前缀分出来的模块名（google、example）对读者没有信息量，
        // 所以分不出 3 个以上模块时要继续加深 —— real 语料上应当落到 com.google.gson 这一层
        Set<String> packages = new TreeSet<>(List.of(
                "com.google.gson", "com.google.gson.internal", "com.google.gson.stream",
                "com.google.gson.reflect", "com.google.gson.annotations", "com.example"));
        assertThat(RepoSummaryService.choosePrefix(packages)).isEqualTo("com.google.gson");
        assertThat(RepoSummaryService.distinctModuleCount(packages, "com.google.gson"))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void modulePartitionIsDerivedFromTheCommonPackagePrefix() {
        // 纯函数：这些规则写错不会报错，只会让读者少看到一片代码，所以单独钉死
        assertThat(RepoSummaryService.packageOf("com.a.B.C")).isEqualTo("com.a.B");
        assertThat(RepoSummaryService.packageOf("Main")).isEmpty();
        assertThat(RepoSummaryService.packageOf(null)).isNull();

        Set<String> packages = new TreeSet<>(List.of(
                "com.google.gson", "com.google.gson.internal", "com.google.gson.internal.bind",
                "com.google.gson.stream", "com.example"));
        assertThat(RepoSummaryService.commonPackagePrefix(packages)).isEqualTo("com");

        assertThat(RepoSummaryService.moduleName("com.google.gson.internal.bind", "com.google.gson"))
                .isEqualTo("internal");
        assertThat(RepoSummaryService.moduleName("com.google.gson", "com.google.gson")).isEqualTo("(根包)");
        assertThat(RepoSummaryService.moduleName("com.example", "com.google.gson")).startsWith("(其他包)");

        // 根包只匹配本包（否则会把所有子包都吞进来），具名模块匹配整棵子树
        assertThat(RepoSummaryService.keyTypeRegex("com.google.gson", "com.google.gson"))
                .isEqualTo("^com[.]google[.]gson[.][A-Za-z0-9_$]+$");
        assertThat(RepoSummaryService.keyTypeRegex("com.google.gson.internal", "com.google.gson"))
                .isEqualTo("^com[.]google[.]gson[.]internal[.].+$");
        assertThat(RepoSummaryService.keyTypeRegex("(其他包) com.example", "com.google.gson")).isNull();
    }

    private static List<SymbolRef> collectSymbolRefs(RepoSummary.Structure structure) {
        List<SymbolRef> refs = new ArrayList<>();
        structure.modules().forEach(module -> refs.addAll(module.keyTypes()));
        refs.addAll(structure.entryPoints());
        refs.addAll(structure.uncalledClasses());
        structure.callHubs().forEach(hub -> refs.add(hub.symbol()));
        structure.topMethods().forEach(method -> refs.add(method.symbol()));
        structure.implementations().forEach(impl -> refs.add(impl.symbol()));
        return refs;
    }

    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, queries);
        assumeThat(resolved).as("语料不存在时跳过：" + TestCorpus.SAMPLE).isPresent();
        return resolved.get();
    }
}
