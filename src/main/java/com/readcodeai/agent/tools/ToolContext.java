package com.readcodeai.agent.tools;

import java.nio.file.Path;

/**
 * 工具执行时的环境：**只给它仓库范围**，不给模型任何东西。
 *
 * <p>{@code repoRoot} 是必须的：读源码、核验证据都要把索引里的相对路径还原成磁盘上的真实文件。
 */
public record ToolContext(long repoId, Path repoRoot) {
}
