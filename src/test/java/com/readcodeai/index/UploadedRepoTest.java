package com.readcodeai.index;

import com.readcodeai.retrieve.FileContentService;
import com.readcodeai.retrieve.NotFoundException;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传压缩包 → 索引 → 点开文件核对 → 删除索引：**前端 ①④ 两个环节的后端全流程**。
 *
 * <p>三件必须钉死的事：
 * <ol>
 *   <li><b>剥不剥最外层目录要看压缩包本身</b>：剥错了会把 {@code src} 当成包装目录扔掉，
 *       用户只会看到"索引出 0 个文件"而不知道哪里错了</li>
 *   <li><b>点开证据看到的是磁盘当前内容</b>，并且能告知"索引之后这个文件被改过"</li>
 *   <li><b>路径不许越出仓库</b>：那条路径来自前端，属于不可信输入</li>
 * </ol>
 *
 * <p>工作区改到 {@code target/test-workspace}：测试不该往用户目录里的仓库工作区写东西。
 */
@SpringBootTest(properties = "readcodeai.index.workspace=target/test-workspace")
class UploadedRepoTest {

    @Autowired
    private ProjectIndexer indexer;

    @Autowired
    private SymbolQueryService queries;

    @Autowired
    private FileContentService fileContentService;

    @Test
    void indexesAnUploadedArchiveThenReadsAndDeletesIt() throws IOException {
        byte[] archive = zipOf("demo-src/main/java/demo/Hello.java", """
                package demo;

                public class Hello {
                    public String greet(String name) {
                        return "hi " + name;
                    }
                }
                """);

        // ① 上传（只给字节流与文件名，控制器之外没有任何文件系统操作）
        IndexSummary summary = indexer.indexArchive(
                new ByteArrayInputStream(archive), "demo-src.zip");

        System.out.printf("%n[上传索引] %s · 文件 %d · 符号 %d · 解析成功 %d%n",
                summary.name(), summary.fileCount(), summary.symbolCount(), summary.parsedOkCount());

        assertThat(summary.name()).as("仓库名取自压缩包名（去掉 .zip）").isEqualTo("demo-src");
        assertThat(summary.fileCount()).as("单层目录被剥掉后仍能找到 Java 文件").isEqualTo(1);
        assertThat(summary.parsedOkCount()).isEqualTo(1);
        assertThat(summary.symbolCount()).isGreaterThan(0);

        RepoView repo = queries.findByRootPath(summary.rootPath()).orElseThrow();

        // ② 点开文件核对：内容与磁盘一致，且没有"索引后被改过"的告警
        FileContentService.FileContent content = fileContentService.read(
                repo.id(), "main/java/demo/Hello.java", 3, 5);
        assertThat(content.lines()).extracting(FileContentService.Line::text)
                .containsExactly("public class Hello {", "    public String greet(String name) {",
                        "        return \"hi \" + name;");
        assertThat(content.totalLines()).isEqualTo(7);
        assertThat(content.changedSinceIndex()).as("刚索引完，磁盘与索引一致").isFalse();

        // 改一下磁盘上的文件：必须能察觉（否则用户会对着漂移的行号看代码）
        Path source = Path.of(summary.rootPath()).resolve("main/java/demo/Hello.java");
        Files.writeString(source, Files.readString(source, StandardCharsets.UTF_8) + "\n// 改了一行\n",
                StandardCharsets.UTF_8);
        assertThat(fileContentService.read(repo.id(), "main/java/demo/Hello.java", 1, 3).changedSinceIndex())
                .as("索引之后被改动的文件必须给出提示").isTrue();

        // ③ 路径防护：前端传上来的路径不可信
        assertThatThrownBy(() -> fileContentService.read(repo.id(), "../../windows/win.ini", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越出仓库范围");
        assertThatThrownBy(() -> fileContentService.read(repo.id(), "main/java/demo/Nope.java", null, null))
                .isInstanceOf(NotFoundException.class);

        // ④ 删除索引（外键级联）：删完就查不到了
        assertThat(indexer.deleteIndex(repo.id())).isTrue();
        assertThat(queries.findByRootPath(summary.rootPath())).isEmpty();
    }

    @Test
    void keepsTheStructureWhenTheArchiveHasNoWrapperDirectory() throws IOException {
        // 压缩包里直接就是 src/... 时**不能剥**：剥掉会连 src 一起扔掉，结果一个文件都找不到
        byte[] archive = zipOf("src/main/java/demo/Flat.java", """
                package demo;

                public class Flat {
                }
                """);

        IndexSummary summary = indexer.indexArchive(new ByteArrayInputStream(archive), "flat.zip");
        assertThat(summary.fileCount()).as("没有包装目录时保持原样，仍能索引到文件").isEqualTo(1);
        assertThat(summary.rootPath()).contains("flat");
        indexer.deleteIndex(summary.repoId());
    }

    @Test
    void rejectsArchivesThatAreNotZipAndUnsafeNames() {
        assertThatThrownBy(() -> indexer.indexArchive(new ByteArrayInputStream(new byte[0]), "empty.zip"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空");

        assertThat(ProjectIndexer.safeArchiveName("../../etc/passwd.zip")).isEqualTo("passwd");
        assertThat(ProjectIndexer.safeArchiveName("我的项目.zip")).isEqualTo("uploaded-repo");
        assertThat(ProjectIndexer.safeArchiveName(null)).isEqualTo("uploaded-repo");
    }

    /** 造一个只有一层目录的 zip（模拟"把项目文件夹压进去"）。 */
    private static byte[] zipOf(String entryName, String content) throws IOException {
        var buffer = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }
}
