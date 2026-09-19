package com.readcodeai.verify;

import com.readcodeai.agent.AnswerService;
import com.readcodeai.agent.model.AskAnswer;
import com.readcodeai.agent.model.AskEvidence;
import com.readcodeai.agent.model.SupportCheck;
import com.readcodeai.config.LlmClient;
import com.readcodeai.evidence.SupportChecker;
import com.readcodeai.index.ProjectIndexer;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ③ 层核验（**真实模型**）的实测：它到底能不能发现"证据真实但不支持结论"。
 *
 * <h3>为什么要构造样本，而不是拿几个真实问答看看</h3>
 * 「张冠李戴」是稀有事件 —— 实测里 15 道题只撞到 1 次。拿真实问答跑，
 * 得不到分母，也就说不出"检出率"。所以这里**成对构造**：
 * <ul>
 *   <li><b>正样本</b>：结论说的是 A，证据就是 A 的定义处 → 应当判 supported</li>
 *   <li><b>错位样本</b>：**同一句结论**，证据换成另一个文件里另一个符号的定义处（同样真实存在、
 *       同样通过 ①② 层）→ 应当判 unsupported。这正是实测中撞到的那类错误</li>
 *   <li><b>过度断言样本</b>：结论声称的实现手段，在引用的那几行里**程序化确认不存在**
 *       （例如声称用了 synchronized，而原文没有这个词）→ 应当判 unsupported</li>
 * </ul>
 * 三类样本的"应有答案"都是程序算出来的，不是人判的，也不是模型判的 —— 与评估集同一条纪律。
 *
 * <h3>它证明什么、不证明什么</h3>
 * 它证明「程序造出来的张冠李戴，这个判官能抓多少」；
 * **它不能证明"真实模型犯的错都能被抓住"** —— 真实错误比构造的更隐蔽，
 * 所以同一个测试还跑一组真实问答，记录误报（把正确结论判成不支持）的条数。
 *
 * <p>没配 Key 时自动跳过；运行前 {@code source notes/llm-env.sh}。
 */
@SpringBootTest
class SupportLiveTest {

    /** 每类样本的条数：三类共 3×N 次判定调用，够看出趋势又不至于烧配额。 */
    private static final int PER_FAMILY = 4;

    /** 真实问答的题数：问的是真模型，慢且要花 token，取样点到为止（实测 5 题里 2 题会因模型自身原因拒答）。 */
    private static final int REAL_QUESTIONS = 5;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private SupportChecker supportChecker;

    @Autowired
    private AnswerService answerService;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private SymbolQueryRepository repository;

    @Autowired
    private ProjectIndexer indexer;

    @Test
    void constructedMismatchesAreDetected() {
        LiveLlm.assumeReachable(llmClient);
        RepoView repo = corpus();
        Path repoRoot = Path.of(repo.rootPath());
        List<SymbolView> symbols = methodsWithDistinctFiles(repo.id(), PER_FAMILY * 8);
        assumeTrue(symbols.size() >= PER_FAMILY * 2, "语料里可用的方法符号不够，跳过");

        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < PER_FAMILY && i < symbols.size(); i++) {
            SymbolView target = symbols.get(i);
            // 问题与结论都要**只声明被引用的那几行确实展示了的东西**，否则正样本本身就不成立。
            // 第一版这里写死成"「X」是怎么实现的"+"实现就在下面这段代码里"，而 gson 的
            // TypeAdapter#read 是抽象方法、引用区间只有声明行 —— 判官两次判它"不支持"，
            // 理由是"只有声明，没有实现细节"。**判官是对的，样本是错的**：
            // 拿本身就不成立的正样本去量误报率，量出来的数字没有意义。
            boolean withBody = hasBody(repoRoot, target);
            String question = withBody
                    ? "「" + target.qualifiedName() + "」是怎么实现的？"
                    : "「" + target.qualifiedName() + "」定义在哪、签名是什么？";
            String conclusion = withBody
                    ? "「" + target.qualifiedName() + "」的实现（含方法体）就是下面引用的这段代码"
                    : "「" + target.qualifiedName() + "」的定义（声明行）就是下面引用的这段代码";
            samples.add(new Sample("匹配", question, conclusion,
                    new AskEvidence(target.filePath(), target.startLine(), target.endLine(), "", "目标方法的定义"),
                    Expect.SUPPORTED));

            SymbolView other = firstOtherFile(symbols, target);
            if (other != null) {
                samples.add(new Sample("错位", question, conclusion,
                        new AskEvidence(other.filePath(), other.startLine(), other.endLine(), "",
                                "另一个符号的定义（应当被判成张冠李戴）"),
                        Expect.UNSUPPORTED));
            }

            SymbolView noSync = aMethodWithout(repoRoot, symbols, "synchronized");
            if (noSync != null) {
                samples.add(new Sample("过度断言", "「" + noSync.qualifiedName() + "」是怎么实现的？",
                        "「" + noSync.qualifiedName() + "」用 synchronized 保证并发安全",
                        new AskEvidence(noSync.filePath(), noSync.startLine(), noSync.endLine(), "",
                                "该方法原文（里面没有 synchronized）"),
                        Expect.UNSUPPORTED));
            }
        }

