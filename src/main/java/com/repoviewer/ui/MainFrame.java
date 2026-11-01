package com.repoviewer.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.repoviewer.AppSettings;
import com.repoviewer.core.ProjectScanner;
import com.repoviewer.core.RepoIndex;
import com.repoviewer.core.RepoProject;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主窗口：扫描目录 → 项目树 / 阅读区 / 全文搜索。
 * <p>
 * 线程约定：所有 Swing 状态只在 EDT 上改；扫描与索引在 SwingWorker 里跑，
 * 搜索在单线程执行器里跑，用递增的 searchSeq 丢弃过期结果。
 */
public final class MainFrame extends JFrame {

    private static final int MAX_RESULTS = 1000;
    private static final int SEARCH_DEBOUNCE_MS = 160;

    private final JPanel toolbar;
    private final JLabel rootLabel = new JLabel("尚未选择目录");
    private final JButton chooseButton;
    private final JButton refreshButton;
    private final JButton themeButton;
    private final JButton settingsButton;
    private final JButton cancelButton;
    private final JTextField searchField;
    private final JComboBox<String> scopeCombo;
    private final JToggleButton onlyProject;
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel("就绪");
    private final JLabel indexLabel = new JLabel("");
    private final JPanel statusBar;
    private final JPanel topRow;
    private final JSplitPane mainSplit;
    private final JSplitPane resultSplit;

    private final ProjectTreePanel treePanel;
    private final DocumentViewer documentViewer;
    private final SearchResultsPanel resultsPanel;
    private final Timer searchTimer;
    private final ExecutorService searchExecutor;

    private volatile RepoIndex repoIndex;
    private volatile AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private ProjectScanner.ScanResult scanResult;
    private Path currentRoot;
    private SwingWorker<ScanPayload, String> worker;
    private int searchSeq;
    private String lastExecutedQuery = "";
    private boolean busy;
    private boolean closing;
    private boolean updatingScope;

    private record ScanPayload(ProjectScanner.ScanResult scan, RepoIndex.SyncResult sync,
                               int docCount, long elapsedMs) {
    }

