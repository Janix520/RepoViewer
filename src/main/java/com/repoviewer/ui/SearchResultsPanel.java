package com.repoviewer.ui;

import com.repoviewer.core.RepoIndex;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 底部搜索结果面板：文件名 + 相对路径 + 内容片段。
 * <p>
 * 顶部带“按项目”下拉框，列出本次搜索命中的所有项目及数量（不受结果条数截断），
 * 选中后只显示该项目的文件，便于根据内容快速判断是哪个项目。
 * 单击预览、双击固定打开，和项目树的交互保持一致。
 */
final class SearchResultsPanel extends JPanel {

    private final JList<RepoIndex.Hit> list = new JList<>();
    private final DefaultListModel<RepoIndex.Hit> model = new DefaultListModel<>();
    private final JLabel titleLabel = UiKit.label("搜索结果", Theme.uiFont(Font.BOLD, 13), Theme.text());
    private final JLabel metaLabel = UiKit.label("", Theme.uiFont(Font.PLAIN, 11), Theme.muted());
    private final JLabel emptyLabel = UiKit.label("没有找到匹配项", Theme.uiFont(Font.PLAIN, 13), Theme.muted());
    private final JComboBox<ProjectOption> projectCombo = new JComboBox<>();
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel body = new JPanel(bodyCards);

    private final Consumer<RepoIndex.Hit> onPreview;
    private final Consumer<RepoIndex.Hit> onOpenPinned;
    private final Consumer<String> onProjectSelected;
    private final Runnable onClose;

    private List<RepoIndex.Hit> allHits = List.of();
    private List<RepoIndex.ProjectCount> allProjects = List.of();
    private long lastElapsedMs;
    private long lastTotalHits;
    private boolean suppressSelection;
    private boolean updatingProjectFilter;

    private record ProjectOption(String path, String label, int count) {
        @Override
        public String toString() {
            return label;
        }
    }

