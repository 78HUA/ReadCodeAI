package com.readcodeai.agent;

import com.readcodeai.config.LlmClient;
import com.readcodeai.evidence.EvidenceVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把多跳循环装配成一个 Bean。
 *
 * <p>用 {@code @Bean} 而不是给 {@link AgentLoop} 打 {@code @Component}，
 * 是为了让它的依赖（尤其是 {@link LlmClient}）保持**显式可替换** ——
 * 测试里换成脚本化的假客户端，就能在不起网络、不花 token 的情况下
 * 把环检测、预算终止、证据核验这些机制逐条验证掉。
 */
@Configuration
public class AgentConfig {

    @Bean
    AgentLoop agentLoop(ToolRegistry toolRegistry, EvidenceVerifier evidenceVerifier, LlmClient llmClient) {
        return new AgentLoop(toolRegistry, evidenceVerifier, llmClient);
    }
}
