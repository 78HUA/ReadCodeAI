package com.readcodeai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 已有数据库的结构补齐：**没有迁移框架时的最小让步**。
 *
 * <p>项目一直在用 {@code CREATE TABLE IF NOT EXISTS}（不引 Flyway，见设计文档的选型表），
 * 这对"新建库"没问题，但**对已经跑起来的库加不出新列** —— 表已存在，CREATE 会被跳过。
 * 于是新加的列只有新库才有，老库一跑就报"Unknown column"。
 *
 * <p>这里的做法是"查 information_schema，缺了就 ALTER"，**幂等、只在缺列时执行**。
 * 为什么不干脆引 Flyway：结构变更目前只有这一处，引一套迁移框架（版本表、脚本目录、
 * 与 `spring.sql.init` 的先后顺序）成本大于收益。**但这条要记住**：
 * 迁移步骤一旦超过两三条，就该换成 Flyway —— 手写补齐清单迟早会漏。
 */
@Component
public class SchemaUpgrader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrader.class);

    private final JdbcTemplate jdbc;

    public SchemaUpgrader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        // source_file.kind：2026-09-20 加（文本文件只做检索，不参与解析统计）
        if (!columnExists("source_file", "kind")) {
            jdbc.execute("ALTER TABLE `source_file` ADD COLUMN "
                    + "`kind` VARCHAR(8) NOT NULL DEFAULT 'JAVA' "
                    + "COMMENT 'JAVA = 参与解析统计；TEXT = 文本文件，只做检索'");
            log.info("结构补齐：source_file 已加 kind 列（已有的行按 JAVA 处理）");
        }
        // answer_log.source：2026-09-20 加（区分用户提问与评估跑题 —— 建表同一天就发现的口径问题：
        // 跑一次评估集就是 200+ 行流水，不标出来，"累计问答"会被它撑起来）
        if (!columnExists("answer_log", "source")) {
            jdbc.execute("ALTER TABLE `answer_log` ADD COLUMN "
                    + "`source` VARCHAR(8) NOT NULL DEFAULT 'USER' "
                    + "COMMENT 'USER = 用户提问；EVAL = 评估集/对比实验跑题（不计入用户问答统计）' "
                    + "AFTER `question_id`");
            log.info("结构补齐：answer_log 已加 source 列（已有的行按 USER 处理）");
        }
        // 注：这已经是第二条补列了 —— 类注释里说过"超过两三条就该换 Flyway"，下次再加列前先掂量一下。
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }
}
