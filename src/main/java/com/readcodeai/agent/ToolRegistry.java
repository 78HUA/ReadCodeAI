package com.readcodeai.agent;

import com.readcodeai.agent.tools.AgentTool;
import com.readcodeai.agent.tools.FindCalleesTool;
import com.readcodeai.agent.tools.FindCallersTool;
import com.readcodeai.agent.tools.FindDefinitionTool;
import com.readcodeai.agent.tools.FindImplementationsTool;
import com.readcodeai.agent.tools.ReadSymbolTool;
import com.readcodeai.agent.tools.TextSearchTool;
import com.readcodeai.agent.tools.ToolContext;
import com.readcodeai.agent.tools.ToolResult;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.TextRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：**模型能用的全部手段就是这些**，没有别的。
 *
 * <p>工具清单是固定的、确定性的、可测的 —— 这一点很重要：
 * Agent 的能力边界因此是**可以讲清楚的**（"它能查符号、调用关系、类型层次、全文，就这些"），
 * 而不是"看模型心情"。
 *
 * <p>名字做了归一化（大小写、下划线、连字符），因为实测模型爱写 {@code find_callers}、
 * {@code FIND-CALLERS} 这类变体。为这点差异中断一轮，不值得。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(SymbolQueryService symbolQueryService, TextRetriever textRetriever) {
        register(new FindDefinitionTool(symbolQueryService));
        register(new FindCallersTool(symbolQueryService));
        register(new FindCalleesTool(symbolQueryService));
        register(new FindImplementationsTool(symbolQueryService));
        register(new ReadSymbolTool(symbolQueryService));
        register(new TextSearchTool(textRetriever));
    }

    private void register(AgentTool tool) {
        tools.put(normalize(tool.name()), tool);
    }

    static String normalize(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(normalize(name)));
    }

    public List<AgentTool> all() {
        return new ArrayList<>(tools.values());
    }

    public String names() {
        return String.join("、", tools.values().stream().map(AgentTool::name).toList());
    }

    /** 对外的执行入口：**未知工具与工具内部异常都变成一条观察**，不让整次问答崩掉。 */
    public ToolResult execute(ToolContext context, String toolName, Map<String, Object> args) {
        Optional<AgentTool> tool = find(toolName);
        if (tool.isEmpty()) {
            return ToolResult.miss("没有这个工具：「" + toolName + "」。可用工具：" + names());
        }
        try {
            return tool.get().execute(context, args);
        } catch (RuntimeException e) {
            // 工具炸了也是**可观察的事实**而不是事故：把它当一条观察还给模型（它会换个查法），
            // 同时记日志 —— 但不要让一次查询失败终止整轮多跳。
            log.warn("工具 {} 执行失败：{}", toolName, e.toString());
            return ToolResult.miss("工具 " + toolName + " 执行失败（" + e.getClass().getSimpleName()
                    + "：" + e.getMessage() + "）。可以换个查法，或改问别的。");
        }
    }

    /** 拼进系统提示词的工具清单。 */
    public String describeForPrompt() {
        StringBuilder text = new StringBuilder("\n可用工具（每轮只能用一个）：\n");
        for (AgentTool tool : tools.values()) {
            text.append("- ").append(tool.name()).append(' ').append(tool.usage())
                    .append(" —— ").append(tool.description()).append('\n');
        }
        return text.toString();
    }
}
