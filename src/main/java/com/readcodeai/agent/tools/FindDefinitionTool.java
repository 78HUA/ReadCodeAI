package com.readcodeai.agent.tools;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.SymbolView;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 定位：这个名字定义在哪。
 *
 * <p>它是**一切查询的起点** —— 模型拿到问题后要先知道"问题里说的那个东西到底是哪个符号"，
 * 后面的调用关系才有得查。
 */
public class FindDefinitionTool implements AgentTool {

    private final SymbolQueryService queries;

    public FindDefinitionTool(SymbolQueryService queries) {
        this.queries = queries;
    }

    @Override
    public String name() {
        return "findDefinition";
    }

    @Override
    public String usage() {
        return "{\"symbol\":\"类名.方法名 或 全限定名\"}";
    }

    @Override
    public String description() {
        return "定位符号的定义位置（文件 + 起止行）。名字有歧义时会返回候选，需要用限定名再查一次。";
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

        List<SymbolView> symbols = resolution.symbols();
        StringBuilder observation = new StringBuilder("找到 ").append(symbols.size()).append(" 个符号：");
        symbols.forEach(item -> observation.append("\n- ").append(SymbolResolver.describe(item)));
        if (!resolution.note().isBlank()) {
            observation.append("\n").append(resolution.note());
        }
        return ToolResult.of(observation.toString(),
                symbols.stream()
                        .map(item -> new AskEvidence(item.filePath(),
                                item.startLine(), item.endLine(), "",
                                "定义：" + item.kind() + " " + item.signature()))
                        .toList(),
                symbols.stream().map(SymbolView::qualifiedName).collect(Collectors.toList()));
    }
}
