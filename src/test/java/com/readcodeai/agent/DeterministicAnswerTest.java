package com.readcodeai.agent;

import com.readcodeai.agent.model.AnsweredBy;
import com.readcodeai.agent.model.AskAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * **最有说服力的一条验证：确定性路线在没有 LLM 的情况下照样能用。**
 *
 * <p>所以这个测试类刻意**清空所有 LLM 配置** —— 如果哪天有人不小心让
 * 「谁调用了 X」也走了模型，这里会立刻失败。
 *
 * <p>这正是「可降级」设计要证明的东西：**剥掉模型，工具仍然是个能用的工具**，
 * 而不是一个空壳 —— 因为这部分本来就不需要 AI。
 */
@SpringBootTest(properties = {
        "readcodeai.llm.enabled=false",
        "readcodeai.llm.api-key=",
        "readcodeai.llm.base-url=",
        "readcodeai.llm.model="
})
class DeterministicAnswerTest {

    @Autowired
    private AnswerService answerService;

    @Test
    void answersCallersQuestionWithoutAnyLlm() {
        AskAnswer answer = answerService.ask(null, "谁调用了 AddressBookService 的 deleteAddressBook 方法？", null, 5);
        assumeTrue(answer.retrievedFrom() != null, "库里还没索引，跳过");

        System.out.printf("%n[确定性回答·无模型] %s%n  answeredBy=%s%n  证据：%n",
                answer.answer(), answer.answeredBy());
        answer.evidence().forEach(e -> System.out.printf("    %s  —— %s%n", e.location(), e.why()));

        assertThat(answer.answeredBy())
                .as("这类问题的答案应该由调用图直接给出，不该经过模型")
                .isEqualTo(AnsweredBy.STATIC);
        assertThat(answer.refused()).isFalse();
        assertThat(answer.evidence()).as("即使没有调用点，也要能指出目标符号本身").isNotEmpty();
        assertThat(answer.promptTokens()).as("没走模型就不该有 token 消耗").isZero();
    }

    @Test
    void answersImplementationsQuestionWithoutAnyLlm() {
        AskAnswer answer = answerService.ask(null, "AddressBookService 有哪些实现类？", null, 5);

        System.out.printf("%n[确定性回答] %s%n  证据：%n", answer.answer());
        answer.evidence().forEach(e -> System.out.printf("    %s  —— %s%n", e.location(), e.why()));

        assertThat(answer.answeredBy()).isEqualTo(AnsweredBy.STATIC);
        assertThat(answer.answer()).contains("实现");
        assertThat(answer.evidence()).isNotEmpty();
    }

    @Test
    void semanticQuestionsAreRefusedRatherThanAnsweredWithoutEvidenceWhenNoLlm() {
        // 模糊语义类问题需要模型；没配模型时必须明确报错，而不是编一个答案出来
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> answerService.ask(null, "这个项目的订单状态是怎么流转的？", null, 5))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("确定性能力不受影响");
    }
}