    SearchResultsPanel(Consumer<RepoIndex.Hit> onPreview,
                       Consumer<RepoIndex.Hit> onOpenPinned,
                       Consumer<String> onProjectSelected,
                       Runnable onClose) {
        super(new MigLayout("insets 0, fill", "[grow,fill]", "[]0[grow,fill]"));
        this.onPreview = onPreview;
        this.onOpenPinned = onOpenPinned;
        this.onProjectSelected = onProjectSelected;
        this.onClose = onClose;

        setBackground(Theme.surface());
        setBorder(new MatteBorder(1, 0, 0, 0, Theme.border()));

        JPanel header = new JPanel(new MigLayout("insets 7 12 7 8, fillx, hidemode 3",
                "[][grow,fill][][]", ""));
        header.setOpaque(false);
        header.add(titleLabel);
        header.add(metaLabel, "gapleft 8");

        projectCombo.setFont(Theme.uiFont(Font.PLAIN, 12));
        projectCombo.setMaximumRowCount(18);
        projectCombo.setVisible(false);
        projectCombo.setToolTipText("按项目筛选本次搜索结果");
        projectCombo.addActionListener(e -> {
            if (updatingProjectFilter) return;
            applyProjectFilter();
            ProjectOption option = (ProjectOption) projectCombo.getSelectedItem();
            if (option != null && option.path() != null && onProjectSelected != null) {
                onProjectSelected.accept(option.path());
            }
        });
        header.add(projectCombo, "gapleft 8, align right, width 200!");

        JButton close = UiKit.iconButton("close", "关闭搜索结果", 13, () -> {
            if (onClose != null) onClose.run();
        });
        header.add(close, "align right");

        list.setModel(model);
        list.setCellRenderer(new HitRenderer());
        list.setFixedCellHeight(80);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.surface());
        list.setBorder(null);
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || suppressSelection) return;
            RepoIndex.Hit hit = list.getSelectedValue();
            if (hit != null && onPreview != null) onPreview.accept(hit);
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    RepoIndex.Hit hit = list.getSelectedValue();
                    if (hit != null && onOpenPinned != null) onOpenPinned.accept(hit);
                }
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "rv-open-hit");
        list.getActionMap().put("rv-open-hit", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                RepoIndex.Hit hit = list.getSelectedValue();
                if (hit != null && onOpenPinned != null) onOpenPinned.accept(hit);
            }
        });

        JScrollPane scroll = UiKit.scroll(list);
        scroll.setBackground(Theme.surface());

        JPanel empty = new JPanel(new MigLayout("insets 20, fill, align center", "[center]", "[center]"));
        empty.setOpaque(false);
        empty.add(emptyLabel);
        body.add(scroll, "list");
        body.add(empty, "empty");

        add(header, "growx, wrap");
        add(body, "grow, push");
    }

    // ===========================================================
    //  结果展示 / 项目筛选
    // ===========================================================

    void showResults(RepoIndex.Outcome outcome) {
        allHits = outcome.hits() == null ? List.of() : outcome.hits();
        allProjects = outcome.projects() == null ? List.of() : outcome.projects();
        lastElapsedMs = outcome.elapsedMs();
        lastTotalHits = outcome.totalHits();
        emptyLabel.setText("没有找到匹配项");

        populateProjectCombo();
        applyProjectFilter();
    }

    void setSearching() {
        metaLabel.setText("搜索中…");
        projectCombo.setVisible(false);
    }

    void showMessage(String message) {
        allHits = List.of();
        allProjects = List.of();
        suppressSelection = true;
        try {
            model.clear();
            metaLabel.setText("");
            emptyLabel.setText(message);
            projectCombo.setVisible(false);
            bodyCards.show(body, "empty");
        } finally {
            suppressSelection = false;
        }
    }

    void clearSelection() {
        suppressSelection = true;
        list.clearSelection();
        suppressSelection = false;
    }

    void onThemeChanged() {
        setBackground(Theme.surface());
        setBorder(new MatteBorder(1, 0, 0, 0, Theme.border()));
        titleLabel.setForeground(Theme.text());
        metaLabel.setForeground(Theme.muted());
        emptyLabel.setForeground(Theme.muted());
        projectCombo.setFont(Theme.uiFont(Font.PLAIN, 12));
        list.setBackground(Theme.surface());
        list.repaint();
    }

    private void populateProjectCombo() {
        updatingProjectFilter = true;
        try {
            DefaultComboBoxModel<ProjectOption> options = new DefaultComboBoxModel<>();
            options.addElement(new ProjectOption(null, "全部项目（" + allProjects.size() + "）", -1));
            for (RepoIndex.ProjectCount project : allProjects) {
                String name = projectName(project.projectPath());
                options.addElement(new ProjectOption(project.projectPath(),
                        name + "（" + project.count() + "）", project.count()));
            }
            projectCombo.setModel(options);
            projectCombo.setSelectedIndex(0);
            projectCombo.setVisible(allProjects.size() > 1 && !allHits.isEmpty());
        } finally {
            updatingProjectFilter = false;
        }
    }

    private void applyProjectFilter() {
        ProjectOption option = (ProjectOption) projectCombo.getSelectedItem();
        String path = option == null ? null : option.path();
        List<RepoIndex.Hit> filtered = path == null ? allHits
                : allHits.stream().filter(h -> Objects.equals(path, h.projectPath())).toList();

        suppressSelection = true;
        try {
            model.clear();
            for (RepoIndex.Hit hit : filtered) model.addElement(hit);
            list.clearSelection();
            list.setSelectedIndex(-1);

            if (filtered.isEmpty()) {
                if (path != null) {
                    emptyLabel.setText("该项目的命中结果不在当前显示的前 " + allHits.size()
                            + " 条中，可继续缩小查询词");
                } else {
                    emptyLabel.setText("没有找到匹配项");
                }
                bodyCards.show(body, "empty");
            } else {
                bodyCards.show(body, "list");
                list.ensureIndexIsVisible(0);
            }

            metaLabel.setText(buildMeta(option, filtered.size()));
            projectCombo.setToolTipText(path == null ? "按项目筛选本次搜索结果" : path);
        } finally {
            suppressSelection = false;
        }
    }

    private String buildMeta(ProjectOption option, int shown) {
        StringBuilder sb = new StringBuilder();
        if (option != null && option.path() != null) {
            sb.append(projectName(option.path()))
                    .append(" · 显示 ").append(shown)
                    .append(" / 共 ").append(option.count()).append(" 条");
        } else {
            if (lastTotalHits > allHits.size()) {
                sb.append("显示前 ").append(allHits.size())
                        .append(" / 共 ").append(lastTotalHits).append(" 条");
            } else {
                sb.append(allHits.size()).append(" 条结果");
            }
            if (!allProjects.isEmpty()) {
                sb.append(" · ").append(allProjects.size()).append(" 个项目");
            }
        }
        sb.append(" · ").append(lastElapsedMs).append(" ms");
        return sb.toString();
    }

    private static String projectName(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) return "";
        Path path = Path.of(projectPath);
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    // ===========================================================
    //  行渲染
    // ===========================================================

    private final class HitRenderer extends JPanel implements ListCellRenderer<RepoIndex.Hit> {
        private final JLabel name = new JLabel();
        private final JLabel project = new JLabel();
        private final JLabel pathLabel = new JLabel();
        private final JLabel snippet = new JLabel();
        private final JLabel icon = new JLabel();

        HitRenderer() {
            super(new MigLayout("insets 7 12 7 12, fillx", "[]8[grow,fill][]", "[]1[]1[]"));
            setOpaque(true);
            name.setFont(Theme.uiFont(Font.PLAIN, 13));
            project.setFont(Theme.uiFont(Font.PLAIN, 11));
            pathLabel.setFont(Theme.uiFont(Font.PLAIN, 11));
            snippet.setFont(Theme.uiFont(Font.PLAIN, 12));
            add(icon, "cell 0 0 1 3, aligny top, gaptop 2");
            add(name, "cell 1 0, growx");
            add(project, "cell 2 0, align right, gapleft 10");
            add(pathLabel, "cell 1 1 2 1, growx");
            add(snippet, "cell 1 2 2 1, growx, gaptop 1");
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends RepoIndex.Hit> list, RepoIndex.Hit hit,
                                                      int index, boolean isSelected, boolean hasFocus) {
            name.setText(UiKit.ellipsis(hit.name(), 40));
            name.setForeground(Theme.text());
            name.setFont(Theme.uiFont(Font.BOLD, 13));

            project.setText(projectName(hit.projectPath()));
            project.setForeground(Theme.muted());
            project.setFont(Theme.uiFont(Font.PLAIN, 11));

            String rel = relative(hit);
            if (hit.line() > 0) rel = "第 " + hit.line() + " 行 · " + rel;
            pathLabel.setText("<html><font color=\"" + hex(Theme.muted()) + "\">"
                    + UiKit.escapeHtml(UiKit.ellipsis(rel, 90)) + "</font></html>");

            String html = hit.snippet() == null || hit.snippet().isBlank()
                    ? "" : "<html><body>" + hit.snippet() + "</body></html>";
            snippet.setText(html);
            snippet.setForeground(Theme.muted());

            icon.setIcon(UiKit.icon(hit.markdown() ? "doc" : "code", 15,
                    hit.markdown() ? Theme.accent() : Theme.muted()));

            setBackground(isSelected ? Theme.selection() : Theme.surface());
            setBorder(new MatteBorder(0, 0, 1, 0, Theme.border()));
            return this;
        }

        private String relative(RepoIndex.Hit hit) {
            try {
                if (hit.projectPath() == null) return hit.name();
                return Path.of(hit.projectPath()).relativize(hit.file()).toString().replace('\\', '/');
            } catch (Exception e) {
                return hit.name();
            }
        }

        private String hex(Color color) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        }
    }
}
