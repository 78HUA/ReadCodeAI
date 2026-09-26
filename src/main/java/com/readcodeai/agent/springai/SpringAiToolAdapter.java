package com.readcodeai.agent.springai;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * 把现有的 6 个 {@link com.readcodeai.agent.tools.AgentTool} 适配成 Spring AI 的 {@code @Tool} 方法。
 *
 * <p>**一行循环代码都没有**：工具调用的循环由框架的 ToolCallingAdvisor 跑（执行器见
 * {@link BudgetToolCallingManager}）。这个类只负责"把参数翻译成 args、把结果翻译成字符串"，
 * 真正的活儿仍由主项目原来的工具类干 —— 所以确定性能力（符号表、调用图、全文检索）一点没变。
 *
 * <p>注意每个 {@code @Tool} 的 description：它会进模型看到的工具清单里，直接影响它会不会用对工具。
 * 这里的文案是照着原来 {@code AgentTool.usage()} 的意思重写的，比手写版短（原生协议自带参数 schema）。
 */
public class SpringAiToolAdapter {

    private final SpringAiLoopState state;

    public SpringAiToolAdapter(SpringAiLoopState state) {
        this.state = state;
    }

    @Tool(description = "定位：这个名字定义在哪（返回文件 + 行号 + 定义行原文）。一切查询的起点。")
    public String findDefinition(
            @ToolParam(description = "符号名，例如 transfer 或 BankService.transfer") String symbol) {
        return state.invoke("findDefinition", Map.of("symbol", symbol));
    }

    @Tool(description = "谁调用了它：返回每个调用点的文件 + 行号 + 该行代码。追“这个值/参数从哪来”就用它一跳一跳往上查。")
    public String findCallers(
            @ToolParam(description = "方法名，例如 transfer 或 BankService.transfer") String symbol) {
        return state.invoke("findCallers", Map.of("symbol", symbol));
    }

    @Tool(description = "它调用了谁：返回被调用者的文件 + 行号。查“改了这个会影响什么”从这里向下走。")
    public String findCallees(
            @ToolParam(description = "方法名，例如 transfer 或 BankService.transfer") String symbol) {
        return state.invoke("findCallees", Map.of("symbol", symbol));
    }

    @Tool(description = "有哪些实现类 / 子类（只查直接关系）。目标必须是类型，不能是方法。")
    public String findImplementations(
            @ToolParam(description = "类型名，例如 BankService 或 com.x.BankService") String symbol) {
        return state.invoke("findImplementations", Map.of("symbol", symbol));
    }

    @Tool(description = "读出某个符号的真实源码（带行号）。结论里的 snippet 要从这里逐字照抄。")
    public String readSymbol(
            @ToolParam(description = "符号名，例如 transfer 或 BankService.transfer") String symbol) {
        return state.invoke("readSymbol", Map.of("symbol", symbol));
    }

    @Tool(description = "全文检索（不知道符号名时用它）：按关键词搜代码与注释片段，返回文件 + 行号 + 片段。")
    public String textSearch(
            @ToolParam(description = "搜索关键词，例如 “登录校验” 或 “transfer(”") String query) {
        return state.invoke("textSearch", Map.of("query", query));
    }
}
