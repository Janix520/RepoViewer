package com.repoviewer.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.repoviewer.core.MarkdownRenderer;

import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * 主题：FlatLaf 亮 / 暗两套，加上界面自己用的一套调色板。
 * <p>
 * 所有自绘组件（项目树、搜索结果、图标）都从这里取色，切换主题时只要重绘即可；
 * Markdown 的 HTML 颜色是渲染时写死的，所以需要由调用方重新渲染当前文档。
 */
public final class Theme {

    public enum Mode { LIGHT, DARK }

    private static Mode mode = Mode.LIGHT;

    private Theme() {
    }

    // ---- 调色板 ----

    public static Color bg() { return mode == Mode.DARK ? c(0x0D1117) : c(0xFFFFFF); }
    public static Color panel() { return mode == Mode.DARK ? c(0x161B22) : c(0xF6F8FA); }
    public static Color surface() { return mode == Mode.DARK ? c(0x0D1117) : c(0xFFFFFF); }
    public static Color border() { return mode == Mode.DARK ? c(0x30363D) : c(0xD0D7DE); }
    public static Color text() { return mode == Mode.DARK ? c(0xE6EDF3) : c(0x1F2328); }
    public static Color muted() { return mode == Mode.DARK ? c(0x8D96A0) : c(0x59636E); }
    public static Color accent() { return mode == Mode.DARK ? c(0x4493F8) : c(0x0969DA); }
    public static Color accentSoft() { return mode == Mode.DARK ? c(0x1C3A5E) : c(0xDDF4FF); }
    public static Color selection() { return mode == Mode.DARK ? c(0x1F3A5F) : c(0xDDF4FF); }
    public static Color hover() { return mode == Mode.DARK ? c(0x1C2128) : c(0xF3F4F6); }
    public static Color codeBg() { return mode == Mode.DARK ? c(0x161B22) : c(0xF6F8FA); }
    public static Color highlight() { return mode == Mode.DARK ? c(0x6B5900) : c(0xFFF8C5); }
    public static Color green() { return mode == Mode.DARK ? c(0x3FB950) : c(0x1A7F37); }
    public static Color amber() { return mode == Mode.DARK ? c(0xE3B341) : c(0xBF8700); }

    private static Color c(int rgb) {
        return new Color(rgb);
    }

    public static Mode mode() {
        return mode;
    }

    public static boolean isDark() {
        return mode == Mode.DARK;
    }

    // ---- FlatLaf ----

    public static void install(Mode newMode) {
        mode = newMode;
        if (newMode == Mode.DARK) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        applyDefaults();
        FlatLaf.updateUI();
    }

    public static void toggle() {
        install(mode == Mode.DARK ? Mode.LIGHT : Mode.DARK);
    }

    private static void applyDefaults() {
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 1);
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("SplitPane.dividerSize", 7);
        UIManager.put("TabbedPane.tabHeight", 34);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", true);
        UIManager.put("Tree.paintLines", false);
        UIManager.put("Tree.showDefaultIcons", false);
        UIManager.put("ToolTip.background", panel());
        UIManager.put("ToolTip.foreground", text());
        UIManager.put("ToolTip.border", new javax.swing.border.LineBorder(border()));
        UIManager.put("List.selectionBackground", selection());
        UIManager.put("Tree.selectionBackground", selection());
        UIManager.put("Tree.selectionForeground", text());
        UIManager.put("List.selectionForeground", text());
        UIManager.put("Table.selectionBackground", selection());
        UIManager.put("TextField.selectionBackground", accentSoft());
        UIManager.put("defaultFont", new FontUIResource(uiFont(Font.PLAIN, 14)));
    }

    /** Windows 下用 LCD 子像素抗锯齿，和系统 ClearType 保持一致；其它平台用灰度。 */
    public static Object textAntialiasHint() {
        return isWindows()
                ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                : RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ---- 字体 ----

    private static final String UI_FAMILY = pickFamily("Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "PingFang SC");
    private static final String MONO_FAMILY = pickFamily("JetBrains Mono", "Cascadia Mono", "Consolas", "Courier New");

    private static String pickFamily(String... candidates) {
        var families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        var set = new java.util.HashSet<String>();
        for (String family : families) set.add(family);
        for (String candidate : candidates) {
            if (set.contains(candidate)) return candidate;
        }
        return Font.SANS_SERIF;
    }

    public static Font uiFont(int style, float size) {
        return new Font(UI_FAMILY, style, Math.round(size));
    }

    public static Font monoFont(int style, float size) {
        return new Font(MONO_FAMILY, style, Math.round(size));
    }

    public static MarkdownRenderer.RenderTheme renderTheme() {
        return new MarkdownRenderer.RenderTheme(isDark(), 12, 780);
    }
}
