package com.repoviewer.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.repoviewer.core.FileTypes;
import com.repoviewer.core.RepoProject;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 右侧阅读区：项目上下文栏 + 标签页 + 空状态欢迎页。
 * <p>
 * 单击项目树打开的是“预览标签”：反复切换项目只占一个标签位；
 * 双击、搜索结果和文档内链接打开的是固定标签。这是 IDE 里常见的浏览体验，
 * 既能快速扫项目，又不会把标签栏塞满。
 */
final class DocumentViewer extends JPanel {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABS = "tabs";

    private final Function<Path, RepoProject> projectFinder;
    private final Runnable chooseDirectory;
    private final JTabbedPane tabs = new JTabbedPane();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JPanel contextBar = new JPanel(new MigLayout("insets 8 14 8 10, fillx", "[][grow][]", ""));
    private final Map<Component, TabEntry> entries = new LinkedHashMap<>();
    private final JPanel emptyPanel = new JPanel(new MigLayout("insets 0, fill", "[grow,center]", "[grow,center]"));

    private int previewIndex = -1;

    private static final class TabEntry {
        Path file;
        RepoProject project;
        boolean overview;
        boolean preview;
        JComponent view;
    }

    DocumentViewer(Function<Path, RepoProject> projectFinder, Runnable chooseDirectory) {
        super(new MigLayout("insets 0, fill", "[grow,fill]", "[]0[grow,fill]"));
        this.projectFinder = projectFinder;
        this.chooseDirectory = chooseDirectory;

        tabs.setBorder(null);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_TOOLTIPTEXT, "关闭");
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK,
                (BiConsumer<JTabbedPane, Integer>) (pane, index) -> closeTab(index));
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE,
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT, 36);
        tabs.addChangeListener(e -> {
            updateContext();
            updateTabIcons();
        });

        contextBar.setBackground(Theme.panel());
        contextBar.setBorder(new MatteBorder(0, 0, 1, 0, Theme.border()));

        populateEmptyPanel();

        cardPanel.add(emptyPanel, CARD_EMPTY);
        cardPanel.add(tabs, CARD_TABS);
        cards.show(cardPanel, CARD_EMPTY);

        add(contextBar, "growx, wrap, hidemode 3");
        add(cardPanel, "grow, push");
        contextBar.setVisible(false);
        setBackground(Theme.bg());
    }

    // ===========================================================
    //  打开文档
    // ===========================================================

    void openFile(Path file, String query, String anchor, boolean preview) {
        if (file == null) return;
        Path abs = file.toAbsolutePath().normalize();
        if (!FileTypes.isMarkdown(abs) && !FileTypes.isCode(abs)) {
            UiKit.openExternally(abs);
            return;
        }

        TabEntry existing = findByFile(abs);
        if (existing != null) {
            select(existing);
            if (!preview && existing.preview) {
                existing.preview = false;
                if (previewIndex >= 0 && entryAt(previewIndex) == existing) previewIndex = -1;
            }
            if (existing.view instanceof MarkdownView markdown) {
                markdown.load(existing.file, query, anchor);
            } else if (existing.view instanceof CodeView code) {
                code.load(existing.file, query);
            }
            updateContext();
            return;
        }

        TabEntry entry = new TabEntry();
        entry.file = abs;
        entry.project = projectFinder.apply(abs);
        entry.preview = preview;
        entry.view = createView(abs, query, anchor, entry);
        insert(entry, preview);
    }

    void openOverview(RepoProject project, boolean preview) {
        if (project == null) return;
        TabEntry existing = findOverview(project);
        if (existing != null) {
            select(existing);
            updateContext();
            return;
        }

        Path baseFile = project.getReadme() != null ? project.getReadme()
                : project.getRoot().resolve("README.md");
        TabEntry entry = new TabEntry();
        entry.project = project;
        entry.overview = true;
        entry.preview = preview;
        MarkdownView view = new MarkdownView(this::openLocalLink);
        view.loadText(baseFile, overviewMarkdown(project), "", "");
        entry.view = view;
        insert(entry, preview);
    }

    private JComponent createView(Path file, String query, String anchor, TabEntry entry) {
        if (FileTypes.isMarkdown(file)) {
            MarkdownView view = new MarkdownView(this::openLocalLink);
            view.load(file, query, anchor);
            return view;
        }
        CodeView view = new CodeView();
        view.load(file, query);
        return view;
    }

    /** preview 为 true 时复用唯一的预览标签，否则在最右侧新增一个固定标签。 */
    private void insert(TabEntry entry, boolean preview) {
        boolean reuse = preview && previewIndex >= 0 && previewIndex < tabs.getTabCount();
        if (reuse) {
            Component old = tabs.getComponentAt(previewIndex);
            entries.remove(old);
            entries.put(entry.view, entry);
            tabs.setComponentAt(previewIndex, entry.view);
            tabs.setTitleAt(previewIndex, titleFor(entry));
            tabs.setToolTipTextAt(previewIndex, tooltipFor(entry));
            tabs.setIconAt(previewIndex, iconFor(entry));
            tabs.setSelectedIndex(previewIndex);
            updateContext();
            return;
        }

        int index = tabs.getTabCount();
        entries.put(entry.view, entry);
        tabs.addTab(titleFor(entry), iconFor(entry), entry.view, tooltipFor(entry));
        if (preview) previewIndex = index;
        tabs.setSelectedIndex(index);
        showTabs();
        updateContext();
    }

    void closeAll() {
        entries.clear();
        tabs.removeAll();
        previewIndex = -1;
        contextBar.setVisible(false);
        cards.show(cardPanel, CARD_EMPTY);
    }

    RepoProject selectedProject() {
        TabEntry entry = currentEntry();
        return entry == null ? null : entry.project;
    }

    Path selectedFile() {
        TabEntry entry = currentEntry();
        return entry == null ? null : entry.file;
    }

    boolean hasTabs() {
        return tabs.getTabCount() > 0;
    }

    void closeCurrentTab() {
        if (tabs.getTabCount() > 0) closeTab(tabs.getSelectedIndex());
    }

    // ===========================================================
    //  主题
    // ===========================================================

    void onThemeChanged() {
        contextBar.setBackground(Theme.panel());
        contextBar.setBorder(new MatteBorder(0, 0, 1, 0, Theme.border()));
        setBackground(Theme.bg());
        populateEmptyPanel();
        for (TabEntry entry : entries.values()) {
            if (entry.view instanceof MarkdownView markdown) markdown.reload();
            if (entry.view instanceof CodeView code) code.reload();
        }
        updateTabIcons();
        updateContext();
    }

    // ===========================================================
    //  标签维护
    // ===========================================================

    private void select(TabEntry entry) {
        if (entry.view != null) tabs.setSelectedComponent(entry.view);
        showTabs();
    }

    private TabEntry entryAt(int index) {
        if (index < 0 || index >= tabs.getTabCount()) return null;
        return entries.get(tabs.getComponentAt(index));
    }

    private TabEntry findByFile(Path file) {
        for (TabEntry entry : entries.values()) {
            if (!entry.overview && entry.file != null && entry.file.equals(file)) return entry;
        }
        return null;
    }

    private TabEntry findOverview(RepoProject project) {
        for (TabEntry entry : entries.values()) {
            if (entry.overview && entry.project == project) return entry;
        }
        return null;
    }

    private TabEntry currentEntry() {
        Component component = tabs.getSelectedComponent();
        return component == null ? null : entries.get(component);
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.getTabCount()) return;
        Component component = tabs.getComponentAt(index);
        entries.remove(component);
        tabs.removeTabAt(index);
        if (previewIndex == index) previewIndex = -1;
        else if (previewIndex > index) previewIndex--;

        if (tabs.getTabCount() == 0) {
            cards.show(cardPanel, CARD_EMPTY);
            contextBar.setVisible(false);
        }
        updateContext();
    }

    private String titleFor(TabEntry entry) {
        if (entry.overview) return UiKit.ellipsis(entry.project.getName() + " · 概览", 26);
        return entry.file == null ? "文档" : entry.file.getFileName().toString();
    }

    private String tooltipFor(TabEntry entry) {
        if (entry.overview) return entry.project.getRoot().toString();
        return entry.file == null ? "" : entry.file.toString();
    }

    private Icon iconFor(TabEntry entry) {
        if (entry.overview) return UiKit.icon("info", 14, Theme.muted());
        boolean markdown = entry.file != null && FileTypes.isMarkdown(entry.file);
        return UiKit.icon(markdown ? "doc" : "code", 14, markdown ? Theme.accent() : Theme.muted());
    }

    private void updateTabIcons() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            TabEntry entry = entryAt(i);
            if (entry != null) tabs.setIconAt(i, iconFor(entry));
        }
    }

    private void showTabs() {
        if (tabs.getTabCount() > 0) {
            cards.show(cardPanel, CARD_TABS);
            contextBar.setVisible(true);
        }
    }

    private void openLocalLink(Path path, String anchor) {
        openFile(path, "", anchor, false);
    }

    // ===========================================================
    //  上下文栏
    // ===========================================================

    private void updateContext() {
        contextBar.removeAll();
        TabEntry entry = currentEntry();
        if (entry == null) {
            contextBar.setVisible(false);
            contextBar.revalidate();
            contextBar.repaint();
            return;
        }
        contextBar.setVisible(true);

        RepoProject project = entry.project;
        String projectName = project != null ? project.getName() : "外部文件";
        String relative;
        if (entry.overview) {
            relative = "概览";
        } else if (project != null && entry.file != null) {
            try {
                relative = project.getRoot().relativize(entry.file).toString().replace('\\', '/');
            } catch (IllegalArgumentException e) {
                relative = entry.file.getFileName().toString();
            }
        } else {
            relative = entry.file == null ? "" : entry.file.getFileName().toString();
        }

        JLabel projectLabel = UiKit.label(projectName, Theme.uiFont(Font.BOLD, 13), Theme.text());
        projectLabel.setIcon(UiKit.icon("folder", 15, Theme.accent()));
        projectLabel.setIconTextGap(7);
        if (project != null) projectLabel.setToolTipText(project.getRoot().toString());

        JLabel pathLabel = UiKit.label(relative, Theme.uiFont(Font.PLAIN, 12), Theme.muted());
        if (entry.file != null) pathLabel.setToolTipText(entry.file.toString());

        contextBar.add(projectLabel);
        contextBar.add(pathLabel, "gapleft 8, growx");

        int matches = 0;
        if (entry.view instanceof MarkdownView markdown) matches = markdown.getMatchCount();
        if (entry.view instanceof CodeView code) matches = code.getMatchCount();
        if (matches > 0) {
            contextBar.add(UiKit.label(matches + " 处匹配", Theme.uiFont(Font.PLAIN, 11), Theme.amber()),
                    "gapright 8");
        }

        if (entry.view instanceof MarkdownView markdown && markdown.hasToc()) {
            JButton toc = UiKit.iconButton("list", markdown.isTocVisible() ? "隐藏目录" : "显示目录", 15,
                    () -> {
                        markdown.setTocVisible(!markdown.isTocVisible());
                        updateContext();
                    });
            contextBar.add(toc);
        }

        if (entry.overview && project != null && project.hasReadme()) {
            JButton openReadme = UiKit.iconButton("doc", "打开 README", 15,
                    () -> openFile(project.getReadme(), "", "", false));
            contextBar.add(openReadme);
        }

        Path target = entry.overview && project != null ? project.getRoot() : entry.file;
        if (target != null) {
            Path dir = Files.isDirectory(target) ? target : target.getParent();
            Path openTarget = dir == null ? target : dir;
            JButton explorer = UiKit.iconButton("external", "在文件管理器中打开", 15,
                    () -> UiKit.openInExplorer(openTarget));
            JButton copy = UiKit.iconButton("copy", "复制路径", 15,
                    () -> UiKit.copyToClipboard(target.toString()));
            contextBar.add(explorer);
            contextBar.add(copy);
        }

        contextBar.revalidate();
        contextBar.repaint();
    }

    // ===========================================================
    //  欢迎页
    // ===========================================================

    private void populateEmptyPanel() {
        emptyPanel.removeAll();
        emptyPanel.setBackground(Theme.bg());

        JPanel inner = new JPanel(new MigLayout("insets 20, alignx center, wrap 1", "[center]", "[]10[]6[]18[]10[]6[]"));
        inner.setOpaque(false);

        JLabel logo = new JLabel(new ImageIcon(UiKit.appImage(84)));
        inner.add(logo, "alignx center");

        JLabel title = UiKit.label("RepoViewer", Theme.uiFont(Font.BOLD, 30), Theme.text());
        inner.add(title, "alignx center");

        JLabel slogan = UiKit.label("把散落在本地的仓库，变成一座可以随手翻阅的书架",
                Theme.uiFont(Font.PLAIN, 14), Theme.muted());
        inner.add(slogan, "alignx center");

        JLabel steps = UiKit.htmlLabel(
                "<html><div style='text-align:center;color:" + hex(Theme.muted()) + ";'>"
                        + "选择一个目录，自动发现其中的 README 与嵌套项目<br>"
                        + "像阅读 GitHub 一样翻文档，也可以直接搜索文档和代码内容"
                        + "</div></html>",
                Theme.uiFont(Font.PLAIN, 12), Theme.muted());
        inner.add(steps, "alignx center, gaptop 6");

        JButton choose = UiKit.primaryButton("选择项目目录", "folder-open", chooseDirectory);
        choose.setFont(Theme.uiFont(Font.BOLD, 13));
        inner.add(choose, "alignx center, gaptop 6, width 180!");

        JLabel tips = UiKit.label("支持嵌套项目 · 完全离线 · 索引自动缓存",
                Theme.uiFont(Font.PLAIN, 11), Theme.muted());
        inner.add(tips, "alignx center, gaptop 8");

        emptyPanel.add(inner);
        emptyPanel.revalidate();
        emptyPanel.repaint();
    }

    private static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    // ===========================================================
    //  项目概览的 Markdown 生成
    // ===========================================================

    private String overviewMarkdown(RepoProject project) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("# ").append(project.getTitle()).append("\n\n");
        if (!project.getTitle().equals(project.getName())) {
            sb.append("**目录名**：`").append(project.getName()).append("`  \n");
        }
        sb.append("**路径**：`").append(project.getRoot()).append("`  \n");
        sb.append("**文档** ").append(project.getMarkdownFiles().size())
                .append(" 篇 · **代码文件** ").append(project.getCodeFiles().size()).append(" 个");
        if (project.getLastModified() > 0) {
            LocalDate date = Instant.ofEpochMilli(project.getLastModified())
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            sb.append(" · **最近更新** ").append(date);
        }
        sb.append("\n\n");

        String summary = project.getSummary();
        if (summary != null && !summary.isBlank()) {
            sb.append(summary).append("\n\n");
        }
        if (!project.hasReadme()) {
            sb.append("> 该目录没有 README.md，下面是扫描到的文档与子项目。\n\n");
        }

        List<Path> docs = project.getMarkdownFiles();
        if (!docs.isEmpty()) {
            sb.append("## 文档\n\n");
            int shown = 0;
            for (Path doc : docs) {
                if (shown++ >= 40) {
                    sb.append("- 其余 ").append(docs.size() - 40).append(" 篇文档请从左侧目录打开\n");
                    break;
                }
                sb.append("- [").append(relative(project.getRoot(), doc)).append("](")
                        .append(doc.toUri()).append(")\n");
            }
            sb.append('\n');
        } else {
            sb.append("## 文档\n\n没有找到 Markdown 文档。\n\n");
        }

        List<RepoProject> children = project.getChildren();
        if (!children.isEmpty()) {
            sb.append("## 子项目\n\n");
            for (RepoProject child : children) {
                String rel = relative(project.getRoot(), child.getRoot());
                String suffix = rel.equalsIgnoreCase(child.getName()) ? "" : "  · `" + rel + "`";
                if (child.hasReadme()) {
                    sb.append("- [").append(child.getName()).append("](")
                            .append(child.getReadme().toUri()).append(")").append(suffix).append('\n');
                } else {
                    sb.append("- **").append(child.getName()).append("**").append(suffix)
                            .append("（无 README）\n");
                }
            }
            sb.append('\n');
        }

        if (!project.getCodeFiles().isEmpty()) {
            sb.append("## 代码\n\n共 ").append(project.getCodeFiles().size())
                    .append(" 个代码文件，可在顶部搜索框把范围切到「代码」进行检索。\n");
        }
        return sb.toString();
    }

    private static String relative(Path base, Path file) {
        try {
            return base.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.getFileName() == null ? file.toString() : file.getFileName().toString();
        }
    }
}
