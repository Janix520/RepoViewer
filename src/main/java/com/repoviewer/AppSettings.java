package com.repoviewer;

import com.repoviewer.core.FileTypes;
import com.repoviewer.core.ProjectScanner;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * 应用级配置：上次打开的目录、主题、窗口位置、索引目录。
 * <p>用 {@link Preferences} 存，不额外产生配置文件；索引放在用户目录下，重装应用也能复用。
 */
public final class AppSettings {

    private static final Preferences PREFS = Preferences.userRoot().node("com/repoviewer");

    private AppSettings() {
    }

    /** 允许 -Drepoviewer.home 覆盖（便携版 jpackage 场景）。 */
    public static Path home() {
        String override = System.getProperty("repoviewer.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".repoviewer");
    }

    public static Path indexPath() {
        return home().resolve("index");
    }

    public static Path lastRoot() {
        String value = PREFS.get("lastRoot", "");
        if (value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setLastRoot(Path root) {
        PREFS.put("lastRoot", root == null ? "" : root.toString());
    }

    public static boolean dark() {
        return "dark".equals(PREFS.get("theme", "light"));
    }

    public static void setDark(boolean dark) {
        PREFS.put("theme", dark ? "dark" : "light");
    }

    public static int[] windowBounds() {
        String value = PREFS.get("bounds", "");
        if (value.isBlank()) return null;
        try {
            String[] parts = value.split(",");
            if (parts.length != 4) return null;
            return new int[]{
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setWindowBounds(int x, int y, int width, int height) {
        PREFS.put("bounds", x + "," + y + "," + width + "," + height);
    }

    public static boolean maximized() {
        return Boolean.parseBoolean(PREFS.get("maximized", "false"));
    }

    public static void setMaximized(boolean maximized) {
        PREFS.put("maximized", Boolean.toString(maximized));
    }

    public static int divider() {
        return PREFS.getInt("divider", 300);
    }

    public static void setDivider(int divider) {
        PREFS.putInt("divider", divider);
    }

    // ---- 扫描忽略规则 ----

    /** 用户自定义的忽略目录；从未保存过时使用内置默认列表。 */
    public static Set<String> ignoredDirs() {
        String saved = PREFS.get("ignoredDirs", null);
        if (saved == null) return FileTypes.DEFAULT_IGNORED_DIRS;
        Set<String> names = new LinkedHashSet<>();
        for (String line : saved.split("\\R")) {
            if (!line.isBlank()) names.add(line.trim());
        }
        return names;
    }

    public static void setIgnoredDirs(Collection<String> names) {
        StringBuilder sb = new StringBuilder();
        if (names != null) {
            for (String name : names) {
                if (name == null || name.isBlank()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(name.trim());
            }
        }
        PREFS.put("ignoredDirs", sb.toString());
    }

    public static boolean ignoreHiddenDirs() {
        return PREFS.getBoolean("ignoreHiddenDirs", true);
    }

    public static void setIgnoreHiddenDirs(boolean ignore) {
        PREFS.putBoolean("ignoreHiddenDirs", ignore);
    }

    public static FileTypes.IgnoreRules ignoreRules() {
        return new FileTypes.IgnoreRules(ignoreHiddenDirs(), ignoredDirs());
    }

    /** 把扫描根目录下多少层以内的 README 目录识别为项目；更深的 README 仍作为上级项目文档参与索引。 */
    public static int projectDepth() {
        int depth = PREFS.getInt("projectDepth", ProjectScanner.DEFAULT_PROJECT_DEPTH);
        return Math.max(1, Math.min(16, depth));
    }

    public static void setProjectDepth(int depth) {
        PREFS.putInt("projectDepth", Math.max(1, Math.min(16, depth)));
    }

    // ---- 代码索引扩展名（默认不索引任何代码，只索引 Markdown） ----

    public static Set<String> codeExtensions() {
        String saved = PREFS.get("codeExtensions", null);
        if (saved == null) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (String line : saved.split("\\R")) {
            if (!line.isBlank()) names.add(line.trim());
        }
        return FileTypes.normalizeExtensions(names);
    }

    public static void setCodeExtensions(Collection<String> extensions) {
        StringBuilder sb = new StringBuilder();
        for (String ext : FileTypes.normalizeExtensions(extensions)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(ext);
        }
        PREFS.put("codeExtensions", sb.toString());
    }

    public static boolean codeIndexingEnabled() {
        return !codeExtensions().isEmpty();
    }
}
