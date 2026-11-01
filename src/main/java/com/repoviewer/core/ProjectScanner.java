package com.repoviewer.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 扫描根目录，识别项目并归集文档 / 代码文件。
 * <p>
 * 识别规则：
 * <ol>
 *   <li>目录里有 README.md（大小写不敏感，兼容 readme.markdown）→ 项目；</li>
 *   <li>没有 README，但有 pom.xml / package.json / .git 等构建标记 → 也算项目，
 *       界面上会标注“无 README”，避免下载来的空壳仓库直接消失。</li>
 * </ol>
 * 嵌套项目不截断扫描：项目 A 里的项目 B 会继续被发现并挂在 A 下面。
 */
public final class ProjectScanner {

    private ProjectScanner() {
    }

    public record ScanResult(RepoProject root,
                             List<RepoProject> projects,
                             int docCount,
                             int codeCount,
                             long elapsedMs) {
    }

    private static final int MAX_DEPTH = 16;
    /** 默认只把扫描根目录下 4 层以内的 README 目录识别成项目，避免 JDK 源码这类深层包目录刷屏。 */
    public static final int DEFAULT_PROJECT_DEPTH = 4;

    private static final String[] MARKER_NAMES = {
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "package.json", "cargo.toml", "go.mod", "pyproject.toml", "setup.py", "setup.cfg",
            "cmakelists.txt", "composer.json", "gemfile", "pubspec.yaml", "deno.json",
            ".git", ".hg", ".svn", "projectsettings", "mix.exs", "rebar.config", "dune-project",
            "stack.yaml", "flake.nix"
    };

    private static final String[] MARKER_SUFFIXES = {
            ".sln", ".csproj", ".vcxproj", ".xcodeproj", ".gemspec", ".cabal", ".uproject"
    };

    public static ScanResult scan(Path root, boolean includeCode,
                                  Consumer<String> progress, AtomicBoolean cancel) throws IOException {
        return scan(root, includeCode, FileTypes.IgnoreRules.defaults(), progress, cancel);
    }

    public static ScanResult scan(Path root, boolean includeCode, FileTypes.IgnoreRules ignoreRules,
                                  Consumer<String> progress, AtomicBoolean cancel) throws IOException {
        return scan(root, includeCode ? allCodeTypes() : Set.of(), ignoreRules,
                DEFAULT_PROJECT_DEPTH, progress, cancel);
    }

    /**
     * @param codeExtensions 需要收集并参与索引的代码扩展名；空集合表示只处理 Markdown
     */
    public static ScanResult scan(Path root, Set<String> codeExtensions, FileTypes.IgnoreRules ignoreRules,
                                  Consumer<String> progress, AtomicBoolean cancel) throws IOException {
        return scan(root, codeExtensions, ignoreRules, DEFAULT_PROJECT_DEPTH, progress, cancel);
    }