    public MainFrame() {
        super("RepoViewer");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 660));
        restoreWindowBounds();
        setIconImage(UiKit.appImage(64));
        getContentPane().setBackground(Theme.bg());
        setLayout(new MigLayout("insets 0, fill", "[grow,fill]", "[]0[grow,fill][]"));

        treePanel = new ProjectTreePanel(this::previewProject, this::openProjectPinned, this::openProjectOverview);
        documentViewer = new DocumentViewer(this::findProject, this::chooseDirectory);
        resultsPanel = new SearchResultsPanel(this::previewHit, this::openHitPinned,
                this::focusProjectByPath, this::closeSearch);

        searchTimer = new Timer(SEARCH_DEBOUNCE_MS, e -> runSearch());
        searchTimer.setRepeats(false);
        searchExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "repoviewer-search");
            thread.setDaemon(true);
            return thread;
        });

        chooseButton = UiKit.primaryButton("选择目录", "folder-open", this::chooseDirectory);
        chooseButton.setFont(Theme.uiFont(Font.BOLD, 13));
        refreshButton = UiKit.iconButton("refresh", "重新扫描 (F5)", 16, this::rescan);
        themeButton = UiKit.iconButton(Theme.isDark() ? "sun" : "moon",
                Theme.isDark() ? "切换到浅色" : "切换到深色", 16, this::toggleTheme);
        settingsButton = UiKit.iconButton("settings", "设置 (Ctrl+,)", 16, this::openSettings);

        rootLabel.setFont(Theme.uiFont(Font.PLAIN, 12));
        rootLabel.setForeground(Theme.muted());
        rootLabel.setIcon(UiKit.icon("folder", 15, Theme.muted()));
        rootLabel.setIconTextGap(7);
        UiKit.hand(rootLabel);
        rootLabel.setToolTipText("在文件管理器中打开当前目录");
        rootLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (currentRoot != null) UiKit.openInExplorer(currentRoot);
            }
        });

        searchField = UiKit.searchField("搜索 README、文档与代码内容…  (Ctrl+F)");
        searchField.setFont(Theme.uiFont(Font.PLAIN, 13));
        searchField.setEnabled(false);
        installSearchListeners();

        scopeCombo = new JComboBox<>(new String[]{"全部", "Markdown", "代码"});
        scopeCombo.setFont(Theme.uiFont(Font.PLAIN, 12));
        scopeCombo.setToolTipText("搜索范围");
        scopeCombo.setMaximumRowCount(3);
        scopeCombo.setEnabled(false);
        scopeCombo.addActionListener(e -> {
            if (!updatingScope && !searchField.getText().isBlank()) runSearch();
        });

        onlyProject = new JToggleButton("仅当前项目");
        onlyProject.setFont(Theme.uiFont(Font.PLAIN, 12));
        onlyProject.setIcon(UiKit.icon("folder", 14, Theme.muted()));
        onlyProject.setIconTextGap(5);
        onlyProject.setFocusable(false);
        onlyProject.setToolTipText("只搜索左侧选中的项目（包含子项目）");
        onlyProject.setEnabled(false);
        onlyProject.putClientProperty(FlatClientProperties.STYLE, "arc: 9;");
        onlyProject.addActionListener(e -> {
            if (onlyProject.isSelected() && treePanel.getSelectedProject() == null) {
                statusLabel.setText("先在左侧选择一个项目，再开启「仅当前项目」");
            }
            if (!searchField.getText().isBlank()) runSearch();
        });

        topRow = new JPanel(new MigLayout("insets 0, fillx", "[]12[grow,fill]4[]4[]4[]", ""));
        topRow.setOpaque(false);
        topRow.add(chooseButton);
        topRow.add(rootLabel, "growx");
        topRow.add(refreshButton);
        topRow.add(settingsButton);
        topRow.add(themeButton);

        JPanel searchRow = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[136!]8[]", ""));
        searchRow.setOpaque(false);
        searchRow.add(searchField, "growx, height 34!");
        searchRow.add(scopeCombo, "height 34!");
        searchRow.add(onlyProject, "height 34!, width 118!");

        toolbar = new JPanel(new MigLayout("insets 12 14 9 14, fillx", "[grow,fill]", "[]8[]"));
        toolbar.setBackground(Theme.bg());
        toolbar.setBorder(new MatteBorder(0, 0, 1, 0, Theme.border()));
        toolbar.add(topRow, "growx, wrap");
        toolbar.add(searchRow, "growx");

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(110, 6));
        progressBar.putClientProperty(FlatClientProperties.STYLE, "arc: 4;");
        cancelButton = UiKit.toolButton("停止", "close", "取消扫描", () -> {
            cancelFlag.set(true);
            statusLabel.setText("正在取消…");
            if (worker != null) worker.cancel(true);
        });
        cancelButton.setVisible(false);

        statusLabel.setFont(Theme.uiFont(Font.PLAIN, 11));
        statusLabel.setForeground(Theme.muted());
        indexLabel.setFont(Theme.uiFont(Font.PLAIN, 11));
        indexLabel.setForeground(Theme.muted());

        statusBar = new JPanel(new MigLayout("insets 5 14, fillx", "[grow,fill]8[]8[]8[]", ""));
        statusBar.setBackground(Theme.panel());
        statusBar.setBorder(new MatteBorder(1, 0, 0, 0, Theme.border()));
        statusBar.add(statusLabel, "growx");
        statusBar.add(progressBar);
        statusBar.add(cancelButton);
        statusBar.add(indexLabel);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, documentViewer);
        mainSplit.setBorder(null);
        mainSplit.setDividerSize(7);
        mainSplit.setResizeWeight(0.22);
        mainSplit.setDividerLocation(AppSettings.divider());

        resultsPanel.setVisible(false);
        resultsPanel.setPreferredSize(new Dimension(0, 260));
        resultSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplit, resultsPanel);
        resultSplit.setBorder(null);
        resultSplit.setDividerSize(7);
        resultSplit.setResizeWeight(1.0);

        add(toolbar, "growx, wrap");
        add(resultSplit, "grow, push, wrap");
        add(statusBar, "growx");

        bindShortcuts();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        setBusy(false, "就绪 · 选择一个目录开始浏览");
        refreshScopeOptions();
        SwingUtilities.invokeLater(() -> mainSplit.setDividerLocation(AppSettings.divider()));
    }

    // ===========================================================
    //  扫描
    // ===========================================================

    /** 打开一个根目录（App 启动参数 / 选择目录都走这里）。 */
    public void openRoot(Path root) {
        if (root == null) return;
        Path abs = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            JOptionPane.showMessageDialog(this, "目录不存在：\n" + abs, "无法打开", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean sameRoot = currentRoot != null && currentRoot.equals(abs);
        if (!sameRoot) {
            documentViewer.closeAll();
            treePanel.clear();
            hideResults();
            searchField.setText("");
            searchSeq++;
            scanResult = null;
        }
        startScan(abs, !sameRoot);
    }

    private void chooseDirectory() {
        if (busy) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择包含项目的目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path start = currentRoot != null ? currentRoot : AppSettings.lastRoot();
        if (start != null && Files.isDirectory(start)) {
            chooser.setCurrentDirectory(start.toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            if (selected != null) openRoot(selected.toPath());
        }
    }

    private void rescan() {
        if (busy) return;
        if (currentRoot == null) {
            chooseDirectory();
            return;
        }
        startScan(currentRoot, false);
    }

    private void openSettings() {
        if (busy) return;
        boolean changed = SettingsDialog.show(this);
        if (!changed) return;
        refreshScopeOptions();
        if (currentRoot != null) {
            startScan(currentRoot, false);
        } else {
            statusLabel.setText("设置已保存");
        }
    }

    /** 代码索引未启用时，范围下拉框显示“代码（未启用）”，并把选中项退回“全部”。 */
    private void refreshScopeOptions() {
        boolean hasCode = AppSettings.codeIndexingEnabled();
        int selected = scopeCombo.getSelectedIndex();
        updatingScope = true;
        try {
            String codeLabel = hasCode ? "代码" : "代码（未启用）";
            scopeCombo.setModel(new DefaultComboBoxModel<>(new String[]{"全部", "Markdown", codeLabel}));
            if (!hasCode && selected == 2) selected = 0;
            scopeCombo.setSelectedIndex(Math.max(0, Math.min(selected, 2)));
            scopeCombo.setToolTipText(hasCode
                    ? "搜索范围：全部 / Markdown / 代码"
                    : "代码索引未启用，可在设置中勾选需要索引的代码类型");
        } finally {
            updatingScope = false;
        }
    }

    private void startScan(Path root, boolean newRoot) {
        if (worker != null && !worker.isDone()) {
            cancelFlag.set(true);
            worker.cancel(true);
        }
        AtomicBoolean flag = new AtomicBoolean(false);
        cancelFlag = flag;

        currentRoot = root;
        updateRootLabel();
        setTitle("RepoViewer — " + (root.getFileName() != null ? root.getFileName() : root));
        hideResults();
        setBusy(true, "正在扫描 " + root + " …");
        indexLabel.setText("");
        if (newRoot) {
            indexLabel.setText("正在建立索引…");
        }

        worker = new SwingWorker<>() {
            @Override
            protected ScanPayload doInBackground() throws Exception {
                long start = System.currentTimeMillis();
                ProjectScanner.ScanResult scan = ProjectScanner.scan(root, AppSettings.codeExtensions(),
                        AppSettings.ignoreRules(), AppSettings.projectDepth(), this::publish, flag);
                if (flag.get()) {
                    return new ScanPayload(scan, null, 0, System.currentTimeMillis() - start);
                }
                publish("已发现 " + scan.projects().size() + " 个项目，正在更新全文索引…");
                if (repoIndex == null) {
                    repoIndex = new RepoIndex(AppSettings.indexPath());
                }
                List<RepoIndex.Doc> docs = collectDocs(scan);
                // 项目识别深度会改变文档归属的项目，深度变化时强制整体重建索引
                String indexKey = root + "|depth=" + AppSettings.projectDepth();
                RepoIndex.SyncResult sync = repoIndex.sync(docs, indexKey, this::publish, flag);
                int docCount = repoIndex.documentCount();
                return new ScanPayload(scan, sync, docCount, System.currentTimeMillis() - start);
            }

            @Override
            protected void process(List<String> chunks) {
                if (worker != this || closing || chunks.isEmpty()) return;
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                if (worker != this || closing) return;
                if (flag.get()) {
                    setBusy(false, "已取消扫描");
                    return;
                }
                try {
                    ScanPayload payload = get();
                    scanResult = payload.scan();
                    treePanel.setScanResult(scanResult);
                    AppSettings.setLastRoot(root);
                    indexLabel.setText("索引 " + payload.docCount() + " 条");
                    setBusy(false, summaryText(payload));

                    if (!documentViewer.hasTabs()) {
                        autoOpenFirst(scanResult);
                    } else if (!searchField.getText().isBlank()) {
                        runSearch();
                    } else {
                        hideResults();
                    }
                } catch (CancellationException ignored) {
                    setBusy(false, "已取消扫描");
                } catch (Exception ex) {
                    setBusy(false, "扫描失败：" + ex.getMessage());
                    showError("扫描目录失败", ex);
                }
            }
        };
        worker.execute();
    }

    private static List<RepoIndex.Doc> collectDocs(ProjectScanner.ScanResult scan) {
        List<RepoIndex.Doc> docs = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (RepoProject project : scan.projects()) {
            for (Path file : project.getMarkdownFiles()) {
                if (seen.add(file)) docs.add(new RepoIndex.Doc(file, project, true));
            }
            for (Path file : project.getCodeFiles()) {
                if (seen.add(file)) docs.add(new RepoIndex.Doc(file, project, false));
            }
        }
        return docs;
    }

    private void autoOpenFirst(ProjectScanner.ScanResult scan) {
        RepoProject first = firstWithReadme(scan.root());
        RepoProject target = first != null ? first : scan.root();
        treePanel.selectProject(target);
        previewProject(target);
    }

    private static RepoProject firstWithReadme(RepoProject project) {
        if (project.hasReadme()) return project;
        for (RepoProject child : project.getChildren()) {
            RepoProject found = firstWithReadme(child);
            if (found != null) return found;
        }
        return null;
    }

    private String summaryText(ScanPayload payload) {
        ProjectScanner.ScanResult scan = payload.scan();
        StringBuilder sb = new StringBuilder();
        sb.append(scan.projects().size()).append(" 个项目 · ")
                .append(scan.docCount()).append(" 篇文档 · ")
                .append(scan.codeCount()).append(" 个代码文件");
        if (payload.sync() != null) {
            sb.append(" · 更新 ").append(payload.sync().indexed())
                    .append("，复用 ").append(payload.sync().skipped());
        }
        sb.append(" · 用时 ").append(payload.elapsedMs()).append(" ms");
        return sb.toString();
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        progressBar.setVisible(busy);
        cancelButton.setVisible(busy);
        chooseButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy && currentRoot != null);
        settingsButton.setEnabled(!busy);
        searchField.setEnabled(!busy && scanResult != null);
        scopeCombo.setEnabled(!busy && scanResult != null);
        onlyProject.setEnabled(!busy && scanResult != null);
        statusLabel.setText(message);
    }

    private void updateRootLabel() {
        if (currentRoot == null) {
            rootLabel.setText("尚未选择目录");
            rootLabel.setToolTipText(null);
            return;
        }
        rootLabel.setText(UiKit.ellipsis(currentRoot.toString(), 78));
        rootLabel.setToolTipText(currentRoot.toString());
    }

    // ===========================================================
    //  项目树 / 结果点击
    // ===========================================================

    private void previewProject(RepoProject project) {
        if (project == null) return;
        if (project.hasReadme()) {
            documentViewer.openFile(project.getReadme(), "", "", true);
        } else {
            documentViewer.openOverview(project, true);
        }
    }

    private void openProjectPinned(RepoProject project) {
        if (project == null) return;
        if (project.hasReadme()) {
            documentViewer.openFile(project.getReadme(), "", "", false);
        } else {
            documentViewer.openOverview(project, false);
        }
    }

    private void openProjectOverview(RepoProject project) {
        documentViewer.openOverview(project, true);
    }

    private void previewHit(RepoIndex.Hit hit) {
        focusProjectByPath(hit.projectPath());
        documentViewer.openFile(hit.file(), lastExecutedQuery, "", true);
    }

    private void openHitPinned(RepoIndex.Hit hit) {
        focusProjectByPath(hit.projectPath());
        documentViewer.openFile(hit.file(), lastExecutedQuery, "", false);
    }

    /** 搜索结果 / 项目下拉框 → 左侧项目树自动定位到对应项目。 */
    private void focusProjectByPath(String projectPath) {
        if (scanResult == null || projectPath == null || projectPath.isBlank()) return;
        for (RepoProject project : scanResult.projects()) {
            if (project.getRoot().toString().equals(projectPath)) {
                treePanel.selectProject(project);
                return;
            }
        }
    }

    private RepoProject findProject(Path file) {
        if (scanResult == null || file == null) return null;
        Path abs = file.toAbsolutePath().normalize();
        RepoProject best = null;
        for (RepoProject project : scanResult.projects()) {
            if (abs.startsWith(project.getRoot())) {
                if (best == null || project.getRoot().getNameCount() > best.getRoot().getNameCount()) {
                    best = project;
                }
            }
        }
        return best;
    }

    // ===========================================================
    //  搜索
    // ===========================================================

    private void installSearchListeners() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { changed(); }
            @Override
            public void removeUpdate(DocumentEvent e) { changed(); }
            @Override
            public void changedUpdate(DocumentEvent e) { changed(); }

            private void changed() {
                if (searchField.getText().isBlank()) {
                    searchTimer.stop();
                    hideResults();
                } else {
                    searchTimer.restart();
                }
            }
        });
        searchField.addActionListener(e -> {
            searchTimer.stop();
            runSearch();
        });
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "rv-clear-search");
        searchField.getActionMap().put("rv-clear-search", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                closeSearch();
            }
        });
    }

    private void runSearch() {
        if (busy) return;
        String query = searchField.getText().trim();
        if (query.isEmpty() || repoIndex == null || scanResult == null) {
            hideResults();
            return;
        }

        RepoIndex.Scope scope = currentScope();
        if (scope == RepoIndex.Scope.CODE && !AppSettings.codeIndexingEnabled()) {
            searchSeq++;
            showResultsPanel();
            resultsPanel.showMessage("代码索引未启用，请在设置中勾选需要索引的代码类型");
            return;
        }

        int seq = ++searchSeq;
        lastExecutedQuery = query;
        Path projectScope = null;
        if (onlyProject.isSelected()) {
            RepoProject project = treePanel.getSelectedProject();
            if (project != null) projectScope = project.getRoot();
        }

        showResultsPanel();
        resultsPanel.setSearching();
        Path filter = projectScope;
        searchExecutor.submit(() -> {
            try {
                RepoIndex.Outcome outcome = repoIndex.searchWithProjects(query, scope, filter, MAX_RESULTS);
                SwingUtilities.invokeLater(() -> {
                    if (seq != searchSeq || closing) return;
                    resultsPanel.showResults(outcome);
                    resultsPanel.setVisible(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (seq != searchSeq || closing) return;
                    statusLabel.setText("搜索失败：" + ex.getMessage());
                });
            }
        });
    }

    private RepoIndex.Scope currentScope() {
        return switch (scopeCombo.getSelectedIndex()) {
            case 1 -> RepoIndex.Scope.MARKDOWN;
            case 2 -> RepoIndex.Scope.CODE;
            default -> RepoIndex.Scope.ALL;
        };
    }

    private void showResultsPanel() {
        boolean wasVisible = resultsPanel.isVisible();
        resultsPanel.setVisible(true);
        if (!wasVisible) {
            SwingUtilities.invokeLater(() -> resultSplit.setDividerLocation(0.66));
        }
    }

    private void hideResults() {
        searchSeq++;
        resultsPanel.setVisible(false);
        resultsPanel.clearSelection();
    }

    private void closeSearch() {
        searchTimer.stop();
        searchField.setText("");
        hideResults();
        searchField.requestFocusInWindow();
    }

    // ===========================================================
    //  主题 / 快捷键 / 生命周期
    // ===========================================================

    private void toggleTheme() {
        Theme.install(Theme.isDark() ? Theme.Mode.LIGHT : Theme.Mode.DARK);
        AppSettings.setDark(Theme.isDark());
        applyThemeColors();
    }

    private void applyThemeColors() {
        getContentPane().setBackground(Theme.bg());
        toolbar.setBackground(Theme.bg());
        toolbar.setBorder(new MatteBorder(0, 0, 1, 0, Theme.border()));
        rootLabel.setForeground(Theme.muted());
        rootLabel.setIcon(UiKit.icon("folder", 15, Theme.muted()));
        refreshButton.setIcon(UiKit.icon("refresh", 16, Theme.muted()));
        themeButton.setIcon(UiKit.icon(Theme.isDark() ? "sun" : "moon", 16, Theme.muted()));
        settingsButton.setIcon(UiKit.icon("settings", 16, Theme.muted()));
        themeButton.setToolTipText(Theme.isDark() ? "切换到浅色" : "切换到深色");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
                UiKit.icon("search", 16, Theme.muted()));
        onlyProject.setIcon(UiKit.icon("folder", 14, Theme.muted()));
        statusBar.setBackground(Theme.panel());
        statusBar.setBorder(new MatteBorder(1, 0, 0, 0, Theme.border()));
        statusLabel.setForeground(Theme.muted());
        indexLabel.setForeground(Theme.muted());
        treePanel.onThemeChanged();
        documentViewer.onThemeChanged();
        resultsPanel.onThemeChanged();
        repaint();
    }

    private void bindShortcuts() {
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "rv-open", this::chooseDirectory);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "rv-rescan", this::rescan);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK), "rv-settings",
                this::openSettings);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "rv-search", () -> {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "rv-escape", () -> {
            if (!searchField.getText().isBlank()) closeSearch();
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "rv-close-tab",
                documentViewer::closeCurrentTab);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK), "rv-scope-all",
                () -> scopeCombo.setSelectedIndex(0));
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK), "rv-scope-md",
                () -> scopeCombo.setSelectedIndex(1));
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_3, InputEvent.CTRL_DOWN_MASK), "rv-scope-code",
                () -> scopeCombo.setSelectedIndex(2));
    }

    private void bindKey(KeyStroke stroke, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    private void restoreWindowBounds() {
        int[] bounds = AppSettings.windowBounds();
        if (bounds != null && bounds[2] >= 900 && bounds[3] >= 600 && onScreen(bounds)) {
            setBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        } else {
            setSize(1280, 820);
            setLocationRelativeTo(null);
        }
        if (AppSettings.maximized()) setExtendedState(getExtendedState() | MAXIMIZED_BOTH);
    }

    private static boolean onScreen(int[] bounds) {
        int cx = bounds[0] + bounds[2] / 2;
        int cy = bounds[1] + bounds[3] / 2;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().contains(cx, cy)) return true;
        }
        return false;
    }

    private void shutdown() {
        if (closing) return;
        closing = true;
        cancelFlag.set(true);
        if (worker != null) worker.cancel(true);
        searchTimer.stop();
        searchExecutor.shutdownNow();
        try {
            if (repoIndex != null) repoIndex.close();
        } catch (Exception ignored) {
        }
        AppSettings.setDark(Theme.isDark());
        AppSettings.setMaximized((getExtendedState() & MAXIMIZED_BOTH) != 0);
        if ((getExtendedState() & MAXIMIZED_BOTH) == 0) {
            Rectangle bounds = getBounds();
            AppSettings.setWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        int divider = mainSplit.getDividerLocation();
        if (divider > 0) AppSettings.setDivider(divider);
        dispose();
        System.exit(0);
    }

    private void showError(String title, Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }
}
