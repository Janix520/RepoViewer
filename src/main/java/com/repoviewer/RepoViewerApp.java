package com.repoviewer;

import com.repoviewer.ui.MainFrame;
import com.repoviewer.ui.Theme;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用入口。
 * <p>刻意不引入 Spring：启动只做三件事——装主题、开窗口、恢复上次目录。
 */
public final class RepoViewerApp {

    private RepoViewerApp() {
    }

    public static void main(String[] args) {
        // Windows 上 ClearType 已是开启状态，这里让 Java2D 使用 LCD 子像素抗锯齿；
        // macOS / Linux 没有 ClearType 概念，退回灰度抗锯齿。
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        System.setProperty("awt.useSystemAAFontSettings", windows ? "lcd" : "on");
        System.setProperty("swing.aatext", "true");
        // Lucene 在 Java 21 上会打印 MemorySegment / Vector API 的 INFO，控制台里没必要出现
        java.util.logging.Logger.getLogger("org.apache.lucene").setLevel(java.util.logging.Level.WARNING);

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境不支持图形界面，无法启动 RepoViewer");
            return;
        }

        SwingUtilities.invokeLater(() -> {
            Theme.install(AppSettings.dark() ? Theme.Mode.DARK : Theme.Mode.LIGHT);
            MainFrame frame = new MainFrame();
            frame.setVisible(true);

            Path root = null;
            if (args.length > 0) {
                try {
                    root = Path.of(args[0]);
                } catch (Exception ignored) {
                    root = null;
                }
            }
            if (root == null || !Files.isDirectory(root)) {
                root = AppSettings.lastRoot();
            }
            if (root != null && Files.isDirectory(root)) {
                frame.openRoot(root);
            }
        });
    }
}
