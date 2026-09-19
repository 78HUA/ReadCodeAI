package com.readcodeai.summary;

import com.readcodeai.agent.ScriptedLlmClient;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.summary.model.RepoSummary;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 摘要语义部分的缓存：**同一份索引上不该重复花那 13–15 秒**。
 *
 * <p>用脚本模型来数调用次数（不起网络、不花 token、完全确定）——
 * "第二次没有调用模型"这种事，只有能数清楚才算证明过。
 *
 * <p>三条要钉住的行为：
 * <ol>
 *   <li>第一次生成并落库；第二次**命中缓存、模型调用次数不增加**</li>
 *   <li>{@code refresh=true} 强制重新生成（缓存在界面上是可控的，不是暗箱）</li>
 *   <li>缓存键含索引版本：换个索引时间戳就查不到旧缓存（否则会把旧代码的说辞配给新代码）</li>
 * </ol>
 */
@SpringBootTest
class SummaryCacheTest {

    private static final String NOTES_JSON = """
            {"overview":"一个用于演示的极小 Java 样例项目。","features":["提供问候功能","演示索引流程"],
             "notes":[{"module":"internal","note":"负责内部绑定与类型适配。","mentionedSymbols":[],"unverifiedSymbols":[],"numbersInNote":[],"verified":true}]}
            """;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SummaryRepository repository;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ProjectMaterialBuilder projectMaterialBuilder;

    private Long touchedRepoId;

    @AfterEach
    void cleanUpCache() {
        // 测试里生成的是脚本模型的假说明，不能留在库里被界面读到
        if (touchedRepoId != null) {
            repository.deleteSemantics(touchedRepoId);
        }
    }

    @Test
    void generatesOnceThenServesFromCacheUntilRefresh() {
        RepoView repo = corpus();
        touchedRepoId = repo.id();
        repository.deleteSemantics(repo.id());

        // 语义现在是**两次调用**（项目一句话 / 模块说明各一次，见 SemanticSummarizer 的注释），
        // 所以脚本要给两行；同一份 JSON 两份解析器都能吃（字段超集）
        ScriptedLlmClient client = ScriptedLlmClient.lines(NOTES_JSON, NOTES_JSON, NOTES_JSON, NOTES_JSON);
        RepoSummaryService service = new RepoSummaryService(queries, repository,
                new SemanticSummarizer(client, repository), projectMaterialBuilder);

        RepoSummary first = service.summarize(repo.id(), true, false);
        assertThat(client.calls()).as("第一次要真调模型（两问各一次）").isEqualTo(2);
        assertThat(first.semantics().available()).isTrue();
        assertThat(first.semantics().cached()).as("第一次是新生成的，不是缓存").isFalse();
        assertThat(first.semantics().notes()).hasSize(1);
        assertThat(first.semantics().overview()).as("项目级的一句话也要能解析出来")
                .isNotNull();
        assertThat(first.semantics().features()).hasSize(2);

        RepoSummary second = service.summarize(repo.id(), true, false);
        assertThat(client.calls()).as("第二次不该再调模型（否则每次看概览都要等十几秒）").isEqualTo(2);
        assertThat(second.semantics().cached()).isTrue();
        assertThat(second.semantics().notes()).isEqualTo(first.semantics().notes());
        assertThat(second.semantics().overview()).as("缓存要连项目一句话一起存/取")
                .isEqualTo(first.semantics().overview());

        RepoSummary refreshed = service.summarize(repo.id(), true, true);
        assertThat(client.calls()).as("refresh=true 要强制重新生成（再一次两问）").isEqualTo(4);
        assertThat(refreshed.semantics().cached()).isFalse();

        // 结构部分不吃缓存：每次都是现算的（它 83 ms，而且必须新鲜）
        assertThat(refreshed.structure().scale().fileCount()).isEqualTo(repo.fileCount());
        assertThat(refreshed.structure().modules()).isNotEmpty();
    }

    @Test
    void cacheKeyIncludesTheIndexVersionSoStaleTextCanNeverBeServed() {
        RepoView repo = corpus();
        touchedRepoId = repo.id();
        repository.deleteSemantics(repo.id());

        repository.saveSemantics(repo.id(), repo.indexedAt(), "test-model", NOTES_JSON, 10, 5);

        assertThat(repository.findSemantics(repo.id(), repo.indexedAt(), "test-model")).isPresent();
        assertThat(repository.findSemantics(repo.id(), repo.indexedAt().plusSeconds(5), "test-model"))
                .as("换个索引版本就该查不到 —— 否则会把旧代码的说明配给新代码")
                .isEmpty();
        assertThat(repository.findSemantics(repo.id(), repo.indexedAt(), "another-model"))
                .as("换模型也是另一份生成结果").isEmpty();
    }

    @Test
    void withoutSemanticsNothingIsGeneratedOrCached() {
        RepoView repo = corpus();
        touchedRepoId = repo.id();
        repository.deleteSemantics(repo.id());

        ScriptedLlmClient client = ScriptedLlmClient.lines(NOTES_JSON);
        RepoSummaryService service = new RepoSummaryService(queries, repository,
                new SemanticSummarizer(client, repository), projectMaterialBuilder);

        RepoSummary summary = service.summarize(repo.id(), false, false);

        assertThat(client.calls()).as("关掉语义就不该调模型").isZero();
        assertThat(summary.semantics().available()).isFalse();
        assertThat(repository.findSemantics(repo.id(), repo.indexedAt(), client.model())).isEmpty();
    }

    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, queries);
        assumeThat(resolved).as("语料不存在时跳过：" + TestCorpus.SAMPLE).isPresent();
        return resolved.get();
    }
}
