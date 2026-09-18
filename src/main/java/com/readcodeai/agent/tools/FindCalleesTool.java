package com.readcodeai.agent.tools;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.retrieve.NotFoundException;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.SymbolView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 它调用了谁 —— 向下追的那条腿（"改了这个方法会影响什么"要从这里走）。
 *
 * <p>未解析的调用**照实说**：外部依赖、动态调用、静态分析盲区都会出现在这里，
 * 伪装成"没有调用"比承认盲区更危险。所以观察里会明确写出"另有 N 处未解析"。
 */
public class FindCalleesTool implements AgentTool {

    private static final int MAX_LISTED = 20;

    private final SymbolQueryService queries;

    public FindCalleesTool(SymbolQueryService queries) {
        this.queries = queries;
    }

    @Override
    public String name() {
        return "findCallees";
    }

    @Override
    public String usage() {
        return "{\"symbol\":\"类名.方法名 或 全限定名\"}";
    }

    @Override
    public String description() {
        return "查这个方法调用了哪些仓库内的符号（沿调用链向下跳一步）。未解析的外部调用会被单独计数。";
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String symbol = AgentTool.arg(args, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return AgentTool.missing("symbol", usage());
        }
        SymbolResolver.Resolution resolution = SymbolResolver.resolve(queries, context.repoId(), symbol);
        if (!resolution.found()) {
            return ToolResult.miss(resolution.note());
        }

        List<AskEvidence> evidence = new ArrayList<>();
        Set<String> subjects = new LinkedHashSet<>();
        StringBuilder observation = new StringBuilder();
        int unresolvedTotal = 0;

        for (SymbolView target : resolution.symbols()) {
            List<CallSiteView> callees = queries.callees(target.id());
            List<CallSiteView> resolved = callees.stream().filter(CallSiteView::resolved).toList();
            int unresolved = callees.size() - resolved.size();
            unresolvedTotal += unresolved;

            observation.append("\n").append(target.qualifiedName()).append(" 调用了 ")
                    .append(resolved.size()).append(" 个仓库内符号：\n");
            // subjects 不按观察的条数截断：观察是给模型看的那份，**指标要的是事实**
            resolved.forEach(call -> subjects.add(call.symbolQualifiedName()));
            resolved.stream().limit(MAX_LISTED).forEach(call -> observation
                    .append("- ").append(call.symbolQualifiedName())
                    .append(" @ ").append(call.callSiteLocation()).append("\n"));
            if (resolved.size() > MAX_LISTED) {
                observation.append("（其余 ").append(resolved.size() - MAX_LISTED).append(" 个略）\n");
            }
            if (unresolved > 0) {
                observation.append("（另有 ").append(unresolved)
                        .append(" 处未解析：外部依赖或静态分析盲区，**不代表没有调用**）\n");
            }
            // 证据指向被调用者的**定义位置**（而不是调用点）—— 与确定性路线的口径一致
            resolved.stream().limit(MAX_LISTED).forEach(call -> {
                SymbolView callee = symbolOf(call.symbolId());
                if (callee != null) {
                    evidence.add(new AskEvidence(callee.filePath(), callee.startLine(), callee.endLine(), "",
                            "被 " + target.name() + " 调用"));
                }
            });
        }

        if (subjects.isEmpty()) {
            observation.insert(0, "向下**没有查到任何仓库内调用**（只有外部依赖或未解析调用）：");
        } else {
            observation.insert(0, "向下找到 " + subjects.size() + " 个被调用的符号：");
        }
        if (unresolvedTotal > 0) {
            observation.append("提示：本次共 ").append(unresolvedTotal)
                    .append(" 处未解析，盲区情况见上。\n");
        }
        if (!resolution.note().isBlank()) {
            observation.append(resolution.note()).append("\n");
        }
        return ToolResult.of(observation.toString(), evidence, new ArrayList<>(subjects));
    }

    private SymbolView symbolOf(Long symbolId) {
        if (symbolId == null) {
            return null;
        }
        try {
            return queries.requireSymbol(symbolId);
        } catch (NotFoundException e) {
            return null;
        }
    }
}
