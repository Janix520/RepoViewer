package com.repoviewer.ui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 界面小工具：矢量图标、按钮 / 输入框的统一样式、桌面集成。
 * <p>
 * 图标全部用 Graphics2D 画，不依赖任何图片资源，切换主题时按当前颜色重新生成。
 */
public final class UiKit {

    private UiKit() {
    }

    // ===========================================================
    //  图标
    // ===========================================================

    public interface Painter {
        void paint(Graphics2D g, int size);
    }

    private static final Map<String, Icon> ICON_CACHE = new HashMap<>();

    public static Icon icon(String name, int size, Color color) {
        String key = name + "@" + size + "#" + color.getRGB();
        return ICON_CACHE.computeIfAbsent(key, k -> new VectorIcon(size, color, painterFor(name)));
    }

    public static Image appImage(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color top = Theme.isDark() ? new Color(0x2F81F7) : new Color(0x1F6FEB);
        Color bottom = Theme.isDark() ? new Color(0x1B4F9C) : new Color(0x0B4EA2);
        g.setPaint(new GradientPaint(0, 0, top, 0, size, bottom));
        g.fillRoundRect(0, 0, size, size, size / 4, size / 4);

        // 白色文档
        int m = size / 4;
        int docW = (int) (size * 0.42);
        int docH = (int) (size * 0.52);
        int docX = (int) (size * 0.24);
        int docY = (int) (size * 0.24);
        g.setColor(Color.WHITE);
        g.fillRoundRect(docX, docY, docW, docH, size / 10, size / 10);
        g.setColor(new Color(0xB6D3FF));
        for (int i = 0; i < 3; i++) {
            int y = docY + (int) (docH * (0.28 + i * 0.2));
            g.fillRoundRect(docX + docW / 6, y, docW * 2 / 3, Math.max(1, size / 26), 2, 2);
        }

        // 放大镜
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(size / 13f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = (int) (size * 0.66);
        int cy = (int) (size * 0.65);
        int r = (int) (size * 0.15);
        g.drawOval(cx - r, cy - r, r * 2, r * 2);
        g.drawLine(cx + r * 2 / 3, cy + r * 2 / 3, (int) (size * 0.87), (int) (size * 0.87));

        g.dispose();
        return image;
    }

    private static Painter painterFor(String name) {
        return switch (name) {
            case "folder" -> (g, s) -> {
                g.setStroke(stroke(s));
                Path2D p = new Path2D.Float();
                float x = s * 0.12f, y = s * 0.28f, w = s * 0.76f, h = s * 0.5f;
                p.moveTo(x, y + s * 0.08f);
                p.quadTo(x, y, x + s * 0.08f, y);
                p.lineTo(x + w * 0.36f, y);
                p.lineTo(x + w * 0.46f, y + s * 0.1f);
                p.lineTo(x + w - s * 0.08f, y + s * 0.1f);
                p.quadTo(x + w, y + s * 0.1f, x + w, y + s * 0.18f);
                p.lineTo(x + w, y + h);
                p.lineTo(x, y + h);
                p.closePath();
                g.draw(p);
            };
            case "folder-open" -> (g, s) -> {
                g.setStroke(stroke(s));
                Path2D p = new Path2D.Float();
                float x = s * 0.1f, y = s * 0.24f, w = s * 0.8f, h = s * 0.56f;
                p.moveTo(x, y + s * 0.1f);
                p.quadTo(x, y, x + s * 0.1f, y);
                p.lineTo(x + w * 0.3f, y);
                p.lineTo(x + w * 0.4f, y + s * 0.1f);
                p.lineTo(x + w - s * 0.1f, y + s * 0.1f);
                p.lineTo(x + w - s * 0.1f, y + s * 0.22f);
                g.draw(p);
                Path2D front = new Path2D.Float();
                front.moveTo(x, y + s * 0.22f);
                front.lineTo(x + w, y + s * 0.22f);
                front.lineTo(x + w - s * 0.1f, y + h);
                front.lineTo(x + s * 0.08f, y + h);
                front.closePath();
                g.draw(front);
            };
            case "doc" -> (g, s) -> {
                g.setStroke(stroke(s));
                float x = s * 0.22f, y = s * 0.12f, w = s * 0.56f, h = s * 0.76f;
                Path2D p = new Path2D.Float();
                p.moveTo(x, y);
                p.lineTo(x + w * 0.62f, y);
                p.lineTo(x + w, y + w * 0.32f);
                p.lineTo(x + w, y + h);
                p.lineTo(x, y + h);
                p.closePath();
                g.draw(p);
                g.drawLine((int) (x + w * 0.62f), (int) y, (int) (x + w * 0.62f), (int) (y + w * 0.32f));
                g.drawLine((int) (x + w * 0.62f), (int) (y + w * 0.32f), (int) (x + w), (int) (y + w * 0.32f));
                g.drawLine((int) (x + w * 0.2f), (int) (y + h * 0.52f), (int) (x + w * 0.8f), (int) (y + h * 0.52f));
                g.drawLine((int) (x + w * 0.2f), (int) (y + h * 0.72f), (int) (x + w * 0.6f), (int) (y + h * 0.72f));
            };
            case "code" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawPolyline(new int[]{(int) (s * 0.38), (int) (s * 0.14), (int) (s * 0.38)},
                        new int[]{(int) (s * 0.3), (int) (s * 0.5), (int) (s * 0.7)}, 3);
                g.drawPolyline(new int[]{(int) (s * 0.62), (int) (s * 0.86), (int) (s * 0.62)},
                        new int[]{(int) (s * 0.3), (int) (s * 0.5), (int) (s * 0.7)}, 3);
                g.drawLine((int) (s * 0.55), (int) (s * 0.22), (int) (s * 0.45), (int) (s * 0.78));
            };
            case "search" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawOval((int) (s * 0.16), (int) (s * 0.16), (int) (s * 0.48), (int) (s * 0.48));
                g.drawLine((int) (s * 0.62), (int) (s * 0.62), (int) (s * 0.84), (int) (s * 0.84));
            };
            case "refresh" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawArc((int) (s * 0.16), (int) (s * 0.16), (int) (s * 0.68), (int) (s * 0.68), 40, 280);
                g.drawPolyline(new int[]{(int) (s * 0.74), (int) (s * 0.82), (int) (s * 0.62)},
                        new int[]{(int) (s * 0.2), (int) (s * 0.4), (int) (s * 0.42)}, 3);
            };
            case "sun" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawOval((int) (s * 0.32), (int) (s * 0.32), (int) (s * 0.36), (int) (s * 0.36));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    g.drawLine((int) (s * 0.5 + Math.cos(a) * s * 0.3), (int) (s * 0.5 + Math.sin(a) * s * 0.3),
                            (int) (s * 0.5 + Math.cos(a) * s * 0.42), (int) (s * 0.5 + Math.sin(a) * s * 0.42));
                }
            };
            case "moon" -> (g, s) -> {
                Area moon = new Area(new Ellipse2D.Float(s * 0.2f, s * 0.14f, s * 0.6f, s * 0.72f));
                moon.subtract(new Area(new Ellipse2D.Float(s * 0.36f, s * 0.04f, s * 0.62f, s * 0.7f)));
                g.fill(moon);
            };
            case "external" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawRoundRect((int) (s * 0.14), (int) (s * 0.26), (int) (s * 0.52), (int) (s * 0.6), 3, 3);
                g.drawLine((int) (s * 0.44), (int) (s * 0.56), (int) (s * 0.84), (int) (s * 0.16));
                g.drawPolyline(new int[]{(int) (s * 0.62), (int) (s * 0.84), (int) (s * 0.84)},
                        new int[]{(int) (s * 0.16), (int) (s * 0.16), (int) (s * 0.38)}, 3);
            };
            case "copy" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawRoundRect((int) (s * 0.16), (int) (s * 0.16), (int) (s * 0.48), (int) (s * 0.56), 3, 3);
                g.drawRoundRect((int) (s * 0.34), (int) (s * 0.3), (int) (s * 0.48), (int) (s * 0.56), 3, 3);
            };
            case "settings" -> (g, s) -> {
                g.setStroke(stroke(s));
                int x1 = (int) (s * 0.16), x2 = (int) (s * 0.84);
                for (int i = 0; i < 3; i++) {
                    int y = (int) (s * (0.3 + i * 0.2));
                    g.drawLine(x1, y, x2, y);
                }
                int r = Math.max(2, s / 9);
                g.fillOval((int) (s * 0.62) - r, (int) (s * 0.3) - r, r * 2, r * 2);
                g.fillOval((int) (s * 0.36) - r, (int) (s * 0.5) - r, r * 2, r * 2);
                g.fillOval((int) (s * 0.7) - r, (int) (s * 0.7) - r, r * 2, r * 2);
            };
            case "plus" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawLine((int) (s * 0.5), (int) (s * 0.24), (int) (s * 0.5), (int) (s * 0.76));
                g.drawLine((int) (s * 0.24), (int) (s * 0.5), (int) (s * 0.76), (int) (s * 0.5));
            };
            case "check" -> (g, s) -> {
                g.setStroke(new BasicStroke(Math.max(1.6f, s / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawPolyline(new int[]{(int) (s * 0.22), (int) (s * 0.42), (int) (s * 0.78)},
                        new int[]{(int) (s * 0.52), (int) (s * 0.72), (int) (s * 0.3)}, 3);
            };
            case "close" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawLine((int) (s * 0.28), (int) (s * 0.28), (int) (s * 0.72), (int) (s * 0.72));
                g.drawLine((int) (s * 0.72), (int) (s * 0.28), (int) (s * 0.28), (int) (s * 0.72));
            };
            case "chevron-right" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawPolyline(new int[]{(int) (s * 0.4), (int) (s * 0.62), (int) (s * 0.4)},
                        new int[]{(int) (s * 0.28), (int) (s * 0.5), (int) (s * 0.72)}, 3);
            };
            case "chevron-down" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawPolyline(new int[]{(int) (s * 0.28), (int) (s * 0.5), (int) (s * 0.72)},
                        new int[]{(int) (s * 0.4), (int) (s * 0.62), (int) (s * 0.4)}, 3);
            };
            case "list" -> (g, s) -> {
                g.setStroke(stroke(s));
                for (int i = 0; i < 3; i++) {
                    int y = (int) (s * (0.3 + i * 0.2));
                    g.fillOval((int) (s * 0.16), y - 1, 2, 2);
                    g.drawLine((int) (s * 0.3), y, (int) (s * 0.84), y);
                }
            };
            case "info" -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawOval((int) (s * 0.14), (int) (s * 0.14), (int) (s * 0.72), (int) (s * 0.72));
                g.drawLine((int) (s * 0.5), (int) (s * 0.46), (int) (s * 0.5), (int) (s * 0.72));
                g.fillOval((int) (s * 0.47), (int) (s * 0.3), (int) (s * 0.06), (int) (s * 0.06));
            };
            default -> (g, s) -> {
                g.setStroke(stroke(s));
                g.drawOval((int) (s * 0.2), (int) (s * 0.2), (int) (s * 0.6), (int) (s * 0.6));
            };
        };
    }

    private static BasicStroke stroke(int size) {
        return new BasicStroke(Math.max(1.2f, size / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private static final class VectorIcon implements Icon {
        private final int size;
        private final Color color;
        private final Painter painter;

        VectorIcon(int size, Color color, Painter painter) {
            this.size = size;
            this.color = color;
            this.painter = painter;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(color);
            painter.paint(g2, size);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    // ===========================================================
    //  组件工厂
    // ===========================================================

    public static JButton toolButton(String text, String iconName, String tooltip, Runnable action) {
        JButton button = new JButton(text);
        if (iconName != null) {
            button.setIcon(icon(iconName, 15, Theme.muted()));
            button.setIconTextGap(6);
        }
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.addActionListener(e -> action.run());
        return button;
    }

    public static JButton primaryButton(String text, String iconName, Runnable action) {
        JButton button = new JButton(text);
        if (iconName != null) {
            button.setIcon(icon(iconName, 15, Color.WHITE));
            button.setIconTextGap(7);
        }
        button.setFocusable(false);
        button.putClientProperty(FlatClientProperties.STYLE,
                "background: #1F6FEB; foreground: #FFFFFF; borderWidth: 0; arc: 9;");
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        button.addActionListener(e -> action.run());
        return button;
    }

    public static JButton iconButton(String iconName, String tooltip, int size, Runnable action) {
        JButton button = new JButton(icon(iconName, size, Theme.muted()));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setBorder(new EmptyBorder(4, 4, 4, 4));
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> action.run());
        return button;
    }

    public static JTextField searchField(String placeholder) {
        JTextField field = new JTextField();
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
                icon("search", 16, Theme.muted()));
        field.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10;");
        field.setMargin(new Insets(5, 8, 5, 8));
        return field;
    }

    public static JScrollPane scroll(JComponent view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getHorizontalScrollBar().setUnitIncrement(18);
        scroll.getViewport().setBackground(Theme.surface());
        return scroll;
    }

    public static JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    public static JLabel htmlLabel(String html, Font font, Color color) {
        JLabel label = new JLabel(html);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    public static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String ellipsis(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    // ===========================================================
    //  桌面集成
    // ===========================================================

    public static void openInExplorer(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                File file = path.toFile();
                if (isWindows() && file.isFile()) {
                    new ProcessBuilder("explorer.exe", "/select,", file.getAbsolutePath()).start();
                    return;
                }
                if (isMac() && file.isFile()) {
                    new ProcessBuilder("open", "-R", file.getAbsolutePath()).start();
                    return;
                }
                Desktop.getDesktop().open(file.isDirectory() ? file : file.getParentFile());
                return;
            }
        } catch (Exception ignored) {
        }
        copyToClipboard(path.toString());
        JOptionPane.showMessageDialog(null, "无法打开文件管理器，路径已复制到剪贴板：\n" + path);
    }

    public static void openExternally(Path path) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (Exception ignored) {
        }
    }

    public static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception ignored) {
        }
    }

    public static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** 给按钮 / 标签统一加上手型光标。 */
    public static <T extends JComponent> T hand(T component) {
        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return component;
    }
}
