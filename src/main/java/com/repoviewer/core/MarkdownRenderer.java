package com.repoviewer.core;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → Swing HTML。
 * <p>
 * 直接拿 commonmark 输出的 HTML 丢进 JEditorPane 效果很差：代码块没有底色、表格没有边框、
 * 标题没有锚点、相对图片路径也加载不出来。这里在 HTML 输出之后做一轮“Swing 化”后处理：
 * <ul>
 *   <li>代码块 → 带底色的单行表格（Swing 对 table 的 bgcolor 支持最可靠）</li>
 *   <li>标题 → 注入 {@code <a name="h-N">} 锚点，供目录跳转</li>
 *   <li>图片 / 链接 → 解析成绝对 file URI，本地图片按栏宽等比缩放</li>
 *   <li>引用 → 左侧竖线表格；任务列表 → ☑ / ☐ 字符</li>
 * </ul>
 * 纯渲染逻辑，不依赖任何 UI 类，便于单独测试。
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {
    }

    public record TocEntry(int level, String text, String anchor) {
    }

    public record Rendered(String html, List<TocEntry> toc) {
    }

    /** 渲染参数：主题、正文字号（pt）、图片最大宽度（px）。 */
    public record RenderTheme(boolean dark, int bodyPt, int maxImageWidth) {
        public static RenderTheme light() {
            return new RenderTheme(false, 10, 760);
        }
    }

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create(),
            TaskListItemsExtension.create()
    );

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private static final Pattern PRE_CODE = Pattern.compile(
            "<pre><code(?:\\s+class=\"language-([^\"]*)\")?[^>]*>([\\s\\S]*?)</code></pre>");
    private static final Pattern CHECKBOX = Pattern.compile("<input[^>]*type=\"checkbox\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_ATTR = Pattern.compile("\\b(href|src)=\"([^\"]*)\"");
    private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_SRC = Pattern.compile("\\bsrc=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_ALT = Pattern.compile("\\balt=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_WIDTH = Pattern.compile("\\bwidth=", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING_OPEN = Pattern.compile(
            "<h([1-6])\\b([^>]*\\bid=\"(h-\\d+)\"[^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern H1_CLOSE = Pattern.compile("</h1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern H2_CLOSE = Pattern.compile("</h2>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HR = Pattern.compile("<hr\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern THEAD_TBODY = Pattern.compile("</?(?:thead|tbody)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_OPEN = Pattern.compile("<table>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TH_OPEN = Pattern.compile("<th(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCKQUOTE_OPEN = Pattern.compile("<blockquote>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCKQUOTE_CLOSE = Pattern.compile("</blockquote>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");

    // ===========================================================
    //  对外入口
    // ===========================================================

    public static Rendered render(Path sourceFile, String markdown, RenderTheme theme) {
        Node document = PARSER.parse(markdown == null ? "" : markdown);

        List<Heading> headingNodes = new ArrayList<>();
        collectHeadingNodes(document, headingNodes);
        Map<String, Integer> slugCounts = new LinkedHashMap<>();
        Map<String, String> slugByAnchor = new LinkedHashMap<>();
        List<TocEntry> toc = new ArrayList<>(headingNodes.size());
        for (int i = 0; i < headingNodes.size(); i++) {
            Heading heading = headingNodes.get(i);
            String text = textOf(heading).trim();
            String anchor = "h-" + (i + 1);
            String slug = uniqueSlug(slugify(text), slugCounts);
            toc.add(new TocEntry(heading.getLevel(), text, anchor));
            slugByAnchor.put(anchor, slug);
        }

        int[] headingSeq = {0};
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(EXTENSIONS)
                .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                    if (node instanceof Heading) {
                        attributes.put("id", "h-" + (++headingSeq[0]));
                    }
                })
                .build();
        String html = renderer.render(document);

        Palette palette = theme.dark() ? Palette.DARK : Palette.LIGHT;
        Path baseDir = sourceFile == null ? null
                : (sourceFile.getParent() == null ? sourceFile.toAbsolutePath() : sourceFile.getParent());

        // 1. 先把代码块摘出来，避免后续 URL / 图片 / 标题处理误伤代码内容
        List<String> codeBlocks = new ArrayList<>();
        html = extractCodeBlocks(html, codeBlocks, theme, palette);

        // 2. 任务列表复选框、表格、按钮之类的小修补
        html = CHECKBOX.matcher(html).replaceAll(match ->
                match.group().toLowerCase(Locale.ROOT).contains("checked")
                        ? "<font color=\"" + palette.green + "\">&#8730;</font> "
                        : "<font color=\"" + palette.muted + "\">&#9675;</font> ");
        html = THEAD_TBODY.matcher(html).replaceAll("");
        html = TABLE_OPEN.matcher(html).replaceAll("<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\">");
        html = TH_OPEN.matcher(html).replaceAll("<th bgcolor=\"" + palette.tableHeaderBg + "\"$1>");

        // 3. 链接、图片路径 → 绝对路径
        if (baseDir != null) {
            html = resolveUrls(html, baseDir);
            html = processImages(html, baseDir, theme, palette);
        }

        // 4. 标题锚点 + 分割线（GitHub 的 h1/h2 下边线）
        html = HEADING_OPEN.matcher(html).replaceAll(match -> {
            String anchor = match.group(3);
            String slug = slugByAnchor.get(anchor);
            String anchors = "<a name=\"" + anchor + "\"></a>"
                    + (slug == null ? "" : "<a name=\"" + escapeAttribute(slug) + "\"></a>");
            return anchors + match.group();
        });
        String divider = divider(palette);
        html = H1_CLOSE.matcher(html).replaceAll("</h1>" + divider);
        html = H2_CLOSE.matcher(html).replaceAll("</h2>" + divider);
        html = HR.matcher(html).replaceAll(divider);

        // 5. 引用块左侧竖线
        html = BLOCKQUOTE_OPEN.matcher(html).replaceAll(
                "<table width=\"100%\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\"><tr>"
                        + "<td width=\"3\" bgcolor=\"" + palette.border + "\"></td>"
                        + "<td width=\"12\"></td><td>");
        html = BLOCKQUOTE_CLOSE.matcher(html).replaceAll("</td></tr></table>");

        // 6. 还原代码块
        html = restoreCodeBlocks(html, codeBlocks);

        String full = wrapHtml(html, theme, palette);
        return new Rendered(full, toc);
    }

    // ===========================================================
    //  标题 / 摘要（项目列表与概览页用，不产生 UI）
    // ===========================================================

    public static String extractTitle(String markdown) {
        if (markdown == null) return null;
        for (String raw : markdown.split("\\r?\\n", -1)) {
            String line = raw.trim();
            if (line.startsWith("# ")) {
                String title = plainInline(line.substring(2)).trim();
                if (!title.isEmpty()) return title.length() > 120 ? title.substring(0, 120) + "…" : title;
            }
        }
        return null;
    }

    public static String extractSummary(String markdown) {
        if (markdown == null) return "";
        StringBuilder paragraph = new StringBuilder();
        boolean started = false;
        for (String raw : markdown.split("\\r?\\n", -1)) {
            String line = raw.trim();

            if (line.isEmpty()) {
                if (started) break;
                continue;
            }
            if (line.startsWith("#") || line.startsWith(">") || line.startsWith("|")
                    || line.matches("^[-*_]{3,}$") || line.startsWith("```")) {
                if (started) break;
                continue;
            }
            if (isBadgeLine(line)) {
                if (started) break;
                continue;
            }
            if (line.startsWith("<")) {
                if (started) break;
                continue;
            }
            if (line.startsWith("- [") || line.startsWith("* [")) {
                if (started) break;
                continue;
            }
            // 列表项也可以当作摘要，但去掉列表符号
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
                line = line.substring(2).trim();
            }
            started = true;
            if (paragraph.length() > 0) paragraph.append(' ');
            paragraph.append(line);
            if (paragraph.length() > 400) break;
        }
        String text = plainInline(HTML_COMMENT.matcher(paragraph).replaceAll(""))
                .replaceAll("\\s+", " ").trim();
        if (text.length() > 220) {
            text = text.substring(0, 217) + "…";
        }
        return text;
    }

    private static boolean isBadgeLine(String line) {
        String compact = line.replaceAll("\\s+", "");
        if (compact.isEmpty()) return true;
        if (compact.startsWith("[![") || compact.startsWith("![")) return true;
        // 整行都是链接/图片（典型徽章行）
        String stripped = compact.replaceAll("\\[!\\[[^]]*]\\([^)]*\\)]\\([^)]*\\)", "")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replaceAll("\\[[^]]*]\\([^)]*\\)", "");
        return compact.length() > 8 && stripped.length() < compact.length() / 4;
    }

    /** 去掉常见行内 Markdown 记号，得到纯文本。 */
    private static String plainInline(String text) {
        if (text == null) return "";
        String out = text;
        out = out.replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1");
        out = out.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1");
        out = out.replaceAll("<[^>]+>", "");
        out = out.replaceAll("`{1,3}([^`]*)`{1,3}", "$1");
        out = out.replaceAll("(\\*\\*|__)(.*?)\\1", "$2");
        out = out.replaceAll("(?<!\\w)([*_])(\\S.*?)\\1(?!\\w)", "$2");
        out = out.replaceAll("~~(.*?)~~", "$1");
        return out;
    }

    // ===========================================================
    //  HTML 后处理
    // ===========================================================

    private static String extractCodeBlocks(String html, List<String> blocks, RenderTheme theme, Palette palette) {
        Matcher matcher = PRE_CODE.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        int index = 0;
        while (matcher.find()) {
            out.append(html, last, matcher.start());
            String language = matcher.group(1) == null ? "" : matcher.group(1);
            String content = matcher.group(2);
            blocks.add(codeBlockHtml(language, content, theme, palette));
            out.append("@@RVCODE").append(index++).append("@@");
            last = matcher.end();
        }
        out.append(html.substring(last));
        return out.toString();
    }

    private static String restoreCodeBlocks(String html, List<String> blocks) {
        String out = html;
        for (int i = 0; i < blocks.size(); i++) {
            out = out.replace("@@RVCODE" + i + "@@", blocks.get(i));
        }
        return out;
    }

    private static String codeBlockHtml(String language, String escapedContent, RenderTheme theme, Palette p) {
        String content = escapedContent;
        if (content.startsWith("\n")) content = content.substring(1);
        if (content.endsWith("\n")) content = content.substring(0, content.length() - 1);

        StringBuilder lines = new StringBuilder(content.length() + 64);
        String[] split = content.split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            String line = split[i];
            int chars = 0;
            int columns = 0;
            while (chars < line.length() && (line.charAt(chars) == ' ' || line.charAt(chars) == '\t')) {
                columns += line.charAt(chars) == '\t' ? 4 : 1;
                chars++;
            }
            lines.append("&nbsp;".repeat(columns));
            lines.append(line.substring(chars).replace("\t", "    "));
            if (i < split.length - 1) lines.append("<br>");
        }

        int codePt = Math.max(8, theme.bodyPt() - 1);
        String langLabel = language == null || language.isBlank() ? ""
                : "<font color=\"" + p.muted + "\" size=\"1\">" + escapeHtml(language) + "</font><br>";
        return "<table width=\"100%\" border=\"0\" cellspacing=\"0\" cellpadding=\"10\" bgcolor=\""
                + p.codeBg + "\"><tr><td>" + langLabel
                + "<span style=\"font-family:Consolas;font-size:" + codePt + "pt;color:" + p.codeText + "\">"
                + lines + "</span></td></tr></table>";
    }

    /** 把相对链接 / 图片解析成绝对 file URI。 */
    private static String resolveUrls(String html, Path baseDir) {
        Matcher matcher = URL_ATTR.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 64);
        int last = 0;
        while (matcher.find()) {
            String attr = matcher.group(1);
            String value = unescapeAttribute(matcher.group(2));
            String resolved = resolveOne(value, baseDir);
            out.append(html, last, matcher.start());
            out.append(attr).append("=\"").append(escapeAttribute(resolved)).append('"');
            last = matcher.end();
        }
        out.append(html.substring(last));
        return out.toString();
    }

    private static String resolveOne(String value, Path baseDir) {
        if (value.isEmpty()) return value;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#") || lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("data:")
                || lower.startsWith("file:") || lower.startsWith("javascript:")) {
            return value;
        }
        String pathPart = value;
        String fragment = "";
        int hash = value.indexOf('#');
        if (hash >= 0) {
            pathPart = value.substring(0, hash);
            fragment = value.substring(hash);   // 含 #
        }
        if (pathPart.isEmpty()) return value;

        // /docs/a.png 这类以 / 开头的路径，按“相对当前文件夹”近似处理
        while (pathPart.startsWith("/") || pathPart.startsWith("\\")) {
            pathPart = pathPart.substring(1);
        }
        try {
            Path target = baseDir.resolve(pathPart).normalize();
            return target.toUri().toString() + fragment;
        } catch (Exception e) {
            return value;
        }
    }

    /** 本地图片按栏宽缩放；SVG 无法用 ImageIO 渲染，退化成文字链接。 */
    private static String processImages(String html, Path baseDir, RenderTheme theme, Palette p) {
        Matcher matcher = IMG_TAG.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        while (matcher.find()) {
            String tag = matcher.group();
            out.append(html, last, matcher.start());
            out.append(imageReplacement(tag, baseDir, theme, p));
            last = matcher.end();
        }
        out.append(html.substring(last));
        return out.toString();
    }

    private static String imageReplacement(String tag, Path baseDir, RenderTheme theme, Palette p) {
        Matcher srcMatcher = IMG_SRC.matcher(tag);
        if (!srcMatcher.find()) return tag;
        String src = unescapeAttribute(srcMatcher.group(1));
        String alt = "";
        Matcher altMatcher = IMG_ALT.matcher(tag);
        if (altMatcher.find()) alt = unescapeAttribute(altMatcher.group(1));

        String lower = src.toLowerCase(Locale.ROOT);
        boolean remote = lower.startsWith("http://") || lower.startsWith("https://");

        if (lower.endsWith(".svg") || lower.contains(".svg?")) {
            if (remote) return "";   // 远程徽章通常就是 SVG，直接去掉更清爽
            return "<a href=\"" + escapeAttribute(src) + "\">[SVG 图片" + (alt.isBlank() ? "" : "：" + escapeHtml(alt)) + "]</a>";
        }
        if (remote) return tag;      // 远程位图交给 JEditorPane 异步加载
        if (IMG_WIDTH.matcher(tag).find()) return tag;

        try {
            Path file = src.startsWith("file:")
                    ? Path.of(URI.create(src))
                    : baseDir.resolve(unescapeAttribute(src)).normalize();
            if (!Files.isRegularFile(file) || Files.size(file) > 20L * 1024 * 1024) return tag;
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) return tag;
            int width = image.getWidth();
            int height = image.getHeight();
            int max = Math.max(320, theme.maxImageWidth());
            if (width > max) {
                int newHeight = (int) Math.round(height * (max / (double) width));
                return tag.replaceAll("\\s*/?>$", "")
                        + " width=\"" + max + "\" height=\"" + newHeight + "\">";
            }
        } catch (Exception ignored) {
        }
        return tag;
    }

    private static String divider(Palette p) {
        // HRuleView 的线条颜色由背景色推导，没法用 CSS 调浅，这里用 1pt 表格模拟 1px 细线
        return "<table width=\"100%\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\"><tr>"
                + "<td height=\"1\" bgcolor=\"" + p.hr
                + "\" style=\"font-size:1pt;line-height:1pt\">&nbsp;</td></tr></table>";
    }

    private static String wrapHtml(String body, RenderTheme theme, Palette p) {
        int bodyPt = theme.bodyPt();
        String css = """
                body { font-family: "Microsoft YaHei UI"; font-size: %dpt; color: %s; }
                p { margin-top: 0pt; margin-bottom: 8pt; }
                h1, h2, h3, h4, h5, h6 { font-family: "Microsoft YaHei UI"; font-weight: bold; color: %s; margin-top: 12pt; margin-bottom: 6pt; }
                h1 { font-size: %dpt; }
                h2 { font-size: %dpt; }
                h3 { font-size: %dpt; }
                h4, h5, h6 { font-size: %dpt; }
                a { color: %s; }
                code { font-family: Consolas; font-size: %dpt; color: %s; }
                blockquote { color: %s; }
                ul, ol { margin-top: 0pt; margin-bottom: 8pt; }
                li { margin-bottom: 3pt; }
                td, th { font-size: %dpt; }
                th { font-weight: bold; color: %s; }
                hr { color: %s; }
                """.formatted(
                bodyPt, p.text,
                p.text,
                bodyPt + 7, bodyPt + 3, bodyPt + 1, bodyPt,
                p.accent,
                Math.max(8, bodyPt - 1), p.codeFg,
                p.muted,
                Math.max(8, bodyPt - 1),
                p.text, p.hr);

        return "<html><head><style>\n" + css + "</style></head><body>" + body + "</body></html>";
    }

    // ===========================================================
    //  小工具
    // ===========================================================

    private static void collectHeadingNodes(Node node, List<Heading> headings) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) headings.add(heading);
            collectHeadingNodes(child, headings);
        }
    }

    /** GitHub 风格的标题 slug：保留字母数字和中文，空格转连字符，其余符号丢弃。 */
    private static String slugify(String text) {
        String lower = text.toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastDash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
                lastDash = false;
            } else if (Character.isWhitespace(c) || c == '-') {
                if (!lastDash && sb.length() > 0) {
                    sb.append('-');
                    lastDash = true;
                }
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.isEmpty() ? "section" : sb.toString();
    }

    private static String uniqueSlug(String base, Map<String, Integer> counts) {
        int count = counts.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "-" + (count - 1);
    }

    private static String textOf(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text text) {
                sb.append(text.getLiteral());
            } else if (child instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                sb.append(' ');
            } else if (child instanceof Image) {
                sb.append(textOf(child));
            } else {
                sb.append(textOf(child));
            }
        }
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private static String unescapeAttribute(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    /** 主题色：和 UI 层的调色板保持同一套值。 */
    private record Palette(String text, String muted, String accent, String border, String hr,
                           String codeBg, String codeText, String codeFg, String tableHeaderBg, String green) {
        static final Palette LIGHT = new Palette(
                "#1F2328", "#59636E", "#0969DA", "#D0D7DE", "#D8DEE4",
                "#F6F8FA", "#24292F", "#CF222E", "#F6F8FA", "#1A7F37");
        static final Palette DARK = new Palette(
                "#E6EDF3", "#8D96A0", "#4493F8", "#30363D", "#30363D",
                "#161B22", "#E6EDF3", "#FF7B72", "#161B22", "#3FB950");
    }
}
