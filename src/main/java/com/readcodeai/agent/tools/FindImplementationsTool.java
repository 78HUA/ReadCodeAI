package com.readcodeai.agent.tools;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.SymbolKinds;
import com.readcodeai.retrieve.model.SymbolView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 有哪些实现类 / 子类（只查直接关系）。
 *
 * <p>目标必须是类型：对一个方法问"有哪些实现"没有意义。模型给错时会得到明确的提示，
 * 而不是一个空结果 —— 空结果会让它以为是"真的没有实现"。
 */
public class FindImplementationsTool implements AgentTool {

    private final SymbolQueryService queries;

    public FindImplementationsTool(SymbolQueryService queries) {
        this.queries = queries;
    }

    @Override
    public String name() {
        return "findImplementations";
    }

    @Override
    public String usage() {
        return "{\"symbol\":\"接口或父类名\"}";
    }

    @Override
    public String description() {
        return "查一个接口/父类有哪些直接实现类或子类（类型层次）。";
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
        List<String> subjects = new ArrayList<>();
        StringBuilder observation = new StringBuilder();

        for (SymbolView target : resolution.symbols()) {
            if (!SymbolKinds.isType(target.kind())) {
                observation.append("\n").append(target.qualifiedName())
                        .append(" 是 ").append(target.kind())
                        .append("，不是类型 —— 问「有哪些实现」需要给接口或父类。");
                continue;
            }
            List<SymbolView> implementations = queries.implementations(target.id());
            observation.append("\n").append(target.qualifiedName()).append(" 有 ")
                    .append(implementations.size()).append(" 个直接实现/子类：\n");
            implementations.forEach(impl -> {
                subjects.add(impl.qualifiedName());
                evidence.add(new AskEvidence(impl.filePath(), impl.startLine(), impl.endLine(), "",
                        "实现了 " + target.name() + " 的 " + impl.kind()));
                observation.append("- ").append(SymbolResolver.describe(impl)).append("\n");
            });
        }

        if (subjects.isEmpty()) {
            observation.insert(0, "没有查到实现类或子类（只查直接关系，不含间接继承）：");
        }
        return ToolResult.of(observation.toString(), evidence, subjects);
    }
}
