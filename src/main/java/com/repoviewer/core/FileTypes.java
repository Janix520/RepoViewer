package com.repoviewer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 文件类型与文本读取规则。
 * <p>
 * 这里集中定义“哪些文件算文档、哪些算代码、哪些目录永远不扫描”，
 * 扫描器、索引器和界面都只依赖这一份规则，避免三处各写一套。
 */
public final class FileTypes {

    private FileTypes() {
    }

    /** Markdown 文档扩展名。 */
    public static final Set<String> MD_EXT = Set.of("md", "markdown", "mdown", "mkd", "mdx");

    /** 参与“代码”搜索的文本型源码扩展名。 */
    public static final Set<String> CODE_EXT = Set.of(
            "java", "kt", "kts", "groovy", "gradle", "scala", "clj", "cljs",
            "py", "pyw", "rb", "php", "pl", "pm", "lua", "r", "dart", "swift",
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte",
            "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "fs", "vb",
            "go", "rs", "m", "mm", "asm", "s",
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "psm1",
            "sql", "html", "htm", "xhtml", "css", "scss", "sass", "less",
            "json", "json5", "jsonc", "xml", "yml", "yaml", "toml",
            "ini", "conf", "cfg", "properties", "env", "proto", "graphql", "gql",
            "cmake", "mk", "makefile", "dockerfile", "tex", "erl", "ex", "exs", "hs");

    /** 无扩展名但属于代码/构建脚本的文件名。 */
    public static final Set<String> CODE_FILE_NAMES = Set.of(
            "dockerfile", "makefile", "cmakelists.txt", "build", "workspace",
            "rakefile", "gemfile", "procfile", "jenkinsfile", "vagrantfile");

    /**
     * 扫描时默认整棵跳过的目录（用户可在设置里增删）。
     * <p>下载下来的仓库往往带着 node_modules / target / .git，跳过后扫描速度是数量级的差别。
     */
    public static final Set<String> DEFAULT_IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", ".bzr", ".idea", ".vscode", ".settings", ".gradle", ".mvn",
            ".next", ".nuxt", ".svelte-kit", ".cache", ".parcel-cache",
            ".pytest_cache", ".tox", ".venv", ".nyc_output", ".terraform",
            ".dart_tool", ".pub-cache", ".angular", ".turbo", ".hypothesis",
            ".ipynb_checkpoints", ".mypy_cache", ".ruff_cache",
            "node_modules", "bower_components", "jspm_packages",
            "target", "build", "dist", "out", "bin", "obj", "libs",
            "vendor", "Pods", "DerivedData", "__pycache__", "venv", "env", "site-packages",
            "coverage", "logs", "tmp", "temp",
            "cmake-build-debug", "cmake-build-release", "debug", "release", "x64", "x86");

    /**
     * 一次扫描使用的忽略规则。
     * <p>目录名大小写不敏感；只匹配目录名，不支持路径或通配符。
     */
    public record IgnoreRules(boolean ignoreHiddenDirs, Set<String> directoryNames) {

        public IgnoreRules {
            Set<String> normalized = new LinkedHashSet<>();
            if (directoryNames != null) {
                for (String raw : directoryNames) {
                    if (raw == null) continue;
                    String name = raw.trim().toLowerCase(Locale.ROOT);
                    if (name.isEmpty() || name.contains("/") || name.contains("\\")) continue;
                    normalized.add(name);
                }
            }
            directoryNames = Set.copyOf(normalized);
        }

        public static IgnoreRules defaults() {
            return new IgnoreRules(true, DEFAULT_IGNORED_DIRS);
        }

        public boolean ignores(Path dir) {
            Path fileName = dir.getFileName();
            if (fileName == null) return false;
            String name = fileName.toString();
            if (ignoreHiddenDirs && name.startsWith(".")) return true;
            return directoryNames.contains(name.toLowerCase(Locale.ROOT));
        }
    }

    /** 文本索引时的文件大小上限：文档 2MB，代码 512KB。 */
    public static final long MAX_DOC_BYTES = 2L * 1024 * 1024;
    public static final long MAX_CODE_BYTES = 512L * 1024;

    /** 扩展名（小写、不含点），无扩展名返回空串。 */
    public static String extension(Path file) {
        Path namePath = file.getFileName();
        if (namePath == null) return "";
        String name = namePath.toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isMarkdown(Path file) {
        return MD_EXT.contains(extension(file));
    }

    public static boolean isCode(Path file) {
        String ext = extension(file);
        if (!ext.isEmpty()) return CODE_EXT.contains(ext);
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return CODE_FILE_NAMES.contains(name);
    }

    /** 该文件是否可能参与索引（按扩展名和大小判断）。 */
    public static boolean isIndexable(Path file) {
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) return false;
            long size = Files.size(file);
            if (isMarkdown(file)) return size > 0 && size <= MAX_DOC_BYTES;
            if (isCode(file)) return size > 0 && size <= MAX_CODE_BYTES;
        } catch (IOException ignored) {
        }
        return false;
    }

    public static boolean isIgnoredDir(Path dir) {
        return IgnoreRules.defaults().ignores(dir);
    }

    public static boolean isIgnoredDir(Path dir, IgnoreRules rules) {
        return (rules == null ? IgnoreRules.defaults() : rules).ignores(dir);
    }

    /**
     * 规范化用户填写的扩展名：去点、小写、去空白，丢弃路径等非法值。
     * <p>无扩展名的特殊文件名（如 Dockerfile）也可以作为“扩展名”填进来。
     */
    public static Set<String> normalizeExtensions(Collection<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String raw : values) {
            if (raw == null) continue;
            String name = raw.trim().toLowerCase(Locale.ROOT);
            while (name.startsWith(".")) name = name.substring(1);
            if (name.isEmpty() || name.contains("/") || name.contains("\\")
                    || name.contains(" ") || name.contains("*")) {
                continue;
            }
            if (!name.matches("[a-z0-9_+-]+")) continue;
            result.add(name);
        }
        return result;
    }

    /**
     * 读取文本文件，自动处理编码。
     * <p>顺序：BOM → UTF-8 严格解码 → GB18030（国内老项目常见）。
     */
    public static String readText(Path file, long maxBytes) throws IOException {
        byte[] raw = readHead(file, maxBytes);
        if (raw.length == 0) return "";

        // BOM
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
        }

        // 严格 UTF-8；失败再退回 GB18030
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(raw));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return new String(raw, Charset.forName("GB18030"));
        }
    }

    public static String readText(Path file) throws IOException {
        return readText(file, MAX_DOC_BYTES);
    }

    private static byte[] readHead(Path file, long maxBytes) throws IOException {
        long size = Files.size(file);
        int limit = (int) Math.min(size, Math.max(0, maxBytes));
        byte[] data = new byte[limit];
        try (var in = Files.newInputStream(file)) {
            int offset = 0;
            while (offset < limit) {
                int read = in.read(data, offset, limit - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset == limit) return data;
            byte[] shorter = new byte[offset];
            System.arraycopy(data, 0, shorter, 0, offset);
            return shorter;
        }
    }

    /** 粗略判断字节内容是否为二进制（含 NUL 或控制字符比例过高）。 */
    public static boolean looksBinary(byte[] data) {
        int max = Math.min(data.length, 1024);
        int control = 0;
        for (int i = 0; i < max; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) return true;
            if (b < 32 && b != '\n' && b != '\r' && b != '\t' && b != '\f') control++;
        }
        return max > 0 && control > max / 10;
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
