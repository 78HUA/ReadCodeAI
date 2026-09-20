package com.readcodeai.index.queue;

/**
 * 一个索引任务的**描述**（可以进消息队列的那种）。
 *
 * <h3>为什么不是"闭包"</h3>
 * 进程内队列可以直接提交一个 {@code Supplier<IndexSummary>}（代码 + 数据一起带着跑），
 * 但消息队列过不去 —— 消息里只能放数据。所以任务要能**被序列化、被另一个进程复原**：
 * 只记"是什么类型、载荷是什么"，步骤由消费端按类型重放。
 *
 * <p>这与 {@code index_job} 表的关系：表是**状态与进度**的唯一来源（前端轮询它），
 * 消息只是"该干活了"的通知（jobId 指回表里那一行）。两者分工明确：
 * 消息丢了最多是任务没被触发，绝不会出现"消息说在跑、表里没记录"。
 *
 * @param jobId   {@code index_job.id}
 * @param type    任务类型，决定消费端怎么复原步骤
 * @param payload 类型对应的载荷：本地路径 / GitHub 链接 / 压缩包路径
 */
public record IndexTask(long jobId, Type type, String payload) {

    public enum Type {
        /** 服务器本地路径：直接索引 */
        LOCAL,
        /** GitHub 链接：先拉取源码包，再索引 */
        GIT,
        /** 上传的压缩包：先解压到工作区，再索引 */
        ARCHIVE
    }

    /** 给日志与死信排查用的一行说明。 */
    public String describe() {
        return "任务 " + jobId + "（" + type + "）：" + payload;
    }
}
