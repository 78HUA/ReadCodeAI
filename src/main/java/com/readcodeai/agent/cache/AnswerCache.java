package com.readcodeai.agent.cache;

import com.readcodeai.agent.model.AgentAnswer;

import java.util.Optional;

/**
 * 答案缓存：**同一个问题、同一份索引，第二次问就不该再花那 10–40 秒和几千 token**。
 *
 * <h3>只缓存模型给出的答案</h3>
 * 确定性问题（定位 / 谁调用了它 / 有哪些实现）本来就是查表算出来的、只有几毫秒，
 * 缓存它们既没有收益，又凭空多一份"可能过期"的数据。所以：
 * <b>只有 {@code answeredBy=LLM} 且未被拒答的答案才进缓存。</b>
 *
 * <h3>缓存键里必须有"索引版本"</h3>
 * 少了它就会出现"你刚重新索引，却拿到上一版代码的回答"—— 那比慢更糟。
 * 键形如 {@code readcodeai:answer:{repoId}:{indexedAt}:{问题摘要}}，
 * 重新索引后 {@code indexedAt} 变了，旧键自然失效。
 *
 * <h3>它必须是"可以坏掉"的</h3>
 * 缓存是**加速手段，不是正确性依赖**：Redis 挂了、连不上、序列化出错，
 * 都只应该表现为"这次慢一点"，绝不能让问答失败。所以实现里所有异常都必须自己吞掉并记日志
 * （见 {@link RedisAnswerCache}），调用方也永远不要 try/catch 缓存。
 */
public interface AnswerCache {

    /**
     * 取缓存；没有或不认识的内容都返回空。
     *
     * <p>返回的就是当初存进去的那份答案 —— **"标成来自缓存"由调用方统一做**
     * （{@code AgentAnswer.asCached()}）。这条约定放在接口上，是为了不让每个实现都得记得做对：
     * 漏一个就会出现"缓存明明命中了、界面却不显示"的怪事（写测试时正好踩到）。
     */
    /**
     * @param model 生成答案用的模型名。**它必须进键**：换模型（或换供应商）之后，
     *              同一个问题的答案应当重新生成 —— 否则会返回上一个模型的答案（实测中发现的坑）
     */
    Optional<AgentAnswer> get(long repoId, String indexedAt, String question, String mode, String model);

    /** 存缓存；失败只记日志，不抛。 */
    void put(long repoId, String indexedAt, String question, String mode, String model, AgentAnswer answer);

    /** 缓存是否真的在工作（界面与日志用得上）。 */
    String describe();

    default boolean enabled() {
        return true;
    }
}
