package com.repoviewer.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.repoviewer.core.ProjectScanner;
import com.repoviewer.core.RepoProject;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Enumeration;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 左侧项目树：单选预览、双击固定打开、右键菜单、名称过滤。
 * <p>
 * 每个节点用两行渲染：项目名 + 相对路径 / 文档数。渲染器直接自绘，
 * 不再为每个可见行创建 HTML 文本；单击预览做了防抖，避免方向键快速切换时反复渲染 README。
 */
final class ProjectTreePanel extends JPanel {

    private final Consumer<RepoProject> onPreview;
    private final Consumer<RepoProject> onOpenPinned;
    private final Consumer<RepoProject> onOverview;

    private final JLabel titleLabel = UiKit.label("项目", Theme.uiFont(Font.BOLD, 13), Theme.text());
    private final JLabel countLabel = UiKit.label("", Theme.uiFont(Font.PLAIN, 11), Theme.muted());
    private final JTextField filterField = UiKit.searchField("筛选项目…");
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("尚未扫描")));
    private final JButton expandButton = UiKit.iconButton("chevron-down", "全部展开", 14, this::expandAllRequested);
    private final JButton collapseButton = UiKit.iconButton("chevron-right", "全部折叠", 14, this::collapseAll);
    private final JScrollPane treeScroll;
    private final Timer previewTimer;
    private final Timer filterTimer;

    private ProjectScanner.ScanResult scanResult;
    private RepoProject selected;
    private boolean rebuilding;

    ProjectTreePanel(Consumer<RepoProject> onPreview,
                     Consumer<RepoProject> onOpenPinned,
                     Consumer<RepoProject> onOverview) {
        super(new MigLayout("insets 12 10 10 10, fill", "[grow,fill]", "[]4[]8[grow,fill]"));
        this.onPreview = onPreview;
        this.onOpenPinned = onOpenPinned;
        this.onOverview = onOverview;

        setBackground(Theme.panel());

        tree.setBackground(Theme.panel());
        tree.setRowHeight(48);
        tree.setShowsRootHandles(true);
        tree.setRootVisible(true);
        tree.setLargeModel(true);
        tree.setToggleClickCount(2);
        tree.setCellRenderer(new ProjectCellRenderer());
        tree.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        previewTimer = new Timer(180, e -> {
            if (selected != null && onPreview != null) onPreview.accept(selected);
        });
        previewTimer.setRepeats(false);

        tree.addTreeSelectionListener(e -> {
            if (rebuilding) return;
            TreePath path = tree.getSelectionPath();
            if (path == null) return;
            Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (value instanceof RepoProject project) {
                selected = project;
                previewTimer.restart();
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path == null) return;
                    Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                    if (value instanceof RepoProject project && onOpenPinned != null) {
                        previewTimer.stop();
                        onOpenPinned.accept(project);
                    }
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "rv-open");
        tree.getActionMap().put("rv-open", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (selected != null && onOpenPinned != null) {
                    previewTimer.stop();
                    onOpenPinned.accept(selected);
                }
            }
        });
        tree.setComponentPopupMenu(buildContextMenu());

        treeScroll = UiKit.scroll(tree);
        treeScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        treeScroll.setBackground(Theme.panel());
        treeScroll.getViewport().setBackground(Theme.panel());

        filterField.putClientProperty(FlatClientProperties.STYLE, "arc: 9;");
        filterField.setMargin(new Insets(4, 6, 4, 6));
        filterField.setFont(Theme.uiFont(Font.PLAIN, 12));
        filterTimer = new Timer(140, e -> rebuildTree());
        filterTimer.setRepeats(false);
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filterTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { filterTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { filterTimer.restart(); }
        });

        buildUi();
    }

    private void buildUi() {
        removeAll();
        JPanel header = new JPanel(new MigLayout("insets 0, fillx", "[][grow,fill][]2[]", ""));
        header.setOpaque(false);
        header.add(titleLabel, "gapbottom 1");
        header.add(countLabel, "gapleft 8, gapbottom 1");
        header.add(collapseButton, "gapleft 2");
        header.add(expandButton);

        add(header, "growx, wrap");
        add(filterField, "growx, wrap");
        add(treeScroll, "grow, push");
        revalidate();
        repaint();
    }

    // ===========================================================
    //  数据
    // ===========================================================

    void setScanResult(ProjectScanner.ScanResult result) {
        previewTimer.stop();
        this.scanResult = result;
        this.selected = null;
        rebuildTree();
    }

    void clear() {
        previewTimer.stop();
        scanResult = null;
        selected = null;
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("尚未扫描")));
        countLabel.setText("");
    }

    RepoProject getSelectedProject() {
        return selected;
    }

    RepoProject getRootProject() {
        return scanResult == null ? null : scanResult.root();
    }

    void selectProject(RepoProject project) {
        if (project == null) return;
        selected = project;
        DefaultMutableTreeNode node = findNode(project);
        if (node == null && !filterField.getText().isBlank()) {
            // 搜索结果的所属项目可能被树过滤器排除，清空过滤后重新定位
            filterField.setText("");
            filterTimer.stop();
            rebuildTree();
            node = findNode(project);
        }
        if (node != null) {
            boolean wasRebuilding = rebuilding;
            rebuilding = true;
            try {
                TreePath path = new TreePath(node.getPath());
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
            } finally {
                rebuilding = wasRebuilding;
            }
        }
    }

    void focusFilter() {
        filterField.requestFocusInWindow();
        filterField.selectAll();
    }

    private DefaultMutableTreeNode findNode(RepoProject project) {
        Enumeration<?> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            Object next = nodes.nextElement();
            if (next instanceof DefaultMutableTreeNode node && node.getUserObject() == project) return node;
        }
        return null;
    }

    // ===========================================================
    //  过滤 / 建模
    // ===========================================================

    private void rebuildTree() {
        if (scanResult == null) return;
        rebuilding = true;
        try {
            String query = filterField.getText().trim().toLowerCase(Locale.ROOT);
            RepoProject root = scanResult.root();
            int total = scanResult.projects().size();
            int matched;

            DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(root);
            if (query.isEmpty()) {
                addAllChildren(rootNode, root);
                matched = total;
            } else {
                matched = addFilteredChildren(rootNode, root, query);
                if (matches(root, query)) matched++;
            }

            tree.setModel(new DefaultTreeModel(rootNode));
            tree.setRootVisible(true);
            expandInitial(matched);

            if (query.isEmpty()) {
                countLabel.setText(total + " 个项目");
            } else {
                countLabel.setText(matched + " / " + total + " 个项目");
            }

            if (selected != null) {
                DefaultMutableTreeNode node = findNode(selected);
                if (node != null) {
                    boolean wasRebuilding = rebuilding;
                    rebuilding = true;
                    try {
                        TreePath path = new TreePath(node.getPath());
                        tree.setSelectionPath(path);
                        tree.scrollPathToVisible(path);
                    } finally {
                        rebuilding = wasRebuilding;
                    }
                }
            }
        } finally {
            rebuilding = false;
        }
    }

    private void addAllChildren(DefaultMutableTreeNode node, RepoProject project) {
        for (RepoProject child : project.getChildren()) {
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
            node.add(childNode);
            addAllChildren(childNode, child);
        }
    }

    /** 过滤时一次自底向上遍历，避免每个节点重复计算子树命中数。 */
    private int addFilteredChildren(DefaultMutableTreeNode parent, RepoProject project, String query) {
        int matched = 0;
        for (RepoProject child : project.getChildren()) {
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
            int descendantMatches = addFilteredChildren(childNode, child, query);
            boolean selfMatches = matches(child, query);
            if (selfMatches || descendantMatches > 0) {
                parent.add(childNode);
                matched += descendantMatches + (selfMatches ? 1 : 0);
            }
        }
        return matched;
    }

    private boolean matches(RepoProject project, String query) {
        if (query.isEmpty()) return true;
        String name = project.getName().toLowerCase(Locale.ROOT);
        if (name.contains(query)) return true;
        String path = project.getRoot().toString().toLowerCase(Locale.ROOT);
        return path.contains(query);
    }

    /** 少量节点全部展开；项目很多时只展开根节点，避免一次渲染几千行导致卡顿。 */
    private void expandInitial(int matched) {
        if (matched <= 800) {
            expandAll();
            return;
        }
        if (tree.getRowCount() > 0) tree.expandRow(0);
    }

    private void expandAllRequested() {
        int total = scanResult == null ? 0 : scanResult.projects().size();
        if (total > 3000) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "当前有 " + total + " 个项目，全部展开可能需要几秒钟。\n是否继续？",
                    "全部展开", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }
        expandAll();
    }

    private void expandAll() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        if (root == null) return;
        Enumeration<?> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            Object next = nodes.nextElement();
            if (next instanceof DefaultMutableTreeNode node) {
                tree.expandPath(new TreePath(node.getPath()));
            }
        }
    }

    private void collapseAll() {
        for (int i = tree.getRowCount() - 1; i > 0; i--) tree.collapseRow(i);
    }

    // ===========================================================
    //  右键菜单
    // ===========================================================

    private JPopupMenu buildContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem open = new JMenuItem("打开 README");
        open.addActionListener(e -> {
            if (selected != null && onOpenPinned != null) {
                previewTimer.stop();
                onOpenPinned.accept(selected);
            }
        });
        JMenuItem overview = new JMenuItem("项目概览");
        overview.addActionListener(e -> {
            if (selected != null && onOverview != null) onOverview.accept(selected);
        });
        JMenuItem explorer = new JMenuItem("在文件管理器中打开");
        explorer.addActionListener(e -> {
            if (selected != null) UiKit.openInExplorer(selected.getRoot());
        });
        JMenuItem copy = new JMenuItem("复制项目路径");
        copy.addActionListener(e -> {
            if (selected != null) UiKit.copyToClipboard(selected.getRoot().toString());
        });
        menu.add(open);
        menu.add(overview);
        menu.addSeparator();
        menu.add(explorer);
        menu.add(copy);

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { showMenu(e); }

            @Override
            public void mouseReleased(MouseEvent e) { showMenu(e); }

            private void showMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                tree.setSelectionPath(path);
                previewTimer.stop();   // 右键只是为了弹菜单，不触发 README 预览
                menu.show(tree, e.getX(), e.getY());
            }
        });
        return menu;
    }

    // ===========================================================
    //  主题
    // ===========================================================

    void onThemeChanged() {
        setBackground(Theme.panel());
        tree.setBackground(Theme.panel());
        treeScroll.getViewport().setBackground(Theme.panel());
        titleLabel.setForeground(Theme.text());
        countLabel.setForeground(Theme.muted());
        filterField.setFont(Theme.uiFont(Font.PLAIN, 12));
        expandButton.setIcon(UiKit.icon("chevron-down", 14, Theme.muted()));
        collapseButton.setIcon(UiKit.icon("chevron-right", 14, Theme.muted()));
        tree.repaint();
    }

    // ===========================================================
    //  自绘渲染器（避免每行 HTML 解析）
    // ===========================================================

    private final class ProjectCellRenderer extends JComponent implements TreeCellRenderer {

        private final Font nameFont = Theme.uiFont(Font.BOLD, 13);
        private final Font pathFont = Theme.uiFont(Font.PLAIN, 11);
        private final Font badgeFont = Theme.uiFont(Font.PLAIN, 10);

        private String name = "";
        private String path = "";
        private String badge = "";
        private Icon icon;
        private boolean selectedRow;

        ProjectCellRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean isSelected,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            RepoProject project = value instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof RepoProject p ? p : null;
            selectedRow = isSelected;

            if (project == null) {
                name = String.valueOf(value);
                path = "";
                badge = "";
                icon = null;
                setToolTipText(null);
                return this;
            }

            boolean rootNode = project.getParent() == null;
            name = project.getName();
            String relative = rootNode ? "" : relative(project);
            String counts = project.getMarkdownFiles().size() + " 篇文档";
            if (!project.getCodeFiles().isEmpty()) {
                counts += " · " + project.getCodeFiles().size() + " 个代码文件";
            }
            if (rootNode) {
                int total = scanResult == null ? 1 : scanResult.projects().size();
                path = "根目录 · " + total + " 个项目";
            } else if (relative.equalsIgnoreCase(project.getName())) {
                path = counts;
            } else {
                path = relative + " · " + counts;
            }
            badge = project.isMarkerProject() ? "无 README" : "";
            icon = UiKit.icon(rootNode ? "folder-open" : "folder", 17,
                    project.hasReadme() || rootNode ? Theme.accent() : Theme.muted());
            setToolTipText("<html>" + UiKit.escapeHtml(project.getRoot().toString())
                    + "<br>" + project.getMarkdownFiles().size() + " 篇文档 · "
                    + project.getCodeFiles().size() + " 个代码文件"
                    + (project.isMarkerProject() ? "<br>该目录没有 README.md" : "") + "</html>");
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, Theme.textAntialiasHint());
                g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                        RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

                g2.setColor(selectedRow ? Theme.selection() : Theme.panel());
                g2.fillRect(0, 0, getWidth(), getHeight());

                int iconX = 4;
                int iconY = Math.max(2, (getHeight() - 17) / 2 - 2);
                if (icon != null) icon.paintIcon(this, g2, iconX, iconY);
                int textX = iconX + (icon == null ? 0 : 24);
                int available = Math.max(20, getWidth() - textX - 8);

                g2.setFont(nameFont);
                FontMetrics nameMetrics = g2.getFontMetrics();
                int badgeWidth = 0;
                if (!badge.isEmpty()) {
                    badgeWidth = g2.getFontMetrics(badgeFont).stringWidth(badge) + 8;
                }
                if (badgeWidth > 0 && available < badgeWidth + 40) {
                    badge = "";
                    badgeWidth = 0;
                }
                String displayName = clip(name, nameMetrics, available - badgeWidth);
                g2.setColor(Theme.text());
                g2.drawString(displayName, textX, 21);

                if (!badge.isEmpty()) {
                    g2.setFont(badgeFont);
                    g2.setColor(Theme.amber());
                    g2.drawString(badge, textX + nameMetrics.stringWidth(displayName) + 7, 21);
                }

                g2.setFont(pathFont);
                g2.setColor(Theme.muted());
                String displayPath = clip(path, g2.getFontMetrics(), available);
                g2.drawString(displayPath, textX, 39);
            } finally {
                g2.dispose();
            }
        }

        private String relative(RepoProject project) {
            RepoProject root = scanResult == null ? null : scanResult.root();
            if (root == null) return project.getRoot().toString();
            try {
                return root.getRoot().relativize(project.getRoot()).toString().replace('\\', '/');
            } catch (IllegalArgumentException e) {
                return project.getName();
            }
        }

        private String clip(String text, FontMetrics metrics, int width) {
            if (text == null) return "";
            if (width <= 0) return "";
            if (metrics.stringWidth(text) <= width) return text;
            String ellipsis = "…";
            int end = text.length();
            while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > width) {
                end--;
            }
            return text.substring(0, end) + ellipsis;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(220, 48);
        }
    }
}
