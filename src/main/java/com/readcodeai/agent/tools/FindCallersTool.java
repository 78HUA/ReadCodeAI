package com.readcodeai.agent.tools;

import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.SymbolView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 谁调用了它 —— **多跳上溯的那条腿**。
 *
 * <p>「这个参数从哪来」这类问题就是靠它一跳一跳往上追出来的：
 * 查 A 的调用者 → 拿到 B → 再查 B 的调用者 → …… 跳几跳由模型决定，不由我们写死。
 *
 * <p>同名重载会一并查（人问"谁调用了 read"指的是全部重载）；重复的调用者按符号去重 ——
 * 一个方法在 10 个地方调用了目标，链路上它仍然只算一个上游节点。
 */
public class FindCallersTool implements AgentTool {

    /** 一次列多少个调用点。列太多会把上下文吃光，而这个工具的价值在"上游是谁"，不在逐条枚举。 */
    private static final int MAX_LISTED = 20;

    /** 证据条数上限（给机器看的那份，比给模型看的多）；只防病态数据，正常用不到。 */
    private static final int MAX_EVIDENCE = 200;

    private final SymbolQueryService queries;

    public FindCallersTool(SymbolQueryService queries) {
        this.queries = queries;
    }

    @Override
    public String name() {
        return "findCallers";
    }

    @Override
    public String usage() {
        return "{\"symbol\":\"类名.方法名 或 全限定名\"}";
    }

    @Override
    public String description() {
        return "查谁调用了这个方法/构造器（沿调用链向上跳一步）。返回调用者的限定名与调用点行号。";
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
        int totalCallSites = 0;

        for (SymbolView target : resolution.symbols()) {
            List<CallSiteView> callers = queries.callers(target.id());
            totalCallSites += callers.size();
            // subjects 与 evidence 是**给机器看的**（指标与核验），所以不按"给模型看的那份"截断：
            // 截断会让"这次查到了多少个上游"变成观察文本的属性，而不是事实。
            callers.forEach(call -> {
                subjects.add(call.symbolQualifiedName());
                if (evidence.size() < MAX_EVIDENCE) {
                    evidence.add(new AskEvidence(call.callSiteFile(), call.callLine(), call.callLine(), "",
                            "被 " + call.symbolQualifiedName() + " 调用"));
                }
            });

            observation.append("\n").append(target.qualifiedName()).append(" 的调用点：");
            if (callers.isEmpty()) {
                observation.append(" 没有任何调用点（可能是入口方法、也可能是死代码）");
                continue;
            }
            observation.append("\n");
            callers.stream().limit(MAX_LISTED).forEach(call -> observation
                    .append("- ").append(call.symbolQualifiedName())
                    .append(" @ ").append(call.callSiteLocation()).append("\n"));
            if (callers.size() > MAX_LISTED) {
                observation.append("（其余 ").append(callers.size() - MAX_LISTED).append(" 处略）\n");
            }
        }

        if (subjects.isEmpty()) {
            observation.insert(0, "沿着调用链向上**没有任何上游**（这些符号没有被仓库内的代码调用过）：");
        } else {
            observation.insert(0, "向上找到 " + subjects.size() + " 个不同的调用者（共 "
                    + totalCallSites + " 处调用点）：");
        }
        if (!resolution.note().isBlank()) {
            observation.append(resolution.note()).append("\n");
        }
        return ToolResult.of(observation.toString(), evidence, new ArrayList<>(subjects));
    }
}
