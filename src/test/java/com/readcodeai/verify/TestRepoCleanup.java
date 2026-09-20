package com.readcodeai.verify;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 测试自己造出来的仓库，**测试自己删干净**。
 *
 * <p>为什么需要它：索引类测试会建仓库行（{@code repo} + 符号 + 调用边 + 检索块）。
 * 正常路径上测试末尾会删，但**断言失败时不会走到那一行** —— 于是库里留一堆 FAILED 仓库，
 * 界面上看到的就是"仓库列表里全是看不懂的临时路径"（实测发生过：一次联调后留下 6 条）。
 *
 * <p><b>踩过的坑</b>：Windows 路径里的 {@code \} 在 SQL LIKE 里是**转义字符**，
 * 于是 {@code 'C:\...\junit-123\%'} 这样的模式根本匹配不上（{@code \%} 被当成"字面量百分号"）。
 * 所以这里先把库里的路径归一化成正斜杠再比，调用方也传正斜杠前缀。
 */
public final class TestRepoCleanup {

    private TestRepoCleanup() {
    }

    /**
     * @param pathPatterns **正斜杠**前缀（如 {@code "C:/tmp/junit-123/%"}）。调用方负责把模式写窄
     * @return 删掉的仓库行数
     */
    public static int deleteReposUnder(JdbcTemplate jdbc, String... pathPatterns) {
        int deleted = 0;
        for (String pattern : pathPatterns) {
            // CHAR(92) 就是反斜杠：这样 SQL 里一个转义字符都不用写（'\\' 在 SQL 字符串里会吞掉闭合引号，
            // 那个坑在这段代码上已经踩过一次）
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM `repo` WHERE REPLACE(root_path, CHAR(92), '/') LIKE ?",
                    Long.class, pattern);
            for (Long id : ids) {
                // 外键级联会带走 source_file / symbol / call_edge / chunk / index_job
                deleted += jdbc.update("DELETE FROM `repo` WHERE id = ?", id);
            }
        }
        return deleted;
    }
}
