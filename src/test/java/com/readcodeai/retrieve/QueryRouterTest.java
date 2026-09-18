package com.readcodeai.retrieve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由的纯逻辑测试（不连数据库、不连模型）。
 *
 * <p>路由错了的代价是「用错检索层」，所以它必须可解释、可测 ——
 * 这也是为什么路由用词汇模式而不是语义理解：模式能写出断言，语义判断只能靠感觉。
 */
class QueryRouterTest {

    private final QueryRouter router = new QueryRouter();

    @Test
    void routesChineseQuestionsAboutCallRelations() {
        assertThat(router.detectRoute("谁调用了 AddressBookService 的 deleteAddressBook 方法？"))
                .isEqualTo(QueryRouter.Route.CALLERS);
        assertThat(router.detectRoute("deleteAddressBook 被谁调用？"))
                .isEqualTo(QueryRouter.Route.CALLERS);
        assertThat(router.detectRoute("AddressBookService 调用了哪些方法？"))
                .isEqualTo(QueryRouter.Route.CALLEES);
    }

    @Test
    void routesQuestionsAboutImplementations() {
        assertThat(router.detectRoute("AddressBookService 有哪些实现类？"))
                .isEqualTo(QueryRouter.Route.IMPLEMENTATIONS);
        assertThat(router.detectRoute("谁实现了 LoginCheckFilter？"))
                .isEqualTo(QueryRouter.Route.IMPLEMENTATIONS);
    }

    @Test
    void routesQuestionsAboutLocation() {
        assertThat(router.detectRoute("deleteAddressBook 定义在哪？"))
                .isEqualTo(QueryRouter.Route.LOCATE);
        assertThat(router.detectRoute("LoginCheckFilter 在哪个类里？"))
                .isEqualTo(QueryRouter.Route.LOCATE);
    }

    @Test
    void sendsFuzzyAndDescriptiveQuestionsToSemanticSearch() {
        // 这几句都不该被当成「确定性」问题 —— 它们问的是行为，不是符号
        assertThat(router.detectRoute("登录检查是在哪里做的？"))
                .isEqualTo(QueryRouter.Route.SEMANTIC);
        assertThat(router.detectRoute("这个项目的订单状态是怎么流转的？"))
                .isEqualTo(QueryRouter.Route.SEMANTIC);
        assertThat(router.detectRoute("分页查询是怎么实现的？"))
                .isEqualTo(QueryRouter.Route.SEMANTIC);
    }

    @Test
    void extractsIdentifierCandidatesAndDropsStopWords() {
        assertThat(QueryRouter.identifierCandidates("who calls the submit method"))
                .containsExactly("submit");

        // 单字符类名也要能提出来 —— 实测踩过：reggie 的核心类就叫 R，
        // 卡长度下限会让它直接掉进语义检索
        assertThat(QueryRouter.identifierCandidates("R 有哪些成员？"))
                .containsExactly("R");
        // 但单双字符的英文虚词要挡住，否则每个问句都会多几个候选。
        // 注："type" 不在停用词里 —— 它可能真的是个类名，留着无害（最终能不能用取决于符号表里有没有）
        assertThat(QueryRouter.identifierCandidates("what is a R type"))
                .containsExactly("R", "type");
    }

    @Test
    void fallsBackToSemanticWhenNoTargetSymbolExists() {
        // 问法像「谁调用了 X」，但 X 在仓库里不存在 —— 确定性查询无从下手，退回语义检索
        QueryRouter.Routed routed = router.route(1L, "谁调用了 NoSuchThingXyz 方法？", name -> List.of());

        assertThat(routed.route()).isEqualTo(QueryRouter.Route.SEMANTIC);
        assertThat(routed.isDeterministic()).isFalse();
    }
}
