package com.readcodeai.index;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 索引的装配：**事务模板**（Spring Boot 只自动配了事务管理器，没配 TransactionTemplate）。
 *
 * <p>为什么索引要事务：五段写入（文件/符号/调用边/类型关系/代码块）加"标 READY"要么全生效、
 * 要么全不生效 —— 崩在中间留下半份数据，是查询侧最容易踩的坑。
 * 顺带把"每条 INSERT 一次自动提交"变成"整段落一次盘"（实测落库占索引总耗时的 70%）。
 */
@Configuration
public class IndexConfig {

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
