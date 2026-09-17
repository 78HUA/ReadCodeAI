package com.readcodeai.retrieve;

import com.readcodeai.retrieve.model.CallSiteView;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 第 1 步验证：四类确定性查询。
 *
 * <p><b>最重要的一条断言不是「查得到」，而是「查出来的行号在磁盘上真的对得上」</b> ——
 * 这不是普通单元测试的写法，而是这个项目的核心主张：证据必须能被程序读文件核验。
 * 如果库里记的行号和磁盘内容脱节，整个项目的可信度就不成立。
 *
 * <p>依赖云舒NAS 的索引是库里最近一次完成的索引（集成测试对活库的固有耦合）。
 */
@SpringBootTest
class SymbolQueryTest {

    @Autowired
    private SymbolQueryService queryService;

    @Test
    void locateReturnsAPositionThatMatchesTheFileOnDisk() throws IOException {
        List<SymbolView> found = queryService.locate(null, "NasRedisConfig#getRedisTemplate/0", 5);
        assumeTrue(!found.isEmpty(), "库里还没有索引，跳过");
        SymbolView symbol = found.get(0);

        assertThat(symbol.kind()).isEqualTo("METHOD");
        assertThat(symbol.filePath()).endsWith("NasRedisConfig.java");
        assertThat(symbol.qualifiedName())
                .isEqualTo("top.itning.yunshunas.common.config.NasRedisConfig#getRedisTemplate/0");

        // 核心验证：库里记的起止行，去磁盘上读出来核对
        String rootPath = queryService.repos().get(0).rootPath();
        Path file = Path.of(rootPath).resolve(symbol.filePath());
        assertThat(file).as("索引里记录的文件必须真实存在").exists();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(symbol.endLine()).as("行号必须在文件范围内").isLessThanOrEqualTo(lines.size());

        String firstLine = lines.get(symbol.startLine() - 1);
        assertThat(firstLine)
                .as("库里记的第 %d 行应该就是该方法声明所在行：%s", symbol.startLine(), firstLine)
                .contains("getRedisTemplate");

        System.out.printf("%n[证据核验] %s -> %s%n  磁盘第 %d 行：%s%n",
                symbol.qualifiedName(), symbol.location(), symbol.startLine(), firstLine.strip());
    }

    @Test
    void callersFindsEveryCallSiteOfAnInRepoMethod() {
        SymbolView enabled = firstOrSkip(queryService.locate(null, "NasRedisConfig#enabled/0", 5));
        List<CallSiteView> callers = queryService.callers(enabled.id());

        System.out.printf("%n[谁调用了它] %s（共 %d 处）%n", enabled.qualifiedName(), callers.size());
        callers.forEach(c -> System.out.printf("   %s  (%s)%n", c.callSiteLocation(), c.symbolQualifiedName()));

        // 基准来自索引实测：这两处是 known 的调用点
        assertThat(callers)
                .as("应包含已核实的两个调用点")
                .anySatisfy(c -> assertThat(c.callSiteLocation())
                        .isEqualTo("nas-common/src/main/java/top/itning/yunshunas/common/config/ConfigBroadcaster.java:65"))
                .anySatisfy(c -> assertThat(c.callSiteLocation())
                        .isEqualTo("nas-common/src/main/java/top/itning/yunshunas/common/lock/RedisDistributedLock.java:67"));
        assertThat(callers).allSatisfy(c -> assertThat(c.symbolFilePath()).isNotBlank());
    }

    @Test
    void calleesReportsUnresolvedCallsWithAReasonInsteadOfDroppingThem() {
        SymbolView onLocalChange = firstOrSkip(queryService.locate(null, "ConfigBroadcaster#onLocalChange/1", 5));
        List<CallSiteView> callees = queryService.callees(onLocalChange.id());

        System.out.printf("%n[我调用了谁] %s（共 %d 条边）%n", onLocalChange.qualifiedName(), callees.size());
        callees.forEach(c -> System.out.printf("   %s  ->  %s  %s%n", c.callSiteLocation(), c.calleeRaw(),
                c.resolved() ? "[已解析]" : "[" + c.reason() + "]"));

        assertThat(callees).as("该方法体内有多个调用").hasSizeGreaterThanOrEqualTo(3);
        assertThat(callees).allSatisfy(c -> {
            assertThat(c.calleeRaw()).as("未解析的调用必须保留原文").isNotBlank();
            assertThat(c.callSiteFile()).as("调用点必须有文件").isNotBlank();
            if (!c.resolved()) {
                assertThat(c.reason()).as("未解析必须给出原因").isNotBlank();
            }
        });
        assertThat(callees).anySatisfy(c -> assertThat(c.resolved()).isTrue());
    }

    @Test
    void implementationsListsDirectImplementorsOfAnInterface() {
        SymbolView musicDataSource = firstOrSkip(queryService.locate(null,
                "top.itning.yunshunas.music.datasource.MusicDataSource", 5));
        List<SymbolView> implementations = queryService.implementations(musicDataSource.id());

        System.out.printf("%n[有哪些实现] %s（%d 个实现类）%n", musicDataSource.qualifiedName(), implementations.size());
        implementations.forEach(i -> System.out.printf("   %s  %s%n", i.qualifiedName(), i.location()));

        assertThat(musicDataSource.kind()).isEqualTo("INTERFACE");
        assertThat(implementations).as("基准事实：该接口有 3 个实现类").hasSize(3);
        assertThat(implementations).allSatisfy(i -> {
            assertThat(i.filePath()).isNotBlank();
            assertThat(i.qualifiedName()).isNotEqualTo(musicDataSource.qualifiedName());
        });
    }

    @Test
    void locatingSomethingThatDoesNotExistReturnsEmptyRatherThanInventingSomething() {
        List<SymbolView> found = queryService.locate(null, "definitelyNotASymbolNameXyz", 5);
        assertThat(found).isEmpty();
    }

    private static SymbolView firstOrSkip(List<SymbolView> found) {
        assumeTrue(!found.isEmpty(), "库里还没有索引，跳过");
        return found.get(0);
    }
}
