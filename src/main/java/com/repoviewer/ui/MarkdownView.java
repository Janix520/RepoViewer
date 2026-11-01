package com.repoviewer.ui;

import com.repoviewer.core.FileTypes;
import com.repoviewer.core.MarkdownRenderer;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Markdown 阅读器：JEditorPane + 右侧目录。
 * <p>
 * 文档中的本地链接统一回调 {@link LinkHandler}，由外层的标签页系统决定是
 * 打开另一篇 Markdown、代码文件，还是交给系统程序。
 */
final class MarkdownView extends JPanel {

    interface LinkHandler {
        void openLocal(Path path, String anchor);
    }

    private final JEditorPane editor = new AntiAliasedHtmlPane();
    private final JPanel tocPanel = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]"));
    private final JScrollPane tocScroll = UiKit.scroll(tocPanel);
    private final JLabel tocTitle = UiKit.label("目录", Theme.uiFont(Font.BOLD, 12), Theme.muted());
    private final JPanel tocBox = new JPanel(new MigLayout("insets 0, fill", "[grow,fill]", "[]8[grow,fill]"));
    private final LinkHandler linkHandler;

    private Path file;
    private String query = "";
    private String anchor;
    private String syntheticText;
    private boolean tocVisible = true;
    private boolean hasToc;
    private int matchCount;

    MarkdownView(LinkHandler linkHandler) {
        super(new MigLayout("insets 0, fill, hidemode 3",
                "[grow,fill]6[1!]6[]", "[grow,fill]"));
        this.linkHandler = linkHandler;

        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet sheet = kit.getStyleSheet();
        editor.setEditorKit(kit);
        editor.setEditable(false);
        editor.setOpaque(true);
        editor.setBackground(Theme.bg());
        editor.setForeground(Theme.text());
        editor.setBorder(new EmptyBorder(8, 6, 8, 6));
        editor.addHyperlinkListener(this::onHyperlink);
        editor.setComponentPopupMenu(buildPopup());
        // 让 body 默认字体跟着系统走，避免部分环境中文回退成方框
        sheet.addRule("body { font-family: \"" + Theme.uiFont(Font.PLAIN, 13).getFamily() + "\"; }");

        JScrollPane scroll = UiKit.scroll(editor);
        scroll.setBorder(new EmptyBorder(6, 18, 14, 20));
        scroll.getViewport().setBackground(Theme.bg());

        tocTitle.setBorder(new EmptyBorder(14, 14, 4, 12));
        JButton closeToc = UiKit.iconButton("close", "隐藏目录", 12, () -> setTocVisible(false));
        JPanel tocHead = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", ""));
        tocHead.setOpaque(false);
        JLabel head = UiKit.label("本文目录", Theme.uiFont(Font.BOLD, 12), Theme.muted());
        head.setBorder(new EmptyBorder(14, 14, 6, 6));
        tocHead.add(head);
        tocHead.add(closeToc, "top, gapy 10");

        tocBox.setOpaque(false);
        tocBox.setBorder(new EmptyBorder(0, 0, 10, 8));
        tocBox.add(tocHead, "growx, wrap");
        tocBox.add(tocScroll, "grow, push");

        tocPanel.setOpaque(false);
        tocScroll.setOpaque(false);
        tocScroll.getViewport().setOpaque(false);

        JPanel divider = new JPanel();
        divider.setBackground(Theme.border());
        divider.setPreferredSize(new Dimension(1, 10));

        add(scroll, "cell 0 0, grow");
        add(divider, "cell 1 0, growy");
        add(tocBox, "cell 2 0, growy, width 220!");
        setBackground(Theme.bg());
    }

    // ===========================================================
    //  加载
    // ===========================================================

    void load(Path file, String query, String anchor) {
        this.file = file;
        this.query = query == null ? "" : query;
        this.anchor = anchor == null ? "" : anchor;
        this.syntheticText = null;
        render();
    }

    /** 渲染一段即时生成的 Markdown（项目概览页），基准目录用于解析相对链接。 */
    void loadText(Path baseFile, String markdown, String query, String anchor) {
        this.file = baseFile;
        this.query = query == null ? "" : query;
        this.anchor = anchor == null ? "" : anchor;
        this.syntheticText = markdown;
        render();
    }

    void reload() {
        if (file != null) render();
    }

    void clearHighlight() {
        query = "";
        editor.getHighlighter().removeAllHighlights();
        refreshEditorColors();
        matchCount = 0;
    }

    Path getFile() {
        return file;
    }

    int getMatchCount() {
        return matchCount;
    }

    void setTocVisible(boolean visible) {
        this.tocVisible = visible;
        tocBox.setVisible(visible);
        revalidate();
        repaint();
    }

    boolean isTocVisible() {
        return tocVisible;
    }

    /** 标题少于两个时没有目录可展示，外层据此隐藏目录按钮。 */
    boolean hasToc() {
        return hasToc;
    }

    private void render() {
        if (file == null) return;
        String text;
        if (syntheticText != null) {
            text = syntheticText;
        } else {
            try {
                text = FileTypes.readText(file, FileTypes.MAX_DOC_BYTES);
            } catch (IOException e) {
                editor.setContentType("text/html");
                editor.setText("<html><body style='font-family:Microsoft YaHei UI;font-size:12pt;color:#B42318'>"
                        + "无法读取文件：<br>" + UiKit.escapeHtml(file.toString()) + "</body></html>");
                return;
            }
        }

        int width = Math.max(640, editor.getWidth() - 90);
        MarkdownRenderer.RenderTheme theme = new MarkdownRenderer.RenderTheme(
                Theme.isDark(), 11, Math.min(width, 900));
        MarkdownRenderer.Rendered rendered = MarkdownRenderer.render(file, text, theme);

        refreshEditorColors();
        editor.setContentType("text/html");
        editor.setText(rendered.html());
        try {
            if (editor.getDocument() instanceof HTMLDocument htmlDocument) {
                htmlDocument.setBase(file.getParent().toUri().toURL());
            }
        } catch (Exception ignored) {
        }
        editor.setCaretPosition(0);

        buildToc(rendered.toc());
        matchCount = query.isBlank() ? 0 : highlight(query);

        SwingUtilities.invokeLater(() -> {
            if (!anchor.isBlank()) {
                scrollAfterLayout(() -> scrollToAnchor(anchor));
            } else if (matchCount > 0) {
                scrollAfterLayout(this::scrollToFirstHighlight);
            } else {
                editor.setCaretPosition(0);
            }
        });
    }

    /** 新建标签时 HTML 布局可能还没完成，稍后再滚一次，保证锚点 / 搜索命中能定位。 */
    private void scrollAfterLayout(Runnable action) {
        SwingUtilities.invokeLater(action);
        Timer retry = new Timer(180, e -> action.run());
        retry.setRepeats(false);
        retry.start();
    }

    private void refreshEditorColors() {
        editor.setBackground(Theme.bg());
        editor.setForeground(Theme.text());
        setBackground(Theme.bg());
        tocTitle.setForeground(Theme.muted());
    }

    // ===========================================================
    //  目录
    // ===========================================================

    private void buildToc(List<MarkdownRenderer.TocEntry> entries) {
        tocPanel.removeAll();
        List<MarkdownRenderer.TocEntry> visible = new ArrayList<>();
        for (MarkdownRenderer.TocEntry entry : entries) {
            if (entry.level() <= 4 && !entry.text().isBlank()) visible.add(entry);
        }
        if (visible.size() >= 2) {
            hasToc = true;
            for (MarkdownRenderer.TocEntry entry : visible) {
                tocPanel.add(tocButton(entry), "growx");
            }
            tocBox.setVisible(tocVisible);
        } else {
            hasToc = false;
            tocBox.setVisible(false);
        }
        tocPanel.revalidate();
        tocPanel.repaint();
    }

    private JButton tocButton(MarkdownRenderer.TocEntry entry) {
        int indent = 8 + Math.max(0, entry.level() - 1) * 10;
        String text = UiKit.ellipsis(entry.text(), entry.level() == 1 ? 24 : 27);
        JButton button = new JButton(UiKit.escapeHtml(text));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setBorder(new EmptyBorder(3, indent, 3, 8));
        button.setContentAreaFilled(false);
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(Theme.uiFont(entry.level() == 1 ? Font.BOLD : Font.PLAIN, entry.level() == 1 ? 12 : 11));
        button.setForeground(entry.level() == 1 ? Theme.text() : Theme.muted());
        button.setToolTipText(entry.text());
        button.addActionListener(e -> {
            editor.scrollToReference(entry.anchor());
            editor.requestFocusInWindow();
        });
        // 悬停时整行变浅色，像目录项而不是按钮
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                button.setContentAreaFilled(true);
                button.setBackground(Theme.hover());
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setContentAreaFilled(false);
            }
        });
        return button;
    }

    // ===========================================================
    //  搜索高亮
    // ===========================================================

    private int highlight(String query) {
        Highlighter highlighter = editor.getHighlighter();
        highlighter.removeAllHighlights();
        String docText;
        try {
            docText = editor.getDocument().getText(0, editor.getDocument().getLength());
        } catch (BadLocationException e) {
            return 0;
        }
        String lower = docText.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String term : query.trim().split("\\s+")) {
            String core = term.replace("*", "").replace("?", "").toLowerCase(Locale.ROOT);
            if (!core.isBlank()) terms.add(core);
        }
        if (terms.isEmpty()) return 0;

        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(
                alpha(Theme.highlight(), 150));
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
                if (count > 800) return count;
            }
        }
        return count;
    }

    private void scrollToFirstHighlight() {        Highlighter.Highlight[] highlights = editor.getHighlighter().getHighlights();
        if (highlights.length == 0) return;
        int pos = highlights[0].getStartOffset();
        try {
            Rectangle2D rect = editor.modelToView2D(pos);
            if (rect != null) {
                editor.scrollRectToVisible(rect.getBounds());
                editor.setCaretPosition(pos);
            }
        } catch (BadLocationException ignored) {
        }
    }

    /** 兼容 #安装 / #%E5%AE%89%E8%A3%85 / #Installation 等写法，逐个候选锚点尝试滚动。 */
    private void scrollToAnchor(String ref) {
        if (ref == null || ref.isBlank()) return;
        String decoded = ref;
        try {
            decoded = URLDecoder.decode(ref, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        List<String> candidates = new ArrayList<>(4);
        candidates.add(ref);
        candidates.add(decoded);
        candidates.add(decoded.toLowerCase(Locale.ROOT));
        candidates.add(decoded.toLowerCase(Locale.ROOT).replace(' ', '-'));
        candidates.add(decoded.replace(' ', '-'));
        for (String candidate : candidates) {
            if (candidate.isBlank()) continue;
            editor.scrollToReference(candidate);
        }
    }

    private static Color alpha(Color color, int a) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }

    // ===========================================================
    //  链接
    // ===========================================================

    private void onHyperlink(HyperlinkEvent event) {
        if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
        URL url = event.getURL();
        if (url == null) return;
        String scheme = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);

        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")) {
            UiKit.browse(url.toString());
            return;
        }
        if (!scheme.equals("file")) {
            return;
        }
        try {
            Path target = toPath(url);
            String ref = url.getRef();
            if (file != null && target.toAbsolutePath().normalize()
                    .equals(file.toAbsolutePath().normalize())) {
                if (ref != null && !ref.isBlank()) scrollToAnchor(ref);
                return;
            }
            if (!Files.exists(target)) {
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            if (FileTypes.isMarkdown(target) || FileTypes.isCode(target)) {
                linkHandler.openLocal(target, ref == null ? "" : ref);
            } else {
                UiKit.openExternally(target);
            }
        } catch (Exception ignored) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    /**
     * file URL → Path。片段里可能有未编码的中文（#安装），
     * 直接 url.toURI() 会抛 URISyntaxException，所以先去掉 # 片段。
     */
    private static Path toPath(URL url) throws Exception {
        String text = url.toString();
        int hash = text.indexOf('#');
        if (hash >= 0) text = text.substring(0, hash);
        try {
            return Path.of(URI.create(text));
        } catch (Exception e) {
            String path = url.getPath();
            if (path.length() > 2 && path.charAt(0) == '/'
                    && Character.isLetter(path.charAt(1)) && path.charAt(2) == ':') {
                path = path.substring(1);   // Windows: /C:/dir → C:/dir
            }
            return Path.of(URLDecoder.decode(path, StandardCharsets.UTF_8));
        }
    }

    private JPopupMenu buildPopup() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem("复制");
        copy.addActionListener(e -> editor.copy());
        JMenuItem selectAll = new JMenuItem("全选");
        selectAll.addActionListener(e -> editor.selectAll());
        JMenuItem copyAll = new JMenuItem("复制全文");
        copyAll.addActionListener(e -> {
            try {
                UiKit.copyToClipboard(editor.getDocument().getText(0, editor.getDocument().getLength()));
            } catch (BadLocationException ignored) {
            }
        });
        menu.add(copy);
        menu.add(selectAll);
        menu.addSeparator();
        menu.add(copyAll);
        return menu;
    }

    /** HTML 文本自己接管抗锯齿，避免 JEditorPane 在某些 LAF 下退回无 AA 的渲染。 */
    private static final class AntiAliasedHtmlPane extends JEditorPane {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, Theme.textAntialiasHint());
                g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                        RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                super.paintComponent(g2);
            } finally {
                g2.dispose();
            }
        }
    }
}
