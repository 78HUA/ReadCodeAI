package com.readcodeai.retrieve;

import com.readcodeai.retrieve.model.ChunkHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文选片的纯逻辑测试（不连数据库、不连模型）。
 *
 * <p>这一层的价值在于**可解释**：哪些块被选中、哪些被丢、为什么丢。
 * 所以断言不只测"选了几个"，还测"丢弃原因记录得对不对" ——
 * 否则回答"为什么没找到"时就分不清是**检索没召回**还是**被预算挤掉了**。
 */
class ContextSelectorTest {

    private static final String Q = "谁调用了 submit 方法？";

    private final ContextSelector selector = new ContextSelector();

    @Test
    void promotesChunksWhoseSymbolNameExactlyMatchesAnIdentifierInTheQuestion() {
        ChunkHit unrelated = hit("src/A.java", 1, 10, "com.x.Other#doWork/0", 10, "other body");
        ChunkHit exact = hit("src/B.java", 20, 30, "com.x.Service#submit/1", 10, "submit body");

        // 检索顺序把不相关的排在前面，选片应该把精确同名的提到最前
        var selection = selector.select(Q, List.of(unrelated, exact), 10_000, 8, 3);

        assertThat(selection.chunks()).hasSize(2);
        assertThat(selection.chunks().get(0).symbolQualifiedName()).isEqualTo("com.x.Service#submit/1");
    }

    @Test
    void dropsDuplicatePositionsAndIdenticalContents() {
        ChunkHit first = hit("src/A.java", 1, 10, "com.x.A#m/0", 10, "same body");
        ChunkHit samePosition = hit("src/A.java", 1, 10, "com.x.A#m/0", 10, "same body");
        // 不同文件里一模一样的代码（复制粘贴 / 生成代码）—— 留一份就够
        ChunkHit sameContentElsewhere = hit("src/C.java", 50, 60, "com.x.C#m/0", 10, "same body");

        var selection = selector.select("m", List.of(first, samePosition, sameContentElsewhere), 10_000, 8, 3);

        assertThat(selection.chunks()).hasSize(1);
        assertThat(selection.droppedReasons()).hasSize(2);
        assertThat(selection.droppedReasons().toString()).contains("重复");
    }

    @Test
    void capsHowManyChunksOneFileMayContribute() {
        List<ChunkHit> hits = List.of(
                hit("src/A.java", 1, 10, null, 10, "a1"),
                hit("src/A.java", 20, 30, null, 10, "a2"),
                hit("src/A.java", 40, 50, null, 10, "a3"),
                hit("src/A.java", 60, 70, null, 10, "a4"),
                hit("src/B.java", 1, 10, null, 10, "b1"));

        var selection = selector.select("q", hits, 10_000, 8, 2);

        assertThat(selection.chunks()).hasSize(3);
        assertThat(selection.chunks().stream().filter(c -> c.filePath().equals("src/A.java")))
                .as("单个文件最多 2 个块 —— 否则热门文件会霸占整个上下文")
                .hasSize(2);
        assertThat(selection.chunks().stream().anyMatch(c -> c.filePath().equals("src/B.java")))
                .as("被挤出来的预算应该留给别的文件")
                .isTrue();
        assertThat(selection.droppedReasons().toString()).contains("同文件块数");
    }

    @Test
    void skipsOversizedChunksBecauseTheyCrowdOutEveryOtherLead() {
        ChunkHit first = hit("src/A.java", 1, 10, null, 100, "small");
        ChunkHit huge = hit("src/B.java", 1, 5000, null, 9_000, "huge body");

        var selection = selector.select("q", List.of(first, huge), 10_000, 8, 3);

        assertThat(selection.chunks()).as("超大块跳过，不挤掉别的线索").hasSize(1);
        assertThat(selection.droppedReasons().toString()).contains("单块过大");
    }

    @Test
    void stopsAtTheTokenBudgetAndSaysWhyEachRemainingChunkWasDropped() {
        List<ChunkHit> hits = List.of(
                hit("src/A.java", 1, 10, null, 100, "a"),
                hit("src/B.java", 1, 10, null, 100, "b"),
                hit("src/C.java", 1, 10, null, 100, "c"));

        var selection = selector.select("q", hits, 250, 8, 3);

        assertThat(selection.chunks()).hasSize(2);
        assertThat(selection.totalTokens()).isEqualTo(200);
        assertThat(selection.droppedReasons()).anySatisfy(reason ->
                assertThat(reason).contains("超出 token 预算"));
    }

    @Test
    void stillKeepsOneChunkWhenEvenItExceedsTheBudget() {
        // 宁可超一点也要给出一个候选 —— 一个都不给等于直接拒答，那是更差的失败方式
        var selection = selector.select("q", List.of(hit("src/A.java", 1, 10, null, 500, "big")), 100, 8, 3);
        assertThat(selection.chunks()).hasSize(1);
    }

    @Test
    void extractsSimpleNamesFromBothTypeAndMethodKeys() {
        assertThat(ContextSelector.simpleNameOf("com.x.Service#submit/1")).isEqualTo("submit");
        assertThat(ContextSelector.simpleNameOf("com.x.Service")).isEqualTo("Service");
        assertThat(ContextSelector.simpleNameOf(null)).isNull();
    }

    @Test
    void returnsEmptySelectionForEmptyInput() {
        var selection = selector.select("q", List.of(), 10_000, 8, 3);
        assertThat(selection.isEmpty()).isTrue();
        assertThat(selection.totalTokens()).isZero();
    }

    private static ChunkHit hit(String file, int start, int end, String symbol, int tokens, String content) {
        return new ChunkHit(1L, "SYMBOL", file, start, end, null, symbol, tokens, 1.0, content);
    }
}