    /**
     * @param projectMaxDepth 只把扫描根目录下该深度以内的 README 目录识别为项目；
     *                        更深的 README 仍会作为最近上级项目的文档被收集和索引
     */
    public static ScanResult scan(Path root, Set<String> codeExtensions, FileTypes.IgnoreRules ignoreRules,
                                  int projectMaxDepth, Consumer<String> progress,
                                  AtomicBoolean cancel) throws IOException {
        long start = System.currentTimeMillis();
        Path rootAbs = root.toAbsolutePath().normalize();
        FileTypes.IgnoreRules rules = ignoreRules == null ? FileTypes.IgnoreRules.defaults() : ignoreRules;
        Set<String> codeTypes = FileTypes.normalizeExtensions(codeExtensions);
        int depthLimit = Math.max(0, projectMaxDepth);
        Map<Path, RepoProject> projects = new HashMap<>();
        RepoProject rootProject = new RepoProject(rootAbs, null, false);
        projects.put(rootAbs, rootProject);

        int[] docCount = {0};
        int[] codeCount = {0};
        int[] dirCount = {0};

        Files.walkFileTree(rootAbs, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (cancelled(cancel)) return FileVisitResult.TERMINATE;
                        if (!dir.equals(rootAbs) && rules.ignores(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        dirCount[0]++;
                        if (rootAbs.relativize(dir).getNameCount() <= depthLimit) {
                            inspectProjectDir(dir, projects);
                        }
                        if (progress != null && dirCount[0] % 200 == 0) {
                            progress.accept("正在扫描目录… 已检查 " + dirCount[0] + " 个目录，发现 "
                                    + projects.size() + " 个项目");
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (cancelled(cancel)) return FileVisitResult.TERMINATE;
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        String ext = FileTypes.extension(file);

                        boolean markdown = FileTypes.MD_EXT.contains(ext);
                        boolean code = false;
                        if (!markdown && !codeTypes.isEmpty()) {
                            if (!ext.isEmpty()) {
                                code = codeTypes.contains(ext);
                            } else if (file.getFileName() != null) {
                                // Dockerfile / Makefile 这类无扩展名的文件，允许按文件名加入
                                code = codeTypes.contains(file.getFileName().toString().toLowerCase(Locale.ROOT));
                            }
                        }
                        if (!markdown && !code) return FileVisitResult.CONTINUE;

                        long size = attrs.size();
                        if (markdown) {
                            if (size <= 0 || size > FileTypes.MAX_DOC_BYTES) return FileVisitResult.CONTINUE;
                        } else {
                            if (size <= 0 || size > FileTypes.MAX_CODE_BYTES) return FileVisitResult.CONTINUE;
                        }

                        RepoProject owner = nearestProject(file, projects, rootProject);
                        if (markdown) {
                            owner.addMarkdownFile(file);
                            docCount[0]++;
                        } else {
                            owner.addCodeFile(file);
                            codeCount[0]++;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });

        // 建立父子关系
        List<RepoProject> all = new ArrayList<>(projects.values());
        for (RepoProject project : all) {
            if (project == rootProject) continue;
            Path parentPath = project.getRoot().getParent();
            while (parentPath != null && !projects.containsKey(parentPath)) {
                parentPath = parentPath.getParent();
            }
            RepoProject parent = parentPath == null ? rootProject : projects.get(parentPath);
            parent.addChild(project);
        }

        rootProject.sortFiles();
        if (progress != null) {
            progress.accept("扫描完成：发现 " + all.size() + " 个项目");
        }
        return new ScanResult(rootProject, all, docCount[0], codeCount[0],
                System.currentTimeMillis() - start);
    }

    private static boolean cancelled(AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }

    private static Set<String> allCodeTypes() {
        Set<String> all = new LinkedHashSet<>(FileTypes.CODE_EXT);
        all.addAll(FileTypes.CODE_FILE_NAMES);
        return all;
    }

    /** 看目录下有没有 README / 构建标记，有就登记成项目。目录内容只列一次。 */
    private static void inspectProjectDir(Path dir, Map<Path, RepoProject> projects) {
        RepoProject existing = projects.get(dir);   // 根目录会预先登记，但仍要补上 README

        String readmeName = null;
        int readmeRank = Integer.MAX_VALUE;
        boolean marker = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);

                int rank = readmeRank(lower);
                if (rank >= 0 && rank < readmeRank) {
                    readmeRank = rank;
                    readmeName = name;
                }
                if (!marker) {
                    marker = isMarker(lower);
                }
            }
        } catch (IOException | SecurityException ignored) {
            return;
        }

        if (readmeName != null) {
            Path readme = dir.resolve(readmeName);
            if (existing != null) {
                existing.setReadme(readme);
            } else {
                projects.put(dir, new RepoProject(dir, readme, false));
            }
        } else if (marker && existing == null) {
            projects.put(dir, new RepoProject(dir, null, true));
        }
    }

    /** README 优先级：readme.md > readme.markdown > 其他 readme*.md；没有返回 -1。 */
    private static int readmeRank(String lowerName) {
        if (lowerName.equals("readme.md")) return 0;
        if (lowerName.equals("readme.markdown")) return 1;
        if (lowerName.equals("readme.mdown") || lowerName.equals("readme.mkd")) return 2;
        if (lowerName.startsWith("readme") && lowerName.endsWith(".md")) return 3;
        return -1;
    }

    private static boolean isMarker(String lowerName) {
        for (String marker : MARKER_NAMES) {
            if (lowerName.equals(marker)) return true;
        }
        for (String suffix : MARKER_SUFFIXES) {
            if (lowerName.endsWith(suffix)) return true;
        }
        return false;
    }

    private static RepoProject nearestProject(Path file, Map<Path, RepoProject> projects, RepoProject root) {
        Path dir = file.getParent();
        while (dir != null) {
            RepoProject project = projects.get(dir);
            if (project != null) return project;
            dir = dir.getParent();
        }
        return root;
    }
}
