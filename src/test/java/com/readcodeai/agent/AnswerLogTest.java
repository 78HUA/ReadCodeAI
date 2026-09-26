package com.readcodeai.agent;

import com.readcodeai.agent.cache.AnswerCache;
import com.readcodeai.agent.log.AnswerLogService;
import com.readcodeai.agent.log.AnswerLogSource;
import com.readcodeai.agent.log.AnswerLogStats;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.VerificationSummary;
import com.readcodeai.config.LlmClient;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.evidence.EvidenceRepair;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.ContextSelector;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestAnswerLogs;
import com.readcodeai.verify.TestCheckers;
import com.readcodeai.verify.TestCorpus;
import com.readcodeai.verify.TestRepoCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 问答流水的验收：**记不记、记成什么样、统计算得对不对、坏了会不会连累回答**。
 *
 * <p>四件事的优先级是不一样的，测试也照这个顺序写：
 * <ol>
 *   <li><b>记账不能影响回答</b>（写库失败、字段超长都得吞掉）——
 *       这是"账本"与"正确性依赖"的分界，越界了就是一个故障放大器。</li>
 *   <li><b>每条路都要记</b>：静态 / 单跳 / 多跳 / 取缓存 —— 漏一条，"累计"就是假的。</li>
 *   <li><b>口径要对</b>：静态路线零 token；缓存命中那一行记的是"当初花的、这次省的"。</li>
 *   <li><b>删仓库带走流水</b>：账本跟着仓库走（测试造的临时仓库也因此不会撑起统计数字）。</li>
 * </ol>
 *
 * <p>这里刻意用**真实记账**（不像其它测试用 {@link TestAnswerLogs#silent}）：测的就是记账本身。
 * 造的仓库行由 {@link #cleanUp()} 按路径前缀删掉，外键级联会把流水一起带走。
 */
@SpringBootTest
class AnswerLogTest {

    private static final String TEMP_PATH_PREFIX = "C:/tmp/junit-answer-log-";

    /** 一被调用就抛错 —— 用来证明"静态路线确实没叫模型"（而不是"我们以为没叫"）。 */
    private static final ScriptedLlmClient.Script MUST_NOT_BE_CALLED = (turn, prompt) -> {
        throw new AssertionError("这条路线不该调用模型，却在第 " + turn + " 轮被调用了");
    };

    @Autowired
    private AnswerLogService answerLogService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private QueryRouter queryRouter;

    @Autowired
    private ContextSelector contextSelector;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private EvidenceRepair evidenceRepair;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private ReadCodeAiProperties properties;

    @Autowired
    private SummaryAnswerer summaryAnswerer;

    @AfterEach
    void cleanUp() {
        TestRepoCleanup.deleteReposUnder(jdbc, TEMP_PATH_PREFIX + "%");
    }

    @Test
    void staticAnswerIsLoggedAsACostlessRoute() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        String question = "谁调用了 " + target.qualifiedName() + "？";

        answerServiceWith(new ScriptedLlmClient(MUST_NOT_BE_CALLED)).ask(repo.id(), question, null, 8);

        Map<String, Object> row = latestLog(repo.id());
        assertThat(row.get("question")).isEqualTo(question);
        assertThat(row.get("mode")).isEqualTo("STATIC");
        assertThat(row.get("answered_by")).isEqualTo("STATIC");
        assertThat(number(row, "refused")).isZero();
        assertThat(number(row, "prompt_tokens")).as("静态路线不花 token").isZero();
        assertThat(number(row, "completion_tokens")).isZero();
        assertThat(number(row, "evidence_verified")).as("采纳了几条证据也要记").isPositive();
        assertThat(String.valueOf(row.get("route_json"))).as("路由结果要能事后核对").contains("CALLERS");
        assertThat(String.valueOf(row.get("answer_json"))).contains("evidence");
    }

    @Test
    void refusalIsLoggedTooTogetherWithItsReason() {
        RepoView repo = corpus();
        // 脚本模型给的是"拒答"台词：即使检索召回了片段，这次问答也是拒答；
        // 若检索为空则连模型都不用叫 —— 两种情况都该记成一行拒答
        AnswerService service = answerServiceWith(
                ScriptedLlmClient.lines(ScriptedLlmClient.singleHopRefuse("材料不足以回答")));

        service.ask(repo.id(), "Where is zzzzqqqqzzzz implemented?", null, 8);

        Map<String, Object> row = latestLog(repo.id());
        assertThat(number(row, "refused")).isEqualTo(1);
        assertThat(row.get("mode")).isEqualTo("SINGLE_HOP");
        assertThat(row.get("refusal_reason")).as("拒答必须记下原因，否则没法诊断").isNotNull();
    }

    @Test
    void cacheHitIsLoggedWithoutChargingTheTokensTwice() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        String question = "这个方法大致是做什么的：" + target.qualifiedName() + "？";
        ScriptedLlmClient client = ScriptedLlmClient.lines(ScriptedLlmClient.singleHopAnswer(
                        "它做了一件事", target.filePath(), target.startLine(), target.endLine(), null))
                .withTokenUsage(300, 50);
        AgentService service = agentServiceWith(client, new InMemoryAnswerCache());

        AgentAnswer first = service.ask(repo.id(), question, AgentMode.SINGLE_HOP, null, 8);
        assumeTrue(!first.refused(), "第一次就没答出来（语料问题），缓存无从谈起");

        AgentAnswer second = service.ask(repo.id(), question, AgentMode.SINGLE_HOP, null, 8);
        assertThat(second.cached()).as("第二次应当命中缓存").isTrue();

        Map<String, Object> hit = latestLog(repo.id());
        assertThat(number(hit, "cache_hit")).isEqualTo(1);
        assertThat(number(hit, "prompt_tokens")).as("记的是这份答案当初花的，不是这次再花的").isEqualTo(300);
        assertThat(number(hit, "latency_ms")).as("取缓存没有生成耗时").isZero();

        Map<String, Object> generated = logBeforeLatest(repo.id());
        assertThat(number(generated, "cache_hit")).as("前一行是真实生成").isZero();
        assertThat(number(generated, "prompt_tokens")).isEqualTo(300);
    }

    @Test
    void statsAggregateTheLoggedRows() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        AnswerLogStats before = answerLogService.stats(repo.id());

        AnswerService service = answerServiceWith(new ScriptedLlmClient(MUST_NOT_BE_CALLED));
        service.ask(repo.id(), "谁调用了 " + target.qualifiedName() + "？", null, 8);
        service.ask(repo.id(), "谁调用了 " + target.qualifiedName() + "？", null, 8);

        AnswerLogStats after = answerLogService.stats(repo.id());
        assertThat(after.userQuestions()).isEqualTo(before.userQuestions() + 2);
        assertThat(after.totalTokens()).as("静态路线两条也是零 token").isEqualTo(before.totalTokens());
        assertThat(after.byMode()).anySatisfy(row -> {
            assertThat(row.mode()).isEqualTo("STATIC");
            assertThat(row.count()).isGreaterThanOrEqualTo(2);
        });
        assertThat(answerLogService.stats(null).userQuestions())
                .as("不带 repoId 时统计全部仓库，至少不少于单个仓库")
                .isGreaterThanOrEqualTo(after.userQuestions());
    }

    @Test
    void evalRunsAreLoggedButKeptOutOfTheUserAccount() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        AnswerLogStats before = answerLogService.stats(repo.id());

        answerServiceWith(new ScriptedLlmClient(MUST_NOT_BE_CALLED))
                .askForEval(repo.id(), "谁调用了 " + target.qualifiedName() + "？", 8);

        Map<String, Object> row = latestLog(repo.id());
        assertThat(row.get("source")).as("评估跑题要标出来，否则它会撑起累计问答").isEqualTo("EVAL");

        AnswerLogStats after = answerLogService.stats(repo.id());
        assertThat(after.evalQuestions()).isEqualTo(before.evalQuestions() + 1);
        assertThat(after.userQuestions()).as("评估跑题不该进用户问答的账").isEqualTo(before.userQuestions());
    }

    @Test
    void aFailedLogWriteNeverBreaksTheAnswer() {
        // repoId = -1 不存在 → 外键校验失败 → 这一行写不进去。
        // 记账是账本，不是正确性依赖：**调用方不该因此收到异常**
        AskAnswer answer = new AskAnswer("结论", List.of(), false, null, AnsweredBy.NONE,
                List.of(), 0, 0, 0, 0, 1, VerificationSummary.none());

        assertThatCode(() -> answerLogService.recordAsk(-1L, null, AnswerLogSource.USER, "测试问题",
                "STATIC", null, answer))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `answer_log` WHERE repo_id = -1", Integer.class))
                .as("外键挡住了这一行，库里不该留下它的痕迹").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `answer_log`", Integer.class))
                .as("一次失败的记账不该影响已有的流水").isPositive();
    }

    @Test
    void deletingARepoRemovesItsAnswerLog() {
        long repoId = insertTempRepo(TEMP_PATH_PREFIX + System.nanoTime());
        jdbc.update("""
                INSERT INTO `answer_log` (repo_id, question, mode, answered_by, refused, created_at)
                VALUES (?, '测试问题', 'STATIC', 'STATIC', 0, NOW())
                """, repoId);
        assertThat(countLogs(repoId)).isEqualTo(1);

        jdbc.update("DELETE FROM `repo` WHERE id = ?", repoId);

        assertThat(countLogs(repoId)).as("删仓库要带走它的问答流水（账本跟着仓库走）").isZero();
    }

    // ---- 脚手架 ----

    private AnswerService answerServiceWith(LlmClient client) {
        return new AnswerService(textRetriever, queries, queryRouter, contextSelector,
                evidenceVerifier, evidenceRepair, TestCheckers.NONE, client, properties, answerLogService);
    }

    private AgentService agentServiceWith(LlmClient client, AnswerCache cache) {
        AgentLoop loop = new AgentLoop(toolRegistry, evidenceVerifier, TestCheckers.NONE, client, 2);
        return new AgentService(answerServiceWith(client), loop, queryRouter, queries, client, cache,
                properties, answerLogService, summaryAnswerer);
    }

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

    private Map<String, Object> latestLog(long repoId) {
        return jdbc.queryForMap(
                "SELECT * FROM `answer_log` WHERE repo_id = ? ORDER BY id DESC LIMIT 1", repoId);
    }

    private Map<String, Object> logBeforeLatest(long repoId) {
        return jdbc.queryForMap(
                "SELECT * FROM `answer_log` WHERE repo_id = ? ORDER BY id DESC LIMIT 1 OFFSET 1", repoId);
    }

    private int countLogs(long repoId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `answer_log` WHERE repo_id = ?", Integer.class, repoId);
        return count == null ? 0 : count;
    }

    private long insertTempRepo(String rootPath) {
        jdbc.update("""
                INSERT INTO `repo` (name, root_path, status, created_at)
                VALUES ('junit-answer-log', ?, 'READY', NOW())
                """, rootPath);
        Long id = jdbc.queryForObject("SELECT id FROM `repo` WHERE root_path = ?", Long.class, rootPath);
        return id == null ? -1 : id;
    }

    /** 注意 {@code TINYINT(1)} 会被 JDBC 读成 Boolean（MySQL 的老约定），两种都要认。 */
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

    /** 内存版答案缓存：**不依赖 Redis** —— 缓存命中这条记账路径必须离线也能测。 */
    private static final class InMemoryAnswerCache implements AnswerCache {

        private final Map<String, AgentAnswer> store = new HashMap<>();

        @Override
        public Optional<AgentAnswer> get(long repoId, String indexedAt, String question, String mode, String model,
                                         String engine) {
            return Optional.ofNullable(store.get(key(repoId, question, mode, model, engine)));
        }

        @Override
        public void put(long repoId, String indexedAt, String question, String mode, String model, String engine,
                        AgentAnswer answer) {
            store.put(key(repoId, question, mode, model, engine), answer);
        }

        @Override
        public String describe() {
            return "内存缓存（测试用）";
        }

        private static String key(long repoId, String question, String mode, String model, String engine) {
            return repoId + "|" + question + "|" + mode + "|" + model + "|" + engine;
        }
    }
}
