package com.readcodeai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环检测的单测：**同一次查询的几种写法必须被判成同一条边**。
 *
 * <p>这一步看着琐碎，但它决定环检测有没有用：模型只要换个大小写、加个括号、
 * 写成 {@code find_callers}，就能绕过检测继续绕圈 —— 实践里模型真的会这么写。
 */
class VisitedEdgeSetTest {

    @Test
    void treatsCaseSpacingAndParensAsTheSameEdge() {
        VisitedEdgeSet visited = new VisitedEdgeSet();
        assertThat(visited.firstVisit("findCallers", "R.success")).isTrue();
        assertThat(visited.firstVisit("findCallers", "R.success()")).as("结尾的括号不影响判断").isFalse();
        assertThat(visited.firstVisit("FIND_CALLERS", "r.success")).as("大小写与下划线不影响判断").isFalse();
        assertThat(visited.firstVisit("findCallers", " R.success ")).as("空白不影响判断").isFalse();
        assertThat(visited.firstVisit("findCallers", "R.error")).as("换了参数就是另一条边").isTrue();
        assertThat(visited.firstVisit("readSymbol", "R.success")).as("换了工具就是另一条边").isTrue();
        assertThat(visited.size()).isEqualTo(3);
    }

    @Test
    void normalizesEmptyAndNullArguments() {
        assertThat(VisitedEdgeSet.normalize(null)).isEmpty();
        assertThat(VisitedEdgeSet.normalize("  ")).isEmpty();
        assertThat(VisitedEdgeSet.normalize("A.B()")).isEqualTo("a.b");
    }

    @Test
    void listsWhatHasBeenVisitedWithALimit() {
        VisitedEdgeSet visited = new VisitedEdgeSet();
        for (int i = 0; i < 15; i++) {
            visited.firstVisit("textSearch", "query" + i);
        }
        assertThat(visited.labels(12)).hasSize(12);
        assertThat(visited.size()).isEqualTo(15);
    }
}
