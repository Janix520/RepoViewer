package com.repoviewer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScannerTest {

    @TempDir
    Path tmp;

    @Test
    void findsNestedProjectsAndSkipsIgnoredDirectories() throws Exception {
        write(tmp.resolve("README.md"), "# root");
        write(tmp.resolve("notes.md"), "# notes");
        write(tmp.resolve("project-a/README.md"), "# A");
        write(tmp.resolve("project-a/docs/guide.md"), "# guide");
        write(tmp.resolve("project-a/child/README.md"), "# B");
        write(tmp.resolve("project-a/node_modules/pkg/README.md"), "# nope");
        write(tmp.resolve("project-c/package.json"), "{}");

        ProjectScanner.ScanResult result = ProjectScanner.scan(tmp, true, null, null);

        assertEquals(4, result.projects().size());   // root, A, B, C
        RepoProject root = result.root();
        RepoProject a = find(result, "project-a");
        RepoProject b = find(result, "child");
        RepoProject c = find(result, "project-c");

        assertNotNull(root);
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        assertTrue(root.hasReadme());
        assertTrue(a.hasReadme());
        assertTrue(b.hasReadme());
        assertFalse(c.hasReadme());
        assertTrue(c.isMarkerProject());

        assertEquals(root, a.getParent());
        assertEquals(a, b.getParent());
        assertEquals(2, root.getMarkdownFiles().size());   // README.md + notes.md
        assertEquals(2, a.getMarkdownFiles().size());      // README.md + docs/guide.md
        assertEquals(1, b.getMarkdownFiles().size());

        // node_modules 下的 README 不应被任何项目收集
        for (RepoProject project : result.projects()) {
            for (Path doc : project.getMarkdownFiles()) {
                assertFalse(doc.toString().contains("node_modules"));
            }
        }
    }

    @Test
    void prefersCanonicalReadmeName() throws Exception {
        write(tmp.resolve("README.md"), "# canonical");
        write(tmp.resolve("README_zh.md"), "# translation");

        ProjectScanner.ScanResult result = ProjectScanner.scan(tmp, false, null, null);
        RepoProject root = result.root();
        assertEquals("README.md", root.getReadme().getFileName().toString());
    }

    @Test
    void respectsCustomIgnoreRulesAndHiddenToggle() throws Exception {
        write(tmp.resolve("keep/README.md"), "# keep");
        write(tmp.resolve("skipme/README.md"), "# skip");
        write(tmp.resolve(".hidden/README.md"), "# hidden");

        FileTypes.IgnoreRules custom = new FileTypes.IgnoreRules(true, Set.of("skipme"));
        ProjectScanner.ScanResult result = ProjectScanner.scan(tmp, false, custom, null, null);
        assertNotNull(find(result, "keep"));
        assertNull(find(result, "skipme"));
        assertNull(find(result, ".hidden"));

        FileTypes.IgnoreRules includeHidden = new FileTypes.IgnoreRules(false, Set.of("skipme"));
        ProjectScanner.ScanResult result2 = ProjectScanner.scan(tmp, false, includeHidden, null, null);
        assertNotNull(find(result2, ".hidden"));
        assertNull(find(result2, "skipme"));
    }

    @Test
    void onlyCollectsSelectedCodeExtensions() throws Exception {
        write(tmp.resolve("proj/README.md"), "# p");
        write(tmp.resolve("proj/src/Main.java"), "class Main {}");
        write(tmp.resolve("proj/app.py"), "print(1)");

        ProjectScanner.ScanResult noCode = ProjectScanner.scan(tmp, Set.of(),
                FileTypes.IgnoreRules.defaults(), null, null);
        RepoProject p1 = find(noCode, "proj");
        assertNotNull(p1);
        assertEquals(0, p1.getCodeFiles().size());

        ProjectScanner.ScanResult javaOnly = ProjectScanner.scan(tmp, Set.of("java"),
                FileTypes.IgnoreRules.defaults(), null, null);
        RepoProject p2 = find(javaOnly, "proj");
        assertNotNull(p2);
        assertEquals(1, p2.getCodeFiles().size());
        assertTrue(p2.getCodeFiles().get(0).toString().endsWith("Main.java"));
    }

    @Test
    void limitsProjectDepthButKeepsDeeperDocsInParentProject() throws Exception {
        write(tmp.resolve("a/README.md"), "# a");
        write(tmp.resolve("a/b/c/README.md"), "# c");

        ProjectScanner.ScanResult shallow = ProjectScanner.scan(tmp, Set.of(),
                FileTypes.IgnoreRules.defaults(), 1, null, null);
        RepoProject a = find(shallow, "a");
        assertNotNull(a);
        assertNull(find(shallow, "c"));
        assertTrue(a.getMarkdownFiles().stream()
                .anyMatch(p -> p.toString().replace('\\', '/').endsWith("a/b/c/README.md")));

        ProjectScanner.ScanResult deep = ProjectScanner.scan(tmp, Set.of(),
                FileTypes.IgnoreRules.defaults(), 4, null, null);
        assertNotNull(find(deep, "c"));
    }

    @Test
    void normalizesCustomExtensions() {
        assertEquals(Set.of("java", "js", "dockerfile"),
                FileTypes.normalizeExtensions(List.of(".JAVA", " js ", "bad/path", "*.ts", "dockerfile")));
    }

    private RepoProject find(ProjectScanner.ScanResult result, String name) {
        return result.projects().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst().orElse(null);
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
