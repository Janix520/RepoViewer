package com.repoviewer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoIndexTest {

    @TempDir
    Path tmp;

    @Test
    void indexesIncrementallyAndSearchesContentByScopeAndProject() throws Exception {
        Path aRoot = tmp.resolve("alpha");
        Path aReadme = aRoot.resolve("README.md");
        Path aJava = aRoot.resolve("src/Main.java");
        Path bRoot = tmp.resolve("beta");
        Path bReadme = bRoot.resolve("README.md");

        write(aReadme, "# Alpha\n\n这是一个嵌套项目，用来测试全文搜索。\n");
        write(aJava, "public class Main {\n    // Nested project code search test\n}\n");
        write(bReadme, "# Beta\n\nBeta 项目的说明文档。\n");

        RepoProject root = new RepoProject(tmp, null, false);
        RepoProject alpha = new RepoProject(aRoot, aReadme, false);
        RepoProject beta = new RepoProject(bRoot, bReadme, false);
        root.addChild(alpha);
        root.addChild(beta);

        List<RepoIndex.Doc> docs = List.of(
                new RepoIndex.Doc(aReadme, alpha, true),
                new RepoIndex.Doc(aJava, alpha, false),
                new RepoIndex.Doc(bReadme, beta, true));

        try (RepoIndex index = new RepoIndex(tmp.resolve(".index"))) {
            RepoIndex.SyncResult first = index.sync(docs, tmp.toString(), null, null);
            assertEquals(3, first.indexed());
            assertEquals(3, index.documentCount());

            List<RepoIndex.Hit> chinese = index.search("嵌套", RepoIndex.Scope.ALL, null, 20);
            assertEquals(1, chinese.size());
            assertEquals("README.md", chinese.get(0).name());
            assertTrue(chinese.get(0).snippet().contains("嵌套"));
            assertTrue(chinese.get(0).line() >= 1);

            // 末尾带中文标点也应该命中
            assertFalse(index.search("嵌套，", RepoIndex.Scope.ALL, null, 20).isEmpty());

            // 按项目汇总：alpha 2 篇、beta 1 篇
            RepoIndex.Outcome outcome = index.searchWithProjects("", RepoIndex.Scope.ALL, null, 20);
            assertEquals(3, outcome.totalHits());
            assertEquals(2, outcome.projects().size());
            RepoIndex.ProjectCount alphaCount = outcome.projects().stream()
                    .filter(p -> p.projectPath().equals(alpha.getRoot().toString()))
                    .findFirst().orElseThrow();
            RepoIndex.ProjectCount betaCount = outcome.projects().stream()
                    .filter(p -> p.projectPath().equals(beta.getRoot().toString()))
                    .findFirst().orElseThrow();
            assertEquals(2, alphaCount.count());
            assertEquals(1, betaCount.count());

            List<RepoIndex.Hit> codeOnly = index.search("Nested", RepoIndex.Scope.CODE, null, 20);
            assertEquals(1, codeOnly.size());
            assertEquals("Main.java", codeOnly.get(0).name());
            assertFalse(codeOnly.get(0).markdown());

            assertTrue(index.search("Nested", RepoIndex.Scope.MARKDOWN, null, 20).isEmpty());

            // 项目作用域：alpha（含子项目）拿到 2 条，beta 不参与
            List<RepoIndex.Hit> scoped = index.search("", RepoIndex.Scope.ALL, aRoot, 20);
            assertEquals(2, scoped.size());
            assertTrue(scoped.stream().allMatch(h -> h.file().startsWith(aRoot)));

            // 第二次同步全部复用
            RepoIndex.SyncResult second = index.sync(docs, tmp.toString(), null, null);
            assertEquals(0, second.indexed());
            assertEquals(3, second.skipped());

            // 修改 README 后只重建这一条
            write(aReadme, "# Alpha\n\n内容更新了，仍然包含嵌套与搜索关键字。\n");
            Files.setLastModifiedTime(aReadme,
                    FileTime.fromMillis(System.currentTimeMillis() + 2000));
            RepoIndex.SyncResult third = index.sync(docs, tmp.toString(), null, null);
            assertEquals(1, third.indexed());
            assertEquals(2, third.skipped());

            // 删除文件后索引条目同步移除
            Files.delete(aJava);
            RepoIndex.SyncResult fourth = index.sync(List.of(
                    new RepoIndex.Doc(aReadme, alpha, true),
                    new RepoIndex.Doc(bReadme, beta, true)), tmp.toString(), null, null);
            assertEquals(1, fourth.deleted());
            assertEquals(2, index.documentCount());
        }
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
