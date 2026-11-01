package com.repoviewer.ui;

import com.repoviewer.core.FileTypes;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 代码 / 纯文本查看器，基于 RSyntaxTextArea。
 * <p>
 * 只读展示 + 搜索高亮，语法着色按扩展名切换；主题跟随 FlatLaf。
 */
final class CodeView extends JPanel {

    private final RSyntaxTextArea area = new RSyntaxTextArea();
    private final RTextScrollPane scroll;
    private Path file;
    private String query = "";
    private int matchCount;

    CodeView() {
        super(new MigLayout("insets 0, fill", "[grow,fill]", "[grow,fill]"));
        area.setEditable(false);
        area.setLineWrap(false);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setFractionalFontMetricsEnabled(false);
        area.setTabsEmulated(true);
        area.setTabSize(4);
        area.setHighlightCurrentLine(false);
        area.setMarkOccurrences(false);
        area.setRoundedSelectionEdges(true);
        area.setFont(Theme.monoFont(Font.PLAIN, 14));

        scroll = new RTextScrollPane(area);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(true);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getHorizontalScrollBar().setUnitIncrement(18);

        applyTheme();
        add(scroll, "grow");
        setBackground(Theme.bg());
    }

    void load(Path file, String query) {
        this.file = file;
        this.query = query == null ? "" : query;
        render();
    }

    void reload() {
        if (file != null) render();
    }

    Path getFile() {
        return file;
    }

    int getMatchCount() {
        return matchCount;
    }

    private void render() {
        if (file == null) return;
        try {
            long size = Files.size(file);
            String text = FileTypes.readText(file, 3L * 1024 * 1024);
            if (size > 3L * 1024 * 1024) {
                text = text + "\n\n…（文件较大，仅显示前 3 MB）";
            }
            String style = syntaxStyle(file);
            area.setSyntaxEditingStyle(style);
            area.setText(text);
            area.setCaretPosition(0);
        } catch (IOException e) {
            area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
            area.setText("无法读取文件：\n" + file);
        }
        applyTheme();
        matchCount = query.isBlank() ? 0 : highlight(query);
        if (matchCount > 0) SwingUtilities.invokeLater(this::scrollToFirstHighlight);
    }

    private void applyTheme() {
        String resource = Theme.isDark() ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                : "/org/fife/ui/rsyntaxtextarea/themes/idea.xml";
        try (InputStream in = CodeView.class.getResourceAsStream(resource)) {
            if (in != null) {
                org.fife.ui.rsyntaxtextarea.Theme syntaxTheme = org.fife.ui.rsyntaxtextarea.Theme.load(in);
                syntaxTheme.apply(area);
            }
        } catch (IOException ignored) {
        }
        area.setFont(Theme.monoFont(Font.PLAIN, 14));
        area.setFractionalFontMetricsEnabled(false);
        area.setBackground(Theme.bg());
        area.setForeground(Theme.text());
        area.setCurrentLineHighlightColor(Theme.bg());
        area.setSelectionColor(Theme.selection());
        area.setSelectedTextColor(Theme.text());
        area.setCaretColor(Theme.accent());
        if (scroll.getGutter() != null) {
            scroll.getGutter().setBackground(Theme.bg());
        }
        scroll.getViewport().setBackground(Theme.bg());
        setBackground(Theme.bg());
    }

    private static String syntaxStyle(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("dockerfile")) return SyntaxConstants.SYNTAX_STYLE_DOCKERFILE;
        if (name.equals("makefile") || name.equals("cmakelists.txt")) return SyntaxConstants.SYNTAX_STYLE_MAKEFILE;
        return switch (FileTypes.extension(file)) {
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "kt", "kts" -> SyntaxConstants.SYNTAX_STYLE_KOTLIN;
            case "groovy", "gradle" -> SyntaxConstants.SYNTAX_STYLE_GROOVY;
            case "scala" -> SyntaxConstants.SYNTAX_STYLE_SCALA;
            case "py", "pyw" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "js", "mjs", "cjs", "jsx" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            case "ts", "tsx" -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
            case "html", "htm", "xhtml" -> SyntaxConstants.SYNTAX_STYLE_HTML;
            case "css", "scss", "sass" -> SyntaxConstants.SYNTAX_STYLE_CSS;
            case "less" -> SyntaxConstants.SYNTAX_STYLE_LESS;
            case "xml" -> SyntaxConstants.SYNTAX_STYLE_XML;
            case "json", "json5", "jsonc" -> SyntaxConstants.SYNTAX_STYLE_JSON;
            case "yml", "yaml" -> SyntaxConstants.SYNTAX_STYLE_YAML;
            case "properties" -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
            case "ini", "conf", "cfg", "env" -> SyntaxConstants.SYNTAX_STYLE_INI;
            case "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL;
            case "sh", "bash", "zsh" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            case "bat", "cmd" -> SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH;
            case "ps1", "psm1" -> SyntaxConstants.SYNTAX_STYLE_POWERSHELL;
            case "c", "h" -> SyntaxConstants.SYNTAX_STYLE_C;
            case "cpp", "cc", "cxx", "hpp", "hh", "m", "mm" -> SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
            case "cs" -> SyntaxConstants.SYNTAX_STYLE_CSHARP;
            case "go" -> SyntaxConstants.SYNTAX_STYLE_GO;
            case "rs" -> SyntaxConstants.SYNTAX_STYLE_RUST;
            case "php" -> SyntaxConstants.SYNTAX_STYLE_PHP;
            case "rb" -> SyntaxConstants.SYNTAX_STYLE_RUBY;
            case "dart" -> SyntaxConstants.SYNTAX_STYLE_DART;
            case "lua" -> SyntaxConstants.SYNTAX_STYLE_LUA;
            case "tex" -> SyntaxConstants.SYNTAX_STYLE_LATEX;
            case "md", "markdown" -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
            case "proto" -> SyntaxConstants.SYNTAX_STYLE_PROTO;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }

    private int highlight(String query) {
        Highlighter highlighter = area.getHighlighter();
        highlighter.removeAllHighlights();
        String text = area.getText();
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String term : query.trim().split("\\s+")) {
            String core = term.replace("*", "").replace("?", "").toLowerCase(Locale.ROOT);
            if (!core.isBlank()) terms.add(core);
        }
        if (terms.isEmpty()) return 0;

        Color highlight = Theme.highlight();
        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(
                new Color(highlight.getRed(), highlight.getGreen(), highlight.getBlue(), 150));
        int count = 0;
        for (String term : terms) {
            int from = 0;
            while (from < lower.length()) {
                int idx = lower.indexOf(term, from);
                if (idx < 0) break;
                try {
                    highlighter.addHighlight(idx, idx + term.length(), painter);
                    count++;
                } catch (BadLocationException ignored) {
                }
                from = idx + Math.max(1, term.length());
                if (count > 1000) return count;
            }
        }
        return count;
    }

    private void scrollToFirstHighlight() {
        Highlighter.Highlight[] highlights = area.getHighlighter().getHighlights();
        if (highlights.length == 0) return;
        int pos = highlights[0].getStartOffset();
        try {
            Rectangle2D rect = area.modelToView2D(pos);
            if (rect != null) {
                area.scrollRectToVisible(rect.getBounds());
                area.setCaretPosition(pos);
            }
        } catch (BadLocationException ignored) {
        }
    }
}
