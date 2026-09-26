package com.readcodeai.agent.springai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * **A-B 压缩**：近 N 跳保留原文，更早的工具结果压成"一行事实"。
 *
 * <h3>为什么必须由 advisor 来做（这是"约束要插进循环内部"的实证）</h3>
 * 多跳的对话历史由框架的 {@code ToolCallingAdvisor} 在循环里维护：每一轮它都用"累积的历史"
 * 重建 Prompt 再调模型。所以**唯一能改到"模型到底看见什么"的位置**，就是插在它内层的 advisor ——
 * 本类的 order 比它大（更内层），因此**每一轮都会被调用**。
 *
 * <p>如果只在工具返回值里做截断，只能管住"新查到的"；**旧的会一轮轮累积**（这正是迁移后
 * 静默丢掉的能力：{@code readcodeai.llm.keep-full-observations} 一度成了死配置）。
 *
 * <p>留 N 跳原文的意义：模型的下一步推理常常要用最近几跳的细节，而更早的只需要"查过什么、
 * 结论是什么"。压缩后仍带上"原文多少字"，需要细节时它可以再用工具查回来。
 */
public class PromptCompactionAdvisor implements CallAdvisor {

    /** 比 {@code ToolCallingAdvisor}（HIGHEST_PRECEDENCE + 300）更内层 = 每轮都会被调用。 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 400;

    /** 一行事实里保留多少字（够认出"查的是谁"即可）。 */
    private static final int ONE_LINE_CHARS = 120;

    private final int keepFullHops;

    public PromptCompactionAdvisor(int keepFullHops) {
        this.keepFullHops = Math.max(0, keepFullHops);
    }

    @Override
    public String getName() {
        return "PromptCompactionAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (keepFullHops <= 0) {
            return chain.nextCall(request);          // 0 = 不压缩（A/B 对照与逃生门）
        }
        Prompt compacted = compact(request.prompt());
        if (compacted == request.prompt()) {
            return chain.nextCall(request);
        }
        return chain.nextCall(ChatClientRequest.builder()
                .prompt(compacted)
                .context(request.context())
                .build());
    }

    /**
     * 把"更早那几跳"的工具结果换成一行事实。
     *
     * <p>跳的计数口径：对话历史里**每一条 {@link ToolResponseMessage} 就是一次工具调用**（一跳）。
     */
    Prompt compact(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        List<Integer> hopIndexes = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                hopIndexes.add(i);
            }
        }
        if (hopIndexes.size() <= keepFullHops) {
            return prompt;
        }
        Set<Integer> toCompact = new HashSet<>(hopIndexes.subList(0, hopIndexes.size() - keepFullHops));
        List<Message> out = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            out.add(toCompact.contains(i) && message instanceof ToolResponseMessage tool
                    ? compress(tool) : message);
        }
        // 必须带上原来的 options —— 工具清单就在里面，丢了它循环就跑不动
        return new Prompt(out, prompt.getOptions());
    }

    private static ToolResponseMessage compress(ToolResponseMessage tool) {
        List<ToolResponseMessage.ToolResponse> compacted = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
            compacted.add(new ToolResponseMessage.ToolResponse(
                    response.id(), response.name(), oneLine(response.name(), response.responseData())));
        }
        return ToolResponseMessage.builder().responses(compacted).build();
    }

    private static String oneLine(String toolName, String data) {
        String text = data == null ? "" : data.strip();
        String firstLine = text.lines().findFirst().orElse("").strip();
        if (firstLine.length() > ONE_LINE_CHARS) {
            firstLine = firstLine.substring(0, ONE_LINE_CHARS) + "…";
        }
        return "[已压缩] " + toolName + " → " + firstLine
                + "（原文 " + text.length() + " 字；细节需要时用工具重新查）";
    }
}
