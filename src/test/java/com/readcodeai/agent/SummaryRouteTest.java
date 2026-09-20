package com.readcodeai.agent;

import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 总结类问题**自动路由到结构化摘要**的验收。
 *
 * <p>量出来的痛点（改之前，真实服务实测）：同一个「这个项目是干什么的？」
 * 走多跳 **93 秒、6 轮、16.5k token，最后模型 JSON 写坏、按拒答返回（没有结论）**；
 * 走摘要 15 秒就有一份带证据的答案。所以这条路的验收标准不是"能答"，而是：
 * <ul>
 *   <li>**不走多跳、不走检索**（steps 为空、轮次 0）——省下的正是那 93 秒</li>
 *   <li>答案**带真证据**（结构里的符号带文件行号，过 ① 层核验）</li>
 *   <li>**没配模型也能答**（只给结构，并如实说明语义部分没生成）——降级设计在这里同样成立</li>
 *   <li>流水里记成 `mode=SUMMARY`，将来能回答"总结路由到底用了多少次"</li>
 * </ul>
 */
@SpringBootTest
class SummaryRouteTest {

    @Autowired
    private AgentService agentService;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void summaryQuestionIsAnsweredByTheStructuredSummaryWithoutMultiHop() {
        RepoView repo = corpus();

        AgentAnswer answer = agentService.ask(repo.id(), "这个项目是干什么的？",
                AgentMode.MULTI_HOP, null, 8, false);

        assertThat(answer.mode()).as("响应里的 mode 必须是 SUMMARY，界面才显示得对").isEqualTo(AgentMode.SUMMARY);
        assertThat(answer.stopReason()).isEqualTo(StopReason.SUMMARY_ANSWERED);
        assertThat(answer.refused()).isFalse();
        assertThat(answer.steps()).as("总结路线不走多跳").isEmpty();
        assertThat(answer.rounds()).isZero();
        assertThat(answer.answer())
                .as("答案要给出结构与口径，而不是一句话结论")
                .contains("模块划分").contains("口径");
        assertThat(answer.evidence())
                .as("结构里的符号就是证据（带文件行号），且都通过了 ① 层核验")
                .isNotEmpty();
        assertThat(answer.evidence()).allSatisfy(evidence -> {
            assertThat(evidence.file()).isNotBlank();
            assertThat(evidence.startLine()).isPositive();
        });
    }

    @Test
    void summaryRouteIsLoggedWithItsOwnMode() {
        RepoView repo = corpus();

        agentService.ask(repo.id(), "这个仓库是做什么的", AgentMode.SINGLE_HOP, null, 8, false);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM `answer_log` WHERE repo_id = ? ORDER BY id DESC LIMIT 1", repo.id());
        assertThat(row.get("mode")).as("流水里要能看出这是总结路线").isEqualTo("SUMMARY");
        assertThat(row.get("source")).isEqualTo("USER");
        assertThat(number(row, "hops")).as("总结路线没有跳数（列名是 hops，不是 rounds）").isZero();
    }

    @Test
    void symbolLevelQuestionsStillTakeTheirNormalRoute() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());

        AgentAnswer answer = agentService.ask(repo.id(), "谁调用了 " + target.qualifiedName() + " 方法？",
                AgentMode.MULTI_HOP, null, 8, false);

        assertThat(answer.mode()).as("符号级问题不能被总结路由吃掉").isNotEqualTo(AgentMode.SUMMARY);
        assertThat(answer.answeredBy()).isEqualTo(AnsweredBy.STATIC);
    }

    @Test
    void moduleQuestionsAlsoGoToTheSummary() {
        RepoView repo = corpus();

        AgentAnswer answer = agentService.ask(repo.id(), "这个项目有哪些模块？",
                AgentMode.MULTI_HOP, null, 8, false);

        assertThat(answer.mode()).isEqualTo(AgentMode.SUMMARY);
        assertThat(answer.answer()).contains("模块划分");
    }

    // ---- 脚手架 ----

    private RepoView corpus() {
        Optional<RepoView> repo = TestCorpus.resolve(indexer, queries);
        assumeTrue(repo.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        assumeTrue(!"UNAVAILABLE".equals(repo.get().status()), "语料索引不可用，跳过");
        return repo.get();
    }

    private SymbolView firstWithCallers(long repoId) {
        List<SymbolView> candidates = repository.mostCalledMethods(repoId, 20);
        return candidates.stream()
                .filter(symbol -> !queries.callers(symbol.id()).isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("语料里找不到有调用点的符号"));
    }

    private static int number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            return -1;
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        return ((Number) value).intValue();
    }
}
