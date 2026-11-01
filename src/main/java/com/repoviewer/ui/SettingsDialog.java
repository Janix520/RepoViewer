package com.repoviewer.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.repoviewer.AppSettings;
import com.repoviewer.core.FileTypes;
import com.repoviewer.core.ProjectScanner;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 扫描设置：
 * <ul>
 *   <li>忽略目录：默认带上 node_modules / dist / .git / .svn 等，可自由增删；</li>
 *   <li>代码索引：默认只索引 Markdown，按语言勾选需要参与搜索的代码扩展名。</li>
 * </ul>
 * 保存后由调用方决定是否重新扫描当前目录。
 */
final class SettingsDialog {

    private static final List<CodeGroup> CODE_GROUPS = List.of(
            new CodeGroup("Java / Kotlin / Scala", List.of("java", "kt", "kts", "scala", "groovy", "gradle")),
            new CodeGroup("JavaScript / TypeScript", List.of("js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte")),
            new CodeGroup("Python", List.of("py", "pyw")),
            new CodeGroup("Go", List.of("go")),
            new CodeGroup("Rust", List.of("rs")),
            new CodeGroup("C / C++", List.of("c", "h", "cc", "cpp", "cxx", "hpp", "hh")),
            new CodeGroup("C#", List.of("cs")),
            new CodeGroup("Web（HTML / CSS）", List.of("html", "htm", "css", "scss", "sass", "less")),
            new CodeGroup("配置（JSON / YAML / XML）", List.of("json", "json5", "jsonc", "xml", "yml", "yaml", "toml", "properties", "ini", "conf")),
            new CodeGroup("Shell / 脚本", List.of("sh", "bash", "zsh", "bat", "cmd", "ps1", "psm1")),
            new CodeGroup("SQL", List.of("sql")),
            new CodeGroup("其他语言", List.of("rb", "php", "lua", "swift", "dart", "r", "pl", "clj", "hs",
                    "ex", "exs", "erl", "fs", "vb", "m", "mm", "asm", "s", "proto", "graphql", "gql", "tex", "cmake", "mk"))
    );

    private record CodeGroup(String label, List<String> extensions) {
    }

    private final JDialog dialog;
    private final DefaultListModel<String> ignoreModel = new DefaultListModel<>();
    private final JList<String> ignoreList = new JList<>(ignoreModel);
    private final JTextField ignoreInput = new JTextField();
    private final JCheckBox hiddenDirs = new JCheckBox("同时忽略所有以 . 开头的隐藏目录", true);
    private final JSpinner projectDepth = new JSpinner(
            new SpinnerNumberModel(ProjectScanner.DEFAULT_PROJECT_DEPTH, 1, 16, 1));
    private final JLabel ignoreCount = UiKit.label("", Theme.uiFont(Font.PLAIN, 11), Theme.muted());

    private final JCheckBox masterCode = new JCheckBox("全部代码类型");
    private final List<JCheckBox> groupBoxes = new ArrayList<>();
    private final JTextField customExtensions = new JTextField();
    private final JLabel codeCount = UiKit.label("", Theme.uiFont(Font.PLAIN, 11), Theme.muted());

    private final Set<String> originalIgnore;
    private final boolean originalHidden;
    private final int originalDepth;
    private final Set<String> originalCode;

    private boolean updatingMaster;
    private boolean changed;

