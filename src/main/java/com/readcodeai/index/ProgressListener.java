package com.readcodeai.index;

/**
 * 索引进度回调。
 *
 * <p>为什么必须有它：索引是**分钟级**的长任务（实测 2.3 万行 16 秒，十万行就是分钟级），
 * 界面上如果没有进度，用户看到的就是"卡住了"。而进度**只能由干活的人自己报** ——
 * 外面看不出"解析到第几个文件了"。
 *
 * <p>实现方负责节流：逐文件写库对几千个文件的仓库来说太浪费（见 {@code AsyncIndexer} 里的实现）。
 */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NOOP = (stage, done, total, message) -> {
    };

    /**
     * @param stage   QUEUED / FETCHING / EXTRACTING / SCANNING / PARSING / STORING / DONE / FAILED
     * @param done    当前阶段已完成的量
     * @param total   当前阶段的总量（未知时为 0）
     * @param message 给人看的一句话（可为 null）
     */
    void onProgress(String stage, int done, int total, String message);
}
