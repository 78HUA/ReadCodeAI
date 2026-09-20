package com.readcodeai.api;

import com.readcodeai.config.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行状态的验收：**每一项都要如实报出"能不能用"**，不能都写 OK。
 *
 * <p>为什么值得钉：状态页最容易退化成"一片绿" —— 那样它就等于没有。
 * 这里断言的是"降级会被报出来"：测试环境没配 LLM Key，所以大模型那一项必须是 {@code ok=false}
 * 且原因写清（"未配置"），而不是显示成正常。
 */
@SpringBootTest
class StatusControllerTest {

    @Autowired
    private StatusController controller;

    @Autowired
    private LlmClient llmClient;

    @Test
    void reportsEveryDegradableComponentHonestly() {
        StatusController.Status status = controller.status().data();

        assertThat(status.jvm().javaVersion()).as("JVM 版本要能一眼看到").isNotBlank();
        assertThat(status.jvm().maxHeapMb()).as("堆上限要报出来（排查内存问题第一步）").isGreaterThan(0);
        assertThat(status.components()).as("可降级组件逐项列出").hasSizeGreaterThanOrEqualTo(6);

        StatusController.Component llm = component(status, "大模型");
        assertThat(llm.ok()).as("测试环境没配 Key，这一项就该报 false").isEqualTo(llmClient.available());
        if (!llmClient.available()) {
            assertThat(llm.detail()).as("降级要说清原因与影响，而不是只给个红点").contains("未配置");
        }

        StatusController.Component queue = component(status, "索引任务队列");
        assertThat(queue.detail()).as("队列那一项要说清当前是进程内还是 MQ（决定了重启丢不丢任务）")
                .containsAnyOf("进程内", "RabbitMQ");
        assertThat(component(status, "答案缓存").detail()).isNotBlank();
        assertThat(component(status, "仓库锁").detail()).isNotBlank();
        assertThat(component(status, "接口限流").detail()).isNotBlank();

        System.out.printf("%n[运行状态] JVM %s · 堆上限 %d MB · 组件 %d 项%n",
                status.jvm().javaVersion(), status.jvm().maxHeapMb(), status.components().size());
        status.components().forEach(component ->
                System.out.printf("  %s %s —— %s%n", component.ok() ? "✓" : "✗", component.name(),
                        component.detail()));
    }

    private static StatusController.Component component(StatusController.Status status, String namePart) {
        return status.components().stream()
                .filter(component -> component.name().contains(namePart))
                .findFirst()
                .orElseThrow(() -> new AssertionError("状态里少了组件：" + namePart));
    }
}
