package com.readcodeai.agent;

import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.verify.TestCorpus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 2 步验收的**清单生成器**：跑 10 个真实问题，把「结论 + 证据 + 用量」导出成 markdown，
 * 交给项目作者逐条判定对错（工具的准确率不能由工具自己下结论）。
 *
 * <p>输出路径：{@code -Dreadcodeai.qa.report=<路径>}，默认 {@code notes/qa-sampling.md}（在 .gitignore 内）。
 *
 * <p>题目是刻意配比的：**3 个确定性问题**（该走调用图/符号表，不该叫模型）、
 * **6 个模糊语义问题**（该走检索 + 模型）、**1 个仓库里根本不存在的功能**（该拒答）。
 * 只考答得对不对，看不出「该拒答时有没有拒答」。
 */
@SpringBootTest
class QaSweepTest {

    /** 配比：3 确定性 + 6 语义 + 1 应当拒答。 */
    private static final List<String> DEFAULT_QUESTIONS = List.of(
            "deleteAddressBook 定义在哪？",
            "谁调用了 AddressBookService 的 deleteAddressBook 方法？",
            "AddressBookService 有哪些实现类？",
            "登录检查是在哪里做的？",
            "菜品分页查询是怎么实现的？",
            "新增分类的接口在哪个类里？",
            "员工登录时密码是怎么校验的？",
            "哪里做了图片上传？",
            "这个项目的订单状态是怎么流转的？",
            "这个项目是怎么对接支付宝支付的？");

    /**
     * 问题集可覆盖：{@code -Dreadcodeai.qa.questions="问题1;问题2"}（用分号分隔）。
     * 换语料时用得上 —— 默认那 10 题是针对本地教学语料写的。
     * ⚠️ 命令行传中文会被转成 GBK，覆盖时**请用 ASCII 提问**。
     */
    private static List<String> questions() {
        String override = System.getProperty("readcodeai.qa.questions");
        if (override == null || override.isBlank()) {
            return DEFAULT_QUESTIONS;
        }
        return java.util.Arrays.stream(override.split(";"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Autowired
    private AnswerService answerService;

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queryService;

    @Autowired
    private com.readcodeai.config.LlmClient llmClient;

    @Test
    void sweepsRealQuestionsAndWritesAWorksheet() throws IOException {
        // 显式锁定语料 —— 不能用「最近索引的仓库」，那个全局状态会被别的测试（比如远程拉取）改变
        // **没配模型就跳过**：这一轮抽查问的是语义问题，缺 Key 时会抛「未配置 LLM」，
        // 而下面把异常记成"调用失败"并断言为 0 —— 不加这道门禁，CI（没有 Key）就会红。
        // 判据用的是"能不能用"，不是"某个环境的变量名"：CI、本地、换机器行为一致。
        com.readcodeai.verify.LiveLlm.assumeReachable(llmClient);
        var corpus = TestCorpus.resolve(indexer, queryService);
        org.junit.jupiter.api.Assumptions.assumeTrue(corpus.isPresent(),
                "语料 " + TestCorpus.SAMPLE + " 不存在，跳过");
        long repoId = corpus.get().id();
        Path report = Path.of(System.getProperty("readcodeai.qa.report", "notes/qa-sampling.md"));
        List<String> lines = new ArrayList<>();
        lines.add("# 问答效果抽查清单（第 2 步验收）");
        lines.add("");
        lines.add("**语料**：" + corpus.get().name() + "（" + corpus.get().rootPath()
                + "） · **模型**：智谱 glm-4-flash（免费档）");
        lines.add("");
        lines.add("**语料**：reggie 外卖（中性公开代码） · **模型**：智谱 glm-4-flash（免费档）");
        lines.add("");
        lines.add("> **怎么用**：逐条看「结论」和「证据」，在每节末尾的**你的判断**里填 对 / 错 / 部分对。");
        lines.add("> 判错的还要标一下错在哪：**检索没找到** / **模型理解错** / **证据不对**。");
        lines.add(">");
        lines.add("> 配比是刻意的：前 3 题是**确定性问题**（该由调用图和符号表直接算，不该叫模型），");
        lines.add("> 中间 6 题是**模糊语义问题**（该走检索 + 模型），最后 1 题**仓库里根本没有这个功能**（该拒答）。");
        lines.add("> 只考「答得对不对」看不出「该拒答时有没有拒答」。");
        lines.add("");

        int answered = 0;
        int refused = 0;
        int failed = 0;

        List<String> questions = questions();
        for (int i = 0; i < questions.size(); i++) {
            String question = questions.get(i);
            lines.add("---");
            lines.add("");
            lines.add("## Q" + (i + 1) + ". " + question);
            lines.add("");
            try {
                AskAnswer answer = answerService.ask(repoId, question, null, 8);
                if (answer.refused()) {
                    refused++;
                    lines.add("- **结果**：拒答 —— " + answer.refusalReason());
                } else {
                    answered++;
                    lines.add("- **答案来源**：" + switch (answer.answeredBy()) {
                        case STATIC -> "**调用图/符号表直接算出（没经过模型）**";
                        case LLM -> "模型组织（检索 " + answer.chunksUsed() + " 段喂给它）";
                        case NONE -> "无";
                    });
                    lines.add("- **结论**：" + answer.answer());
                    lines.add("- **证据**：");
                    for (AskEvidence evidence : answer.evidence()) {
                        lines.add("  - `" + evidence.location() + "` —— " + evidence.why());
                    }
                }
                lines.add(String.format("- **用量**：检索 %d 段 → 采用 %d 段 · prompt=%d · completion=%d · %d ms",
                        answer.chunksRetrieved(), answer.chunksUsed(),
                        answer.promptTokens(), answer.completionTokens(), answer.latencyMs()));
            } catch (RuntimeException e) {
                failed++;
                lines.add("- **结果**：调用失败 —— " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            lines.add("");
            lines.add("- **你的判断**：____（对 / 错 / 部分对）");
            lines.add("- **若错，错在哪**：____（检索没找到 / 模型理解错 / 证据不对）");
            lines.add("");
        }

        lines.add("---");
        lines.add("");
        lines.add("## 汇总");
        lines.add("");
        lines.add("| 项 | 数量 |");
        lines.add("|---|---|");
        lines.add("| 给出答案 | " + answered + " |");
        lines.add("| 拒答 | " + refused + " |");
        lines.add("| 调用失败 | " + failed + " |");
        lines.add("");

        Files.createDirectories(report.getParent());
        Files.write(report, String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8));
        System.out.printf("%n[问答抽查] %d 个问题跑完：给出答案 %d · 拒答 %d · 失败 %d%n  清单已写入 %s%n",
                questions.size(), answered, refused, failed, report.toAbsolutePath());

        // 作为测试，它只保证「全部题目都能跑完」；答得对不对由人来判
        assertThat(failed).as("有题目跑失败了，说明还有未处理的异常路径").isZero();
    }
}