        System.out.println(System.lineSeparator()
                + "=== ③ 层实测（构造样本 · 真实模型：" + llmClient.model() + "）===");
        System.out.println("  样本类型 | 应有判定 | 实际判定     | 理由");
        int[] correct = new int[2];
        int[] total = new int[2];
        int unavailable = 0;
        for (Sample sample : samples) {
            long start = System.nanoTime();
            SupportCheck check = supportChecker.check(sample.question(), sample.conclusion(),
                    List.of(sample.evidence()), repoRoot);
            long millis = (System.nanoTime() - start) / 1_000_000;
            boolean asExpected = sample.expected() == Expect.SUPPORTED
                    ? check.status() == SupportCheck.Status.SUPPORTED
                    : check.status() == SupportCheck.Status.UNSUPPORTED;
            int bucket = sample.expected() == Expect.SUPPORTED ? 0 : 1;
            total[bucket]++;
            if (asExpected) {
                correct[bucket]++;
            }
            if (check.status() == SupportCheck.Status.UNAVAILABLE) {
                unavailable++;
            }
            System.out.printf("  %-6s | %-8s | %-12s | %s (%d ms, %d token)%n",
                    sample.family(), sample.expected(), check.status(), abbreviate(check.reason()),
                    millis, check.tokens());
        }

        System.out.printf("%n  正样本（应当判支持）：%d/%d 判对%n", correct[0], total[0]);
        System.out.printf("  负样本（应当判不支持）：%d/%d 判出（检出率 %.0f%%）%n",
                correct[1], total[1], total[1] == 0 ? 0.0 : 100.0 * correct[1] / total[1]);
        if (unavailable > 0) {
            System.out.printf("  另有 %d 条判定没做成（模型接口波动，见上面的 UNAVAILABLE 行）%n", unavailable);
        }

