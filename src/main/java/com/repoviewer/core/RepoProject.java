package com.repoviewer.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一个被识别出来的项目节点。
 * <p>
 * 项目之间可以嵌套（A 里有 B），所以这里同时保存父子关系；
 * 文档和代码文件归属“最近的”祖先项目，子项目单独成节点。
 */
public final class RepoProject {

    private final Path root;
    private final String name;
    private Path readme;
    private final boolean markerProject;
    private final List<Path> markdownFiles = new ArrayList<>();
    private final List<Path> codeFiles = new ArrayList<>();
    private final List<RepoProject> children = new ArrayList<>();
    private RepoProject parent;
    private long lastModified;

    private boolean summaryLoaded;
    private String title;
    private String summary;

    public RepoProject(Path root, Path readme, boolean markerProject) {
        this.root = root.toAbsolutePath().normalize();
        this.name = displayName(this.root);
        this.readme = readme == null ? null : readme.toAbsolutePath().normalize();
        this.markerProject = markerProject;
    }

    private static String displayName(Path path) {
        Path fileName = path.getFileName();
        if (fileName != null) return fileName.toString();
        return path.toString();
    }

    // ---- 结构 ----

    public void addChild(RepoProject child) {
        child.parent = this;
        children.add(child);
        children.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
    }

    /** 从根到当前节点的完整链（含自身），用于索引里的 scope 字段。 */
    public Set<String> ancestorRoots() {
        Set<String> roots = new LinkedHashSet<>();
        for (RepoProject p = this; p != null; p = p.parent) {
            roots.add(p.root.toString());
        }
        return roots;
    }

    public Path getRoot() { return root; }
    public String getName() { return name; }
    public Path getReadme() { return readme; }
    public void setReadme(Path readme) { this.readme = readme; }
    public boolean hasReadme() { return readme != null; }
    /** 没有 README、仅凭构建文件（pom.xml/package.json 等）识别出来的项目。 */
    public boolean isMarkerProject() { return markerProject && readme == null; }
    public RepoProject getParent() { return parent; }
    public List<RepoProject> getChildren() { return children; }
    public List<Path> getMarkdownFiles() { return markdownFiles; }
    public List<Path> getCodeFiles() { return codeFiles; }
    public long getLastModified() { return lastModified; }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public void addMarkdownFile(Path file) {
        markdownFiles.add(file);
        markUpdated(file);
    }

    public void addCodeFile(Path file) {
        codeFiles.add(file);
        markUpdated(file);
    }

    private void markUpdated(Path file) {
        try {
            lastModified = Math.max(lastModified, java.nio.file.Files.getLastModifiedTime(file).toMillis());
        } catch (IOException ignored) {
        }
    }

    public void sortFiles() {
        Comparator<Path> byPath = Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT));
        markdownFiles.sort((a, b) -> {
            boolean ra = a.equals(readme);
            boolean rb = b.equals(readme);
            if (ra != rb) return ra ? -1 : 1;
            return byPath.compare(a, b);
        });
        codeFiles.sort(byPath);
        for (RepoProject child : children) child.sortFiles();
    }

    // ---- README 摘要（按需读取，扫描阶段不碰磁盘） ----

    public String getTitle() {
        ensureSummary();
        return title != null ? title : name;
    }

    public String getSummary() {
        ensureSummary();
        return summary;
    }

    private void ensureSummary() {
        if (summaryLoaded) return;
        summaryLoaded = true;
        if (readme == null) return;
        try {
            String text = FileTypes.readText(readme, 128 * 1024);
            title = MarkdownRenderer.extractTitle(text);
            summary = MarkdownRenderer.extractSummary(text);
        } catch (IOException ignored) {
        }
    }
}
