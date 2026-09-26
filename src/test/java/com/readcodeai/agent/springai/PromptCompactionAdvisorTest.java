package com.readcodeai.agent.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-B 压缩的**确定性验收**：同一条轨迹下，量"发给模型的提示词字数"。
 *
 * <p>为什么不拿真模型跑两遍对比 token：真实模型的跳数会波动（实测 3.67 跳 vs 5.00 跳），
 * 两次的轨迹根本不是同一条，token 差多少说明不了压缩省了多少。**假模型 + 固定轨迹**才能隔离出压缩本身的效果。
 */
class PromptCompactionAdvisorTest {

    /** 一条 4000 字的工具结果 —— 与真实 readSymbol 的输出量级一致。 */
    private static String longObservation(int hop) {
        return ("[第 " + hop + " 跳] readSymbol → 第 " + hop + " 段源码\n" + "public void m" + hop + "() { }\n".repeat(120));
    }

    private static Prompt promptWithHops(int hops) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("问题：这个参数从哪来？"));
        for (int hop = 1; hop <= hops; hop++) {
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call-" + hop, "readSymbol", longObservation(hop))))
                    .build());
        }
        ChatOptions options = ToolCallingChatOptions.builder().build();
        return new Prompt(messages, options);
    }

    /** 发给模型的**全部**正文（含工具结果 —— 工具结果不在 getText() 里）。 */
    private static int chars(Prompt prompt) {
        int total = 0;
        for (Message message : prompt.getInstructions()) {
            total += message.getText() == null ? 0 : message.getText().length();
            if (message instanceof ToolResponseMessage tool) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    total += response.responseData() == null ? 0 : response.responseData().length();
                }
            }
        }
        return total;
    }

    private static long compactedCount(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                .filter(r -> String.valueOf(r.responseData()).contains("[已压缩]"))
                .count();
    }

    @Test
    void 五跳里只压更早的三跳且提示词显著变短() {
        Prompt prompt = promptWithHops(5);
        int before = chars(prompt);

        Prompt compacted = new PromptCompactionAdvisor(2).compact(prompt);
        int after = chars(compacted);

        assertThat(compactedCount(compacted)).as("5 跳、保留近 2 跳 ⇒ 压缩 3 条").isEqualTo(3);
        assertThat(compactedCount(prompt)).as("原提示词里没有压缩标记").isZero();
        assertThat(after).as("压缩后正文显著变短（%d → %d 字）", before, after).isLessThan(before / 2);
        assertThat(compacted.getOptions())
                .as("**必须带上原来的 options** —— 工具清单在里面，丢了循环就跑不动")
                .isSameAs(prompt.getOptions());
        assertThat(prompt.getInstructions()).as("原提示词不被就地修改").hasSize(6);
    }

    @Test
    void 保留跳数设为0时不压缩() {
        Prompt prompt = promptWithHops(5);

        Prompt compacted = new PromptCompactionAdvisor(0).compact(prompt);

        assertThat(compacted).isSameAs(prompt);
    }

    @Test
    void 跳数没超过保留数时不压缩() {
        Prompt prompt = promptWithHops(2);

        Prompt compacted = new PromptCompactionAdvisor(2).compact(prompt);

        assertThat(compacted).isSameAs(prompt);
    }
}