    private SettingsDialog(Window owner) {
        this.originalIgnore = normalizeIgnoreNames(AppSettings.ignoredDirs());
        this.originalHidden = AppSettings.ignoreHiddenDirs();
        this.originalDepth = AppSettings.projectDepth();
        this.originalCode = new TreeSet<>(AppSettings.codeExtensions());

        dialog = new JDialog(owner, "设置", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setIconImage(UiKit.appImage(32));
        dialog.setLayout(new MigLayout("insets 16 18 14 18, fill", "[grow,fill]", "[grow,fill][]"));

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(null);
        tabs.addTab("忽略目录", UiKit.icon("folder", 14, Theme.muted()), buildIgnoreTab());
        tabs.addTab("代码索引", UiKit.icon("code", 14, Theme.muted()), buildCodeTab());

        dialog.add(tabs, "grow, push, wrap");
        dialog.add(buildFooter(), "growx");

        loadIgnoreState();
        loadCodeState();

        dialog.setSize(660, 620);
        dialog.setMinimumSize(new Dimension(580, 520));
        dialog.setLocationRelativeTo(owner);
        bindEscape();
    }

    static boolean show(Window owner) {
        SettingsDialog settings = new SettingsDialog(owner);
        settings.dialog.setVisible(true);
        return settings.changed;
    }

    // ===========================================================
    //  忽略目录
    // ===========================================================

    private JPanel buildIgnoreTab() {
        JPanel tab = new JPanel(new MigLayout("insets 16 16 12 16, fill",
                "[grow,fill]", "[]6[]10[grow,fill]8[]8[]10[]8[]4[]"));

        tab.add(UiKit.label("忽略目录", Theme.uiFont(Font.BOLD, 14), Theme.text()), "wrap");
        tab.add(UiKit.htmlLabel(
                "<html>扫描时会整棵跳过这些目录名（大小写不敏感）。<br>"
                        + "默认已包含 node_modules、dist、.git、.svn 等常见目录，可以自由增删。</html>",
                Theme.uiFont(Font.PLAIN, 11), Theme.muted()), "growx, wrap");

        ignoreList.setFixedCellHeight(28);
        ignoreList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ignoreList.setBackground(Theme.surface());
        ignoreList.setBorder(new EmptyBorder(2, 2, 2, 2));
        ignoreList.setCellRenderer(new IgnoreRenderer());
        ignoreList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "rv-remove-ignore");
        ignoreList.getActionMap().put("rv-remove-ignore", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                removeSelectedIgnore();
            }
        });

        JScrollPane scroll = new JScrollPane(ignoreList);
        scroll.setBorder(new LineBorder(Theme.border()));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(Theme.surface());
        tab.add(scroll, "grow, push, wrap");

        JButton remove = dialogButton("删除", "close", this::removeSelectedIgnore);
        JButton reset = dialogButton("恢复默认", "refresh", this::resetIgnoreDefaults);
        JPanel listBar = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]6[]6[]", ""));
        listBar.setOpaque(false);
        listBar.add(ignoreCount, "growx");
        listBar.add(remove, "width 76!");
        listBar.add(reset, "width 106!");
        tab.add(listBar, "growx, wrap");

        ignoreInput.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "输入目录名，如 out、coverage");
        ignoreInput.putClientProperty(FlatClientProperties.STYLE, "arc: 9;");
        ignoreInput.setMargin(new Insets(5, 8, 5, 8));
        ignoreInput.setFont(Theme.uiFont(Font.PLAIN, 12));
        ignoreInput.addActionListener(e -> addIgnoreName());

        JButton add = dialogButton("添加", "plus", this::addIgnoreName);
        JPanel addBar = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]6[]", ""));
        addBar.setOpaque(false);
        addBar.add(ignoreInput, "growx, height 32!");
        addBar.add(add, "height 32!, width 82!");
        tab.add(addBar, "growx, wrap");

        hiddenDirs.setOpaque(false);
        hiddenDirs.setFont(Theme.uiFont(Font.PLAIN, 12));
        hiddenDirs.setToolTipText("关闭后，.github、.config 这类目录也会被扫描");
        tab.add(hiddenDirs, "wrap");

        projectDepth.setFont(Theme.uiFont(Font.PLAIN, 12));
        if (projectDepth.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setFont(Theme.uiFont(Font.PLAIN, 12));
            editor.getTextField().setHorizontalAlignment(JTextField.LEFT);
        }
        projectDepth.setToolTipText("数值越大，越多深层 README 会单独成为项目节点");
        JPanel depthRow = new JPanel(new MigLayout("insets 0, fillx", "[]8[70!][]", ""));
        depthRow.setOpaque(false);
        depthRow.add(UiKit.label("项目识别深度", Theme.uiFont(Font.PLAIN, 12), Theme.text()));
        depthRow.add(projectDepth, "height 30!");
        depthRow.add(UiKit.label("层以内", Theme.uiFont(Font.PLAIN, 11), Theme.muted()));
        tab.add(depthRow, "growx, wrap");
        tab.add(UiKit.htmlLabel(
                "<html>只把扫描根目录下该层数以内的 README 目录识别为项目；更深的 README "
                        + "仍会作为上级项目的文档参与内容搜索。</html>",
                Theme.uiFont(Font.PLAIN, 11), Theme.muted()), "growx, wrap");

        return tab;
    }

    private void loadIgnoreState() {
        ignoreModel.clear();
        List<String> names = new ArrayList<>(originalIgnore);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) ignoreModel.addElement(name);
        hiddenDirs.setSelected(originalHidden);
        projectDepth.setValue(originalDepth);
        updateIgnoreCount();
    }

    private void addIgnoreName() {
        String raw = ignoreInput.getText().trim();
        if (raw.isEmpty()) return;
        String name = raw.toLowerCase(Locale.ROOT);
        if (name.contains("/") || name.contains("\\")) {
            JOptionPane.showMessageDialog(dialog, "只支持目录名，例如 node_modules；请不要填写路径。",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int existing = indexOfIgnore(name);
        if (existing >= 0) {
            ignoreList.setSelectedIndex(existing);
            ignoreList.ensureIndexIsVisible(existing);
            ignoreInput.setText("");
            return;
        }
        ignoreModel.addElement(name);
        sortIgnoreModel();
        int index = indexOfIgnore(name);
        if (index >= 0) {
            ignoreList.setSelectedIndex(index);
            ignoreList.ensureIndexIsVisible(index);
        }
        ignoreInput.setText("");
        updateIgnoreCount();
    }

    private void removeSelectedIgnore() {
        int index = ignoreList.getSelectedIndex();
        if (index < 0) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        ignoreModel.remove(index);
        if (!ignoreModel.isEmpty()) {
            ignoreList.setSelectedIndex(Math.min(index, ignoreModel.size() - 1));
        }
        updateIgnoreCount();
    }

    private void resetIgnoreDefaults() {
        ignoreModel.clear();
        List<String> names = new ArrayList<>(FileTypes.DEFAULT_IGNORED_DIRS);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) ignoreModel.addElement(name);
        hiddenDirs.setSelected(true);
        projectDepth.setValue(ProjectScanner.DEFAULT_PROJECT_DEPTH);
        updateIgnoreCount();
    }

    private void sortIgnoreModel() {
        List<String> names = new ArrayList<>(ignoreModel.size());
        for (int i = 0; i < ignoreModel.size(); i++) names.add(ignoreModel.get(i));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        ignoreModel.clear();
        for (String name : names) ignoreModel.addElement(name);
    }

    private int indexOfIgnore(String name) {
        for (int i = 0; i < ignoreModel.size(); i++) {
            if (ignoreModel.get(i).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private void updateIgnoreCount() {
        ignoreCount.setText("共 " + ignoreModel.size() + " 项");
    }

    // ===========================================================
    //  代码索引
    // ===========================================================

    private JPanel buildCodeTab() {
        JPanel tab = new JPanel(new MigLayout("insets 16 16 12 16, fill",
                "[grow,fill]", "[]6[]12[]10[grow,fill]12[]6[]"));

        tab.add(UiKit.label("代码索引", Theme.uiFont(Font.BOLD, 14), Theme.text()), "wrap");
        tab.add(UiKit.htmlLabel(
                "<html>默认只索引 Markdown。勾选需要参与全文搜索的代码类型；<br>"
                        + "未勾选的文件仍可以通过 README 链接打开，只是不会出现在搜索结果里。</html>",
                Theme.uiFont(Font.PLAIN, 11), Theme.muted()), "growx, wrap");

        masterCode.setOpaque(false);
        masterCode.setFont(Theme.uiFont(Font.BOLD, 12));
        masterCode.addItemListener(e -> {
            if (updatingMaster) return;
            updatingMaster = true;
            try {
                boolean selected = masterCode.isSelected();
                for (JCheckBox box : groupBoxes) box.setSelected(selected);
            } finally {
                updatingMaster = false;
            }
            updateCodeCount();
        });

        JPanel masterRow = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", ""));
        masterRow.setOpaque(false);
        masterRow.add(masterCode, "growx");
        masterRow.add(codeCount, "align right");
        tab.add(masterRow, "growx, wrap");

        JPanel groups = new JPanel(new MigLayout("insets 0, fillx, wrap 2",
                "[grow,fill][grow,fill]", ""));
        groups.setOpaque(false);
        for (CodeGroup group : CODE_GROUPS) {
            JCheckBox box = new JCheckBox(group.label());
            box.setOpaque(false);
            box.setFont(Theme.uiFont(Font.PLAIN, 12));
            box.setToolTipText("." + String.join("  .", group.extensions()));
            box.addItemListener(e -> {
                if (updatingMaster) return;
                updateMasterState();
                updateCodeCount();
            });
            groupBoxes.add(box);
            groups.add(box, "growx");
        }
        tab.add(groups, "grow, push, wrap");

        customExtensions.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "例如 lua, rb, php（不带点）");
        customExtensions.putClientProperty(FlatClientProperties.STYLE, "arc: 9;");
        customExtensions.setMargin(new Insets(5, 8, 5, 8));
        customExtensions.setFont(Theme.uiFont(Font.PLAIN, 12));
        customExtensions.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateCodeCount(); }
            @Override public void removeUpdate(DocumentEvent e) { updateCodeCount(); }
            @Override public void changedUpdate(DocumentEvent e) { updateCodeCount(); }
        });

        JPanel customRow = new JPanel(new MigLayout("insets 0, fillx", "[]8[grow,fill]", ""));
        customRow.setOpaque(false);
        customRow.add(UiKit.label("自定义后缀", Theme.uiFont(Font.PLAIN, 12), Theme.text()));
        customRow.add(customExtensions, "growx, height 32!");
        tab.add(customRow, "growx, wrap");

        tab.add(UiKit.label("用逗号或空格分隔；只有勾选或填写的类型会被读取并建立索引。",
                Theme.uiFont(Font.PLAIN, 11), Theme.muted()), "growx, wrap");

        return tab;
    }

    private void loadCodeState() {
        Set<String> residual = new LinkedHashSet<>(originalCode);
        for (int i = 0; i < CODE_GROUPS.size(); i++) {
            CodeGroup group = CODE_GROUPS.get(i);
            JCheckBox box = groupBoxes.get(i);
            if (residual.containsAll(group.extensions())) {
                box.setSelected(true);
                residual.removeAll(group.extensions());
            }
        }
        customExtensions.setText(String.join(", ", residual));
        updateMasterState();
        updateCodeCount();
    }

    private void updateMasterState() {
        updatingMaster = true;
        try {
            masterCode.setSelected(!groupBoxes.isEmpty() && groupBoxes.stream().allMatch(JCheckBox::isSelected));
        } finally {
            updatingMaster = false;
        }
    }

    private void updateCodeCount() {
        int count = selectedExtensions().size();
        codeCount.setText(count == 0 ? "当前只索引 Markdown" : "将索引 " + count + " 种代码扩展名");
    }

    private Set<String> selectedExtensions() {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < CODE_GROUPS.size(); i++) {
            if (groupBoxes.get(i).isSelected()) {
                result.addAll(CODE_GROUPS.get(i).extensions());
            }
        }
        String custom = customExtensions.getText();
        if (custom != null && !custom.isBlank()) {
            result.addAll(FileTypes.normalizeExtensions(List.of(custom.split("[,，;；\\s]+"))));
        }
        return FileTypes.normalizeExtensions(result);
    }

    // ===========================================================
    //  底部 / 保存
    // ===========================================================

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new MigLayout("insets 10 0 0 0, fillx", "[grow,fill]8[]8[]", ""));
        footer.setOpaque(false);
        footer.add(UiKit.label("保存后会自动重新扫描当前目录", Theme.uiFont(Font.PLAIN, 11), Theme.muted()), "growx");

        JButton cancel = new JButton("取消");
        cancel.setFocusable(false);
        cancel.addActionListener(e -> dialog.dispose());

        JButton save = UiKit.primaryButton("保存", "check", this::save);
        save.setPreferredSize(new Dimension(96, 34));

        footer.add(cancel);
        footer.add(save);
        dialog.getRootPane().setDefaultButton(save);
        return footer;
    }

    private void save() {
        Set<String> ignoreNames = new TreeSet<>();
        for (int i = 0; i < ignoreModel.size(); i++) {
            ignoreNames.add(ignoreModel.get(i).toLowerCase(Locale.ROOT));
        }
        boolean hidden = hiddenDirs.isSelected();
        int depth = ((Number) projectDepth.getValue()).intValue();
        Set<String> code = selectedExtensions();

        changed = !ignoreNames.equals(originalIgnore)
                || hidden != originalHidden
                || depth != originalDepth
                || !code.equals(originalCode);

        if (changed) {
            AppSettings.setIgnoredDirs(ignoreNames);
            AppSettings.setIgnoreHiddenDirs(hidden);
            AppSettings.setProjectDepth(depth);
            AppSettings.setCodeExtensions(code);
        }
        dialog.dispose();
    }

    private void bindEscape() {
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "rv-settings-close");
        dialog.getRootPane().getActionMap().put("rv-settings-close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dialog.dispose();
            }
        });
    }

    private JButton dialogButton(String text, String iconName, Runnable action) {
        JButton button = new JButton(text, UiKit.icon(iconName, 14, Theme.muted()));
        button.setIconTextGap(5);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static Set<String> normalizeIgnoreNames(Collection<String> names) {
        Set<String> result = new TreeSet<>();
        if (names == null) return result;
        for (String raw : names) {
            if (raw == null) continue;
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.contains("/") || name.contains("\\")) continue;
            result.add(name);
        }
        return result;
    }

    /** 列表里给每个目录名配一个小文件夹图标。 */
    private static final class IgnoreRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setIcon(UiKit.icon("folder", 14, isSelected ? Theme.text() : Theme.muted()));
            setIconTextGap(7);
            setBorder(new EmptyBorder(2, 7, 2, 5));
            setFont(Theme.uiFont(Font.PLAIN, 12));
            return this;
        }
    }
}
