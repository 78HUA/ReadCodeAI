package com.readcodeai.retrieve;

import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.retrieve.model.SymbolView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 1 步验证：四类确定性查询。
 *
 * <p><b>本测试不绑定任何具体项目</b>：基准符号是「被调用最多的方法」「实现类最多的接口」，
 * 由索引自己选出来。换测试仓库不需要改这个文件。
 *
 * <p><b>最重的断言不是「查得到」，而是「查出来的行号在磁盘上真的对得上」</b> ——
 * 连调用点的行号也要逐条回磁盘核对。这不是普通单元测试的写法，
 * 而是这个项目的核心主张：证据必须能被程序读文件核验。
 */
@SpringBootTest
class SymbolQueryTest {

    /** 抽查多少个基准符号、最多核对多少处调用点。 */
    private static final int FIXTURE_LIMIT = 5;
    private static final int MAX_CALL_SITES_CHECKED = 10;

    @Autowired
    private SymbolQueryService queryService;

    @Autowired
    private SymbolQueryRepository queryRepository;

    @Test
    void recordedSymbolPositionsMatchTheFilesOnDisk() throws IOException {
        RepoView repo = latestRepo();
        List<SymbolView> methods = queryRepository.mostCalledMethods(repo.id(), FIXTURE_LIMIT);
        assumeTrue(!methods.isEmpty(), "库里还没有可用的基准符号，跳过");

        for (SymbolView method : methods) {
            Path file = Path.of(repo.rootPath()).resolve(method.filePath());
            assertThat(file).as("索引记录的文件必须真实存在：%s", method.filePath()).exists();

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(method.endLine())
                    .as("%s 的结束行必须在文件范围内（文件共 %d 行）", method.qualifiedName(), lines.size())
                    .isLessThanOrEqualTo(lines.size());

            // 不变式是「记录的行区间内包含符号名」，而**不是**「起始行包含符号名」——
            // 带注解的方法，区间起点是注解行（如 @PostMapping），这是对的：
            // 注解本就是声明的一部分。第 8 步前端高亮证据时要注意这一点。
            List<String> range = lines.subList(method.startLine() - 1, method.endLine());
            assertThat(String.join("\n", range))
                    .as("%s 记录的行区间 %d-%d 内应出现符号名，实际区间内容：%s",
                            method.qualifiedName(), method.startLine(), method.endLine(),
                            range.isEmpty() ? "(空)" : range.get(0).strip())
                    .contains(method.name());

            System.out.printf("[符号定位核验] %s -> %s:%d-%d  区间首行：%s%n",
                    method.qualifiedName(), method.filePath(), method.startLine(), method.endLine(),
                    range.isEmpty() ? "(空)" : range.get(0).strip());
        }
    }

    @Test
    void recordedCallSiteLinesActuallyContainThatCallOnDisk() throws IOException {
        RepoView repo = latestRepo();
        List<SymbolView> methods = queryRepository.mostCalledMethods(repo.id(), FIXTURE_LIMIT);
        assumeTrue(!methods.isEmpty(), "库里还没有可用的基准符号，跳过");

        int checked = 0;
        for (SymbolView method : methods) {
            for (CallSiteView call : queryService.callers(method.id())) {
                Path file = Path.of(repo.rootPath()).resolve(call.callSiteFile());
                if (!Files.exists(file)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (call.callLine() < 1 || call.callLine() > lines.size()) {
                    continue;
                }
                String line = lines.get(call.callLine() - 1);
                assertThat(line)
                        .as("调用点行号对不上：%s 的第 %d 行应该是调用 %s，实际内容：%s",
                                call.callSiteFile(), call.callLine(), method.name(), line.strip())
                        .contains(method.name());
                checked++;
                if (checked >= MAX_CALL_SITES_CHECKED) {
                    break;
                }
            }
            if (checked >= MAX_CALL_SITES_CHECKED) {
                break;
            }
        }

        System.out.printf("%n[调用点核验] 逐条回磁盘核对了 %d 处调用点的行号与内容%n", checked);
        assertThat(checked).as("至少要核验到若干处调用点，否则这条验证等于没跑").isGreaterThan(0);
    }

    @Test
    void implementationsComeBackWithLocations() {
        RepoView repo = latestRepo();
        List<SymbolView> interfaces = queryRepository.mostImplementedInterfaces(repo.id(), FIXTURE_LIMIT);
        assumeTrue(!interfaces.isEmpty(), "库里没有带实现类的接口，跳过");

        for (SymbolView itf : interfaces) {
            List<SymbolView> implementations = queryService.implementations(itf.id());
            assertThat(implementations).as("%s 应该有实现类", itf.qualifiedName()).isNotEmpty();
            assertThat(implementations).allSatisfy(i -> {
                assertThat(i.filePath()).as("实现类必须带文件位置").isNotBlank();
                assertThat(i.qualifiedName()).isNotEqualTo(itf.qualifiedName());
            });
            System.out.printf("[实现类查询] %s -> %d 个实现类%n", itf.qualifiedName(), implementations.size());
        }
    }

    @Test
    void unresolvedCalleesKeepAReasonInsteadOfDisappearing() {
        RepoView repo = latestRepo();
        List<SymbolView> methods = queryRepository.mostCalledMethods(repo.id(), FIXTURE_LIMIT);
        assumeTrue(!methods.isEmpty(), "库里还没有可用的基准符号，跳过");

        boolean sawUnresolved = false;
        for (SymbolView method : methods) {
            for (CallSiteView callee : queryService.callees(method.id())) {
                assertThat(callee.calleeRaw()).as("未解析的调用必须保留原文").isNotBlank();
                if (!callee.resolved()) {
                    assertThat(callee.reason()).as("未解析必须给出原因").isNotBlank();
                    sawUnresolved = true;
                }
            }
        }
        assertThat(sawUnresolved)
                .as("这几处的调用边里应该存在未解析项（外部依赖不可避免），否则这条验证没意义")
                .isTrue();
    }

    @Test
    void unknownLookupsFailLoudlyInsteadOfReturningSomethingInvented() {
        assertThat(queryService.locate(null, "definitelyNotASymbolNameXyz123", 5)).isEmpty();
        assertThatThrownBy(() -> queryService.requireSymbol(999_999_999L))
                .isInstanceOf(NotFoundException.class);
    }

    /** 库里最近一次索引完成的仓库。 */
    private RepoView latestRepo() {
        List<RepoView> repos = queryService.repos();
        assumeTrue(!repos.isEmpty(), "还没有任何索引，跳过");
        return repos.get(0);
    }
}
