package com.readcodeai.agent;

import com.readcodeai.agent.cache.AnswerCache;
import com.readcodeai.agent.cache.RedisAnswerCache;
import com.readcodeai.agent.model.AgentAnswer;
import com.readcodeai.agent.model.AgentMode;
import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.StopReason;
import com.readcodeai.config.ReadCodeAiProperties;
import com.readcodeai.evidence.EvidenceVerifier;
import com.readcodeai.eval.ChainQuestionGenerator;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.ContextSelector;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 答案缓存的契约（离线、用假缓存，不起 Redis）：
 *
 * <ol>
 *   <li>同一个问题第二次问 → **命中缓存、不再调模型**，并且结果被标成"来自缓存"</li>
 *   <li>缓存键含**索引版本** → 重新索引后旧答案自动失效（绝不能给过期的行号）</li>
 *   <li>确定性问题**不进缓存**（本来就几毫秒，缓存它只有风险没有收益）</li>
 *   <li>拒答**不进缓存**（拒答常常是临时故障，固化下来就成了"永久失败"）</li>
 *   <li>缓存自己炸了 → **问答照常**（缓存是加速，不是正确性依赖）</li>
 * </ol>
 */
@SpringBootTest
class AgentAnswerCacheTest {

    @Autowired
    private AnswerService answerService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private EvidenceVerifier evidenceVerifier;

    @Autowired
    private QueryRouter queryRouter;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ChainQuestionGenerator chainGenerator;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private TextRetriever textRetriever;

    @Autowired
    private ContextSelector contextSelector;

    @Autowired
    private com.readcodeai.evidence.EvidenceRepair evidenceRepair;

    @Autowired
    private ReadCodeAiProperties properties;

    @Test
    void secondIdenticalQuestionComesFromCacheWithoutCallingTheModelAgain() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        String question = "这个方法大致是做什么的：" + target.qualifiedName() + "？";

        ScriptedLlmClient client = ScriptedLlmClient.lines(
                ScriptedLlmClient.singleHopAnswer("模型第一次给出的答案", target.filePath(),
                        target.startLine(), target.endLine(), null));
        RecordingCache cache = new RecordingCache();
        AgentService service = serviceWith(client, cache);

        AgentAnswer first = service.ask(repo.id(), question, AgentMode.SINGLE_HOP, null, 8);
        assertThat(first.refused()).isFalse();
        assertThat(first.cached()).as("第一次是新生成的").isFalse();
        assertThat(client.calls()).isEqualTo(1);
        assertThat(cache.puts).as("模型给出的答案应当被缓存").isEqualTo(1);

