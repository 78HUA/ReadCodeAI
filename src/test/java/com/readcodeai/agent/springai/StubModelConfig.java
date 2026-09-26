package com.readcodeai.agent.springai;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 离线测试共享的**假模型桩装配**：让 {@code ChatClient} 用桩，而不是自动配置里那个真模型。
 *
 * <p>抽出来是因为默认引擎的离线用例已经不止一个类（{@code SpringAiEngineOfflineTest} 验链路、
 * {@code SpringAiLoopBehaviorTest} 验循环机制），两处都要这一套；
 * 各自写一份嵌套配置会让 Spring 建两个上下文，也会让"桩是不是真的生效"变成两处疑点。
 *
 * <p>{@code @Primary} 是必须的：自动配置在配了 key 时会创建真模型 Bean，没有它就会冲突。
 */
@TestConfiguration
public class StubModelConfig {

    @Primary
    @Bean
    ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }
}
