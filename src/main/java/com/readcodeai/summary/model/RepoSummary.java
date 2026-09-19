package com.readcodeai.summary.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 仓库的结构化摘要：**结构是算出来的，语义是模型补的，两者分开装**。
 *
 * <p>这个拆法是这一步的核心约束：{@link Structure} 里的每一个数字、每一个符号名
 * 都来自索引（查表/图查询），模型一个字也插不进来；{@link Semantics} 才是模型写的，
 * 而且写出来的每个符号名还要**反过来在索引里核对一遍**。
 *
 * <p>为什么要这么较真：把「总结」做成"把文件塞给模型让它自己看"，得到的是一段听起来
 * 很有道理的文字；而这里要的是**可核对的事实 + 明确标注来源的推测**。
 */
public record RepoSummary(
        long repoId,
        String repoName,
        String rootPath,
        String commitHash,
        LocalDateTime indexedAt,
        Structure structure,
        Semantics semantics) {

    /** 全部来自索引的硬事实。 */
    public record Structure(
            Scale scale,
            /** 实际用来切分模块的包前缀（会自动加深到能分出有意义的粒度，见 RepoSummaryService） */
            String modulePrefix,
            List<Module> modules,
            List<SymbolRef> entryPoints,
            List<Ranked> callHubs,
            List<Ranked> topMethods,
            List<Ranked> implementations,
            List<SymbolRef> uncalledClasses) {
    }

    /** 规模与质量指标。解析率、调用解析率同时给出 —— 它们决定下面那些结论有多可信。 */
    public record Scale(
            int fileCount,
            int parsedOkCount,
            double parseSuccessRate,
            int totalLoc,
            int classCount,
            int interfaceCount,
            int methodCount,
            int totalSymbols,
            int callEdges,
            int resolvedCallEdges,
            double callResolveRate) {
    }

    /**
     * 一个模块（= 公共包前缀之后的第一段包名）。
     *
     * @param keyTypes    模块里最有代表性的类型（按类内方法数排），前端可点开看代码
     * @param samplePaths 模块下的文件样例（让人一眼看出这里装的是什么）
     */
    public record Module(
            String name,
            String packagePrefix,
            int fileCount,
            int totalLoc,
            int symbolCount,
            List<SymbolRef> keyTypes,
            List<String> samplePaths) {
    }

    /**
     * 指向一个符号 —— 带 {@code symbolId} 与文件行号，所以摘要里的每个名词都能**点开看代码**，
     * 这是"每个数字都能核对"这条要求的落地方式。
     */
    public record SymbolRef(
            long symbolId,
            String kind,
            String qualifiedName,
            String signature,
            String filePath,
            int startLine,
            int endLine) {

        public String location() {
            return filePath + ":" + startLine + "-" + endLine;
        }
    }

    /** 带计数的排行项（被调用次数、子类型个数）。 */
    public record Ranked(SymbolRef symbol, int count) {
    }

    /**
     * 模型补的语义部分。没配模型时 {@code available=false}，**结构部分照常返回**（可降级设计）。
     *
     * @param cached     是不是**从缓存里取出来的**（键 = 仓库 + 索引版本 + 模型名）。
     *                   这一栏必须在界面上显示出来：给使用者一份上次生成的说明而不告诉他，就是在骗人
     * @param generatedAt 这份说明是什么时候生成的
     * @param promptTokens / completionTokens 生成它花掉的 token（缓存命中时为 0：这次没花钱）
     */
    public record Semantics(
            boolean available,
            String model,
            String reason,
            /** 「这个项目是做什么的」一句话 —— 材料来自索引，模型只负责组织语言 */
            ProjectNote overview,
            /** 主要功能 3–5 条 */
            List<ProjectNote> features,
            List<ModuleNote> notes,
            boolean cached,
            java.time.LocalDateTime generatedAt,
            int promptTokens,
            int completionTokens) {

        public long totalTokens() {
            return (long) promptTokens + completionTokens;
        }
    }

    /**
     * 一个模块的一句话说明。
     *
     * @param mentionedSymbols  这句话里提到的符号名
     * @param unverifiedSymbols 其中**在索引里找不到**的那些 —— 模型编的名字会被如实标出来，
     *                          而不是当作可信内容展示
     * @param numbersInNote     这句话里出现的数字。提示词明确要求"别写数字"（数字由系统给），
     *                          写了就标出来 —— 因为**模型说的数字是不受核验的**，
     *                          而摘要里所有数字都应该能在结构部分找到出处
     * @param verified          {@code unverifiedSymbols} 与 {@code numbersInNote} 都为空。
     *                          **做成字段而不是方法**：record 的方法不会进 JSON，
     *                          前端拿到的会是 undefined（这个坑踩过两次，所以这里显式序列化）
     */
    public record ModuleNote(
            String module,
            String note,
            List<String> mentionedSymbols,
            List<String> unverifiedSymbols,
            List<String> numbersInNote,
            boolean verified) {
    }

    /**
     * 模型写的一句/一段话（项目级）。与 {@link ModuleNote} 同一套核对口径：
     * 提到的名字要能在索引里找到、自说自话的数字要被标出来。
     */
    public record ProjectNote(
            String text,
            List<String> mentionedSymbols,
            List<String> unverifiedSymbols,
            List<String> numbersInNote,
            boolean verified) {
    }

    public static Semantics noSemantics(String reason) {
        return new Semantics(false, null, reason, null, List.of(), List.of(), false, null, 0, 0);
    }
}