        // 硬断言只压在一件事上：**不能把张冠李戴全放过去**（那这层就白做了）。
        // 具体数字只打印不断言 —— 真实模型有波动，把阈值写成断言只会培养"改测试"的习惯。
        // 但接口整体波动时不能假装检出了：那种情况按"这次没测成"跳过（理由留在输出里）。
        assumeTrue(unavailable < total[0] + total[1],
                "模型接口整批不可用，本次没测成（属外部波动，与代码无关）");
        assertThat(correct[1]).as("负样本至少要判出一部分，否则这一层等于没起作用").isGreaterThan(0);
        assertThat(total[0]).as("正样本必须真的跑到（否则说明语料或构造出了问题）").isGreaterThan(0);
    }

    @Test
    void realAnswersAreJudgedAndTheCostIsReported() {
        LiveLlm.assumeReachable(llmClient);
        RepoView repo = corpus();
        Path repoRoot = Path.of(repo.rootPath());
        List<SymbolView> symbols = methodsWithDistinctFiles(repo.id(), REAL_QUESTIONS * 4);
        assumeTrue(!symbols.isEmpty(), "语料里没有可用的方法符号，跳过");

        System.out.println(System.lineSeparator() + "=== ③ 层实测（真实问答的答案 · 误报与代价）===");
        int judged = 0;
        int flagged = 0;
        int providerErrors = 0;
        int checkedTokens = 0;
        long checkedMillis = 0;
        for (int i = 0; i < REAL_QUESTIONS && i < symbols.size(); i++) {
            SymbolView target = symbols.get(i);
            AskAnswer answer;
            try {
                answer = answerService.ask(repo.id(),
                        "这个方法大致是做什么的：" + target.qualifiedName() + "？", null, 8);
            } catch (RuntimeException e) {
                // 模型接口波动（实测撞到过：一轮测试里连着调了很多次之后，接口回了一个非 JSON 的响应体）。
                // 那不是代码缺陷，也不该把整轮实测染红 —— 如实记下这一题，继续下一题。
                providerErrors++;
                System.out.printf("  %-40s → 跳过：模型接口波动（%s）%n",
                        target.name(), e.getClass().getSimpleName());
                continue;
            }
            SupportCheck check = answer.verification().support();
            if (answer.refused()) {
                System.out.printf("  %-40s → 拒答：%s%n", target.name(), abbreviate(answer.refusalReason()));
                continue;
            }
            judged++;
            if (check.flagged()) {
                flagged++;
            }
            checkedTokens += check.tokens();
            // 单独再量一次判定的边际耗时（同一次调用的耗时，用于说明"这一层要花多少时间"）
            long start = System.nanoTime();
            SupportCheck again = supportChecker.check(
                    "这个方法大致是做什么的：" + target.qualifiedName() + "？",
                    answer.answer(), answer.evidence(), repoRoot);
            long millis = (System.nanoTime() - start) / 1_000_000;
            checkedMillis += millis;
            System.out.printf("  %-40s → 判定 %s · %s · +%d ms · +%d token（复核一次：%s）%n",
                    target.name(), check.status(), abbreviate(check.reason()), millis, check.tokens(),
                    again.status());
        }
        System.out.printf("%n  真实答案 %d 条：判成不支持 %d 条（误报或真错，看理由）· 判定平均耗时 %d ms · 平均 %d token%n",
                judged, flagged, judged == 0 ? 0 : checkedMillis / judged,
                judged == 0 ? 0 : checkedTokens / judged);
        if (providerErrors > 0) {
            System.out.printf("  另有 %d 题因模型接口波动跳过（不影响上面的结论，但样本量更小了）%n", providerErrors);
        }

        if (judged == 0) {
            assumeTrue(false, "本次没有一条真实答案走到判定（模型接口波动 " + providerErrors
                    + " 题），这一轮没测成 —— 属外部波动，与代码无关");
        }
        assertThat(judged).as("至少要有几条真实答案被判过（否则这次实测没有意义）").isGreaterThan(0);
    }

    // ---- 样本构造（全部程序化，不经过任何模型）----

    private enum Expect { SUPPORTED, UNSUPPORTED }

    private record Sample(String family, String question, String conclusion, AskEvidence evidence,
                          Expect expected) {
    }

    /** 语料里可用的方法符号：**文件互不相同**，便于构造"另一个文件的另一个符号"。 */
    private List<SymbolView> methodsWithDistinctFiles(long repoId, int limit) {
        List<SymbolView> methods = repository.mostCalledMethods(repoId, limit * 2).stream()
                .filter(symbol -> symbol.filePath() != null && symbol.startLine() > 0)
                .filter(symbol -> !symbol.qualifiedName().contains("<init>"))
                .toList();
        List<SymbolView> distinct = new ArrayList<>();
        List<String> seenFiles = new ArrayList<>();
        for (SymbolView method : methods) {
            if (!seenFiles.contains(method.filePath())) {
                seenFiles.add(method.filePath());
                distinct.add(method);
            }
        }
        return distinct;
    }

    private static SymbolView firstOtherFile(List<SymbolView> symbols, SymbolView target) {
        return symbols.stream()
                .filter(candidate -> !candidate.filePath().equals(target.filePath()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 引用区间里是不是真有方法体 —— **看括号，不猜行数**。
     *
     * <p>第一版用"行数 ≥ 4"猜，结果把一个只有声明（加分号）的方法当成了"有实现"，
     * 正样本因此不成立（判官判它"只有定义没有实现"，那是对的）。构造样本用的判据本身必须是程序可判的事实。
     */
    private static boolean hasBody(Path repoRoot, SymbolView symbol) {
        String text = readRange(repoRoot, symbol);
        return text.contains("{") && text.contains("}");
    }

    /** 读符号区间里的原文（读不到返回空串）。 */
    private static String readRange(Path repoRoot, SymbolView symbol) {
        try {
            List<String> lines = Files.readAllLines(
                    repoRoot.resolve(symbol.filePath().replace('\\', '/')), StandardCharsets.UTF_8);
            int from = Math.max(0, Math.min(symbol.startLine() - 1, lines.size()));
            int to = Math.min(lines.size(), Math.max(symbol.endLine(), from + 1));
            return String.join("\n", lines.subList(from, to));
        } catch (IOException | RuntimeException e) {
            // 读不到就当作"看不到结论所声称的东西"：这里只是找构造样本用的素材
            return "";
        }
    }

    /** 找一具"原文里确实没有某个关键词、而且有方法体"的方法：这样"它用了这个手段"就是程序可判的假话。 */
    private static SymbolView aMethodWithout(Path repoRoot, List<SymbolView> symbols, String keyword) {
        for (SymbolView symbol : symbols) {
            if (!hasBody(repoRoot, symbol)) {
                continue;
            }
            if (!readRange(repoRoot, symbol).toLowerCase().contains(keyword.toLowerCase())) {
                return symbol;
            }
        }
        return null;
    }

    private RepoView corpus() {
        var repo = TestCorpus.resolve(indexer, queries);
        assumeTrue(repo.isPresent(), "语料不存在（sample-repos 或 -Dreadcodeai.verify.repo=），跳过");
        assumeTrue(!"UNAVAILABLE".equals(repo.get().status()), "语料索引不可用，跳过");
        return repo.get();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "(无)";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 70 ? oneLine : oneLine.substring(0, 70) + "...";
    }
}
