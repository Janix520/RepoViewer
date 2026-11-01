package com.repoviewer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    @TempDir
    Path tmp;

    private static final String MD = """
            # 标题

            [![Build](https://img.shields.io/badge/build.svg)](https://example.com)

            这一段是 **摘要**，包含 [指南](docs/guide.md) 和 `code`。

            ## 安装

            ```java
            System.out.println("hi");
            ```

            > 引用内容

            | A | B |
            |---|---|
            | 1 | 2 |

            - [x] 完成
            - [ ] 未完成

            ---
            """;

    @Test
    void rendersGithubLikeHtmlWithAnchorsAndResolvedLinks() throws Exception {
        Path readme = tmp.resolve("README.md");
        Files.writeString(readme, MD);

        MarkdownRenderer.Rendered rendered = MarkdownRenderer.render(
                readme, MD, new MarkdownRenderer.RenderTheme(false, 11, 800));
        String html = rendered.html();

        assertEquals(2, rendered.toc().size());
        assertEquals("标题", rendered.toc().get(0).text());
        assertEquals("h-2", rendered.toc().get(1).anchor());

        // h-N 锚点 + GitHub 风格 slug 锚点都存在
        assertTrue(html.contains("<a name=\"h-1\"></a><a name=\"标题\"></a>"));
        assertTrue(html.contains("<a name=\"h-2\"></a><a name=\"安装\"></a>"));

        // 相对链接被解析成 file URI，并保留锚点
        assertTrue(html.contains("file:"), "relative link should resolve to file URI");
        assertTrue(html.contains("docs/guide.md"));

        // 代码块 → 带背景色的表格；任务列表 → 文本符号；表格 → 带边框
        assertTrue(html.contains("bgcolor=\"#F6F8FA\""), "code block background");
        assertTrue(html.contains("&#8730;"), "checked task glyph");
        assertTrue(html.contains("&#9675;"), "unchecked task glyph");
        assertTrue(html.contains("border=\"1\""), "table border");
        assertTrue(html.contains("<blockquote>") || html.contains("bgcolor=\"#D0D7DE\""),
                "blockquote should be transformed into a left-rule table");
    }

    @Test
    void extractsTitleAndSummarySkippingBadges() {
        assertEquals("标题", MarkdownRenderer.extractTitle(MD));
        String summary = MarkdownRenderer.extractSummary(MD);
        assertTrue(summary.startsWith("这一段是 摘要"));
        assertTrue(summary.contains("指南"));
        assertTrue(summary.length() < 220);
    }
}