        AgentAnswer second = service.ask(repo.id(), question, AgentMode.SINGLE_HOP, null, 8);
        assertThat(client.calls()).as("第二次不该再调模型").isEqualTo(1);
        assertThat(second.cached()).as("必须标成来自缓存").isTrue();
        assertThat(second.answer()).isEqualTo(first.answer());
        assertThat(second.generationTokens()).as("保留当初的 token 用量，界面才能说清省了多少")
                .isEqualTo(first.generationTokens());
        System.out.printf("%n[答案缓存] 第一次生成 %d token → 第二次命中缓存（模型调用仍为 %d 次）%n",
                first.generationTokens(), client.calls());
    }

    @Test
    void deterministicQuestionsNeverTouchTheCache() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        RecordingCache cache = new RecordingCache();
        AgentService service = serviceWith(new ScriptedLlmClient((turn, prompt) -> {
            throw new AssertionError("确定性问题不该调用模型");
        }), cache);

        AgentAnswer answer = service.ask(repo.id(), "谁调用了 " + target.qualifiedName() + "？",
                AgentMode.MULTI_HOP, null, 8);

        assertThat(answer.answeredBy()).isEqualTo(AnsweredBy.STATIC);
        assertThat(cache.gets).as("确定性问题根本不该查缓存").isZero();
        assertThat(cache.puts).as("也不该写缓存（几毫秒的结果，缓存只有风险没有收益）").isZero();
    }

    @Test
    void refusalsAreNotCachedSoTransientFailuresDoNotStick() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        String question = "这个方法大致是做什么的：" + target.qualifiedName() + "？";
        RecordingCache cache = new RecordingCache();
        // 模型只给结论、不给证据 → 按设计拒答
        AgentService service = serviceWith(ScriptedLlmClient.lines(
                "{\"thought\":\"够了\",\"final\":{\"answer\":\"我觉得是这样\",\"evidence\":[],\"refused\":false}}"),
                cache);

        AgentAnswer answer = service.ask(repo.id(), question, AgentMode.SINGLE_HOP, null, 8);

        assertThat(answer.refused()).isTrue();
        assertThat(cache.puts).as("拒答不该被缓存：拒答常常是临时故障，固化下来就成了永久失败").isZero();
    }

    @Test
    void aBrokenCacheNeverBreaksTheAnswer() {
        RepoView repo = corpus();
        SymbolView target = firstWithCallers(repo.id());
        String question = "这个方法大致是做什么的：" + target.qualifiedName() + "？";
        AgentService service = serviceWith(ScriptedLlmClient.lines(
                        ScriptedLlmClient.singleHopAnswer("缓存坏掉也照样回答", target.filePath(),
                                target.startLine(), target.endLine(), null)),
                new ExplodingCache());

        AgentAnswer answer = service.ask(repo.id(), question, AgentMode.SINGLE_HOP, null, 8);

        assertThat(answer.refused()).as("缓存抛异常不能影响问答本身").isFalse();
        assertThat(answer.answer()).isEqualTo("缓存坏掉也照样回答");
    }

    @Test
    void theCacheKeyCarriesTheIndexVersionSoAnswersNeverGoStale() {
        String question = "谁调用了它";
        String model = "glm-4-flash";
        String v1 = RedisAnswerCache.key(7L, "2026-09-19T10:00:00", question, "MULTI_HOP", model);
        String v2 = RedisAnswerCache.key(7L, "2026-09-19T11:00:00", question, "MULTI_HOP", model);

        assertThat(v1).isNotEqualTo(v2).as("索引版本变了，键就必须变 —— 否则会拿到上一版代码的答案");
        assertThat(RedisAnswerCache.key(7L, "2026-09-19T10:00:00", question, "MULTI_HOP", model)).isEqualTo(v1);
        assertThat(RedisAnswerCache.key(8L, "2026-09-19T10:00:00", question, "MULTI_HOP", model)).isNotEqualTo(v1);
        assertThat(RedisAnswerCache.key(7L, "2026-09-19T10:00:00", question, "SINGLE_HOP", model)).isNotEqualTo(v1);
        assertThat(RedisAnswerCache.key(7L, "2026-09-19T10:00:00", question + "？", "MULTI_HOP", model)).isNotEqualTo(v1);
        // 换模型（或换供应商）之后，同一个问题必须重新生成：旧答案是上一个模型给的
        assertThat(RedisAnswerCache.key(7L, "2026-09-19T10:00:00", question, "MULTI_HOP", "deepseek-chat"))
                .as("模型名必须进键 —— 否则换模型后还会拿到旧模型的答案").isNotEqualTo(v1);
        assertThat(v1).startsWith("readcodeai:answer:").as("键前缀要能一眼看出是谁写的");
    }

    // ------------------------------------------------------------------ 测试替身

    /** 记录调用的假缓存（离线测契约，不需要 Redis）。 */
    private static final class RecordingCache implements AnswerCache {

        private final Map<String, AgentAnswer> store = new HashMap<>();
        private int gets;
        private int puts;

        @Override
        public Optional<AgentAnswer> get(long repoId, String indexedAt, String question, String mode,
                                         String model) {
            gets++;
            return Optional.ofNullable(store.get(key(repoId, indexedAt, question, mode)));
        }

        @Override
        public void put(long repoId, String indexedAt, String question, String mode, String model,
                        AgentAnswer answer) {
            puts++;
            store.put(key(repoId, indexedAt, question, mode), answer);
        }

        @Override
        public String describe() {
            return "测试替身";
        }

        private static String key(long repoId, String indexedAt, String question, String mode) {
            return repoId + "|" + indexedAt + "|" + mode + "|" + question;
        }
    }

    /** 每次调用都炸的缓存 —— 验证"缓存坏了不能影响问答"。 */
    private static final class ExplodingCache implements AnswerCache {

        @Override
        public Optional<AgentAnswer> get(long repoId, String indexedAt, String question, String mode,
                                         String model) {
            throw new IllegalStateException("Redis 挂了");
        }

        @Override
        public void put(long repoId, String indexedAt, String question, String mode, String model,
                        AgentAnswer answer) {
            throw new IllegalStateException("Redis 挂了");
        }

        @Override
        public String describe() {
            return "每次都炸（测试用）";
        }
    }

    private AgentService serviceWith(ScriptedLlmClient client, AnswerCache cache) {
        AnswerService singleHop = new AnswerService(textRetriever, queries, queryRouter, contextSelector,
                evidenceVerifier, evidenceRepair, com.readcodeai.verify.TestCheckers.NONE, client, properties);
        return new AgentService(singleHop, new AgentLoop(toolRegistry, evidenceVerifier, com.readcodeai.verify.TestCheckers.NONE, client, 2),
                queryRouter, queries, client, cache, properties);
    }

    private SymbolView firstWithCallers(long repoId) {
        List<SymbolView> candidates = repository.mostCalledMethods(repoId, 20);
        return candidates.stream()
                .filter(symbol -> !queries.callers(symbol.id()).isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("语料里找不到有调用点的符号"));
    }

    private RepoView corpus() {
        var resolved = TestCorpus.resolve(indexer, queries);
        assumeThat(resolved).as("语料不存在时跳过：" + TestCorpus.SAMPLE).isPresent();
        return resolved.get();
    }
}
