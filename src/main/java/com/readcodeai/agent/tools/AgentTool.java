package com.readcodeai.agent.tools;

import java.util.Map;

/**
 * 一个可以被模型调用的工具。
 *
 * <p><b>工具全部是确定性的</b>：进的是参数，出的是查表/图查询/读磁盘的结果。
 * 模型在这套体系里的职责是「决定查什么、串起结论」，不是「回忆代码」——
 * 这条分工是多跳循环不会变成幻觉放大器的前提。
 */
public interface AgentTool {

    /** 模型在 JSON 里写的工具名。 */
    String name();

    /** 参数格式（会进提示词，让模型知道怎么写）。 */
    String usage();

    /** 一句话说明这个工具能问到什么。 */
    String description();

    ToolResult execute(ToolContext context, Map<String, Object> args);

    /** 取参数；模型可能给数字/布尔，统一转成字符串。 */
    static String arg(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value).strip();
    }

    /**
     * 参数缺失时的统一回复。
     *
     * <p>**必须告诉模型正确用法**：只说"没查到"它就会乱试，白烧预算；
     * 给出用法它下一轮就能自己修好 —— 这也是"定向修正"而不是"盲目重试"。
     */
    static ToolResult missing(String key, String usage) {
        return ToolResult.miss("缺少参数 " + key + "；正确用法：" + usage);
    }
}
