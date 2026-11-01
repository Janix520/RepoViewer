package com.repoviewer.core;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MultiCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档 / 代码的 Lucene 索引与检索。
 * <p>
 * 和 SecondSearch 的思路一致：文件名、内容各建一份小写非分词字段，
 * 查询时用 WildcardQuery 做“大小写不敏感的子串匹配”，对中文最直观。
 * 内容会被切成带重叠的块分别建 Field，避免 Lucene 单 term 32KB 上限导致长文件搜不到。
 * <p>
 * 增量策略：索引目录边上的指纹文件记录 path → (mtime,size)，只有变化的文件才重新读盘。
 */
public final class RepoIndex implements Closeable {

    public enum Scope { ALL, MARKDOWN, CODE }

    public record Doc(Path file, RepoProject project, boolean markdown) {
    }

    public record Hit(Path file, String name, String projectPath, boolean markdown,
                      long size, long modified, String snippet, int line) {
    }

    /** 一个项目里命中的文档数量，用于“按项目找结果”。 */
    public record ProjectCount(String projectPath, int count) {
    }

    public record Outcome(List<Hit> hits, List<ProjectCount> projects,
                          long totalHits, long elapsedMs) {
    }

    public record SyncResult(int indexed, int skipped, int deleted, long elapsedMs) {
    }

    /** 索引结构变化时递增，旧索引会在下次同步时整体重建。 */
    private static final int INDEX_VERSION = 3;

    private static final String F_ID = "id";
    private static final String F_NAME = "name";
    private static final String F_NAME_LOWER = "nameLower";
    private static final String F_EXT = "ext";
    private static final String F_KIND = "kind";
    private static final String F_PROJECT = "project";
    private static final String F_PROJECT_DV = "projectDV";
    private static final String F_SCOPE = "scope";
    private static final String F_SIZE = "size";
    private static final String F_MODIFIED = "modified";
    private static final String F_CONTENT = "content";
    private static final String F_README = "readme";

    private static final int CHUNK_CHARS = 6000;
    private static final int CHUNK_OVERLAP = 200;
    private static final int SNIPPET_BYTES = 128 * 1024;
    private static final int SNIPPET_LIMIT = 80;

    private final Path indexPath;
    private final Directory directory;
    private final Analyzer analyzer = new StandardAnalyzer();
    private final IndexWriter writer;
    private final Path fingerprintFile;
    private DirectoryReader reader;
    private IndexSearcher searcher;
    private final Map<String, String> fingerprints = new HashMap<>();
    private String rootKey = "";

    public RepoIndex(Path indexDir) throws IOException {
        this.indexPath = indexDir;
        Files.createDirectories(indexDir);
        this.directory = FSDirectory.open(indexDir);
        this.fingerprintFile = indexDir.resolve("fingerprints.txt");
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(48);
        this.writer = new IndexWriter(directory, config);
        loadFingerprints();
        refreshReader();
        if (reader.numDocs() == 0) fingerprints.clear();
    }

    // ===========================================================
    //  增量同步
    // ===========================================================

    public synchronized SyncResult sync(Collection<Doc> docs, String newRootKey,
                                        Consumer<String> progress, AtomicBoolean cancel) throws IOException {
        long start = System.currentTimeMillis();

        String targetRootKey = versionedRoot(newRootKey);
        if (!rootKey.equals(targetRootKey)) {
            writer.deleteAll();
            fingerprints.clear();
            rootKey = targetRootKey;
        }

        Map<String, Doc> current = new LinkedHashMap<>();
        for (Doc doc : docs) {
            current.put(idOf(doc.file()), doc);
        }

        int deleted = 0;
        int indexed = 0;
        int skipped = 0;
        int processed = 0;

        // 删除已经不存在的文件
        for (String oldId : new ArrayList<>(fingerprints.keySet())) {
            if (!current.containsKey(oldId)) {
                writer.deleteDocuments(new Term(F_ID, oldId));
                fingerprints.remove(oldId);
                deleted++;
            }
        }

        for (Map.Entry<String, Doc> entry : current.entrySet()) {
            if (cancel != null && cancel.get()) break;
            String id = entry.getKey();
            Doc doc = entry.getValue();
            String fingerprint = fingerprintOf(doc.file());
            if (fingerprint == null) {
                continue;
            }
            if (fingerprint.equals(fingerprints.get(id))) {
                skipped++;
            } else {
                try {
                    String text = FileTypes.readText(doc.file(),
                            doc.markdown() ? FileTypes.MAX_DOC_BYTES : FileTypes.MAX_CODE_BYTES);
                    if (!text.isBlank()) {
                        writer.updateDocument(new Term(F_ID, id), toDocument(doc, id, text));
                        fingerprints.put(id, fingerprint);
                        indexed++;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // 单个文件读失败不影响整体
                }
            }
            processed++;
            if (progress != null && processed % 200 == 0) {
                progress.accept("正在建立索引… " + processed + " / " + current.size()
                        + "（更新 " + indexed + "，跳过 " + skipped + "）");
            }
        }

        writer.commit();
        refreshReader();
        saveFingerprints();
        return new SyncResult(indexed, skipped, deleted, System.currentTimeMillis() - start);
    }

    // ===========================================================
    //  检索
    // ===========================================================

    public synchronized List<Hit> search(String query, Scope scope, Path projectScope, int limit) throws IOException {
        refreshReader();
        if (reader.numDocs() == 0) return List.of();

        String trimmed = query == null ? "" : query.trim();
        Query luceneQuery = buildFilteredQuery(trimmed, scope, projectScope);
        TopDocs topDocs = searcher.search(luceneQuery, Math.max(1, limit));
        return toHits(topDocs, trimmed);
    }

    /**
     * 一次检索同时返回：
     * <ul>
     *   <li>按相关度排序的文档（用于结果列表）；</li>
     *   <li>所有命中项目及数量（不受 limit 截断，用于“按项目找”下拉框）。</li>
     * </ul>
     */
    public synchronized Outcome searchWithProjects(String query, Scope scope, Path projectScope,
                                                   int limit) throws IOException {
        long start = System.currentTimeMillis();
        refreshReader();
        if (reader.numDocs() == 0) {
            return new Outcome(List.of(), List.of(), 0, 0);
        }

        String trimmed = query == null ? "" : query.trim();
        Query luceneQuery = buildFilteredQuery(trimmed, scope, projectScope);

        ProjectCounter counter = new ProjectCounter();
        TopScoreDocCollector topCollector = TopScoreDocCollector.create(
                Math.max(1, limit), Integer.MAX_VALUE);
        searcher.search(luceneQuery, MultiCollector.wrap(topCollector, counter));
        TopDocs topDocs = topCollector.topDocs();
        List<Hit> hits = toHits(topDocs, trimmed);
        return new Outcome(hits, counter.results(), counter.total, System.currentTimeMillis() - start);
    }

    private List<Hit> toHits(TopDocs topDocs, String query) throws IOException {
        List<Hit> hits = new ArrayList<>(topDocs.scoreDocs.length);
        List<String> snippetTerms = snippetTerms(query);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = searcher.storedFields().document(sd.doc);
            Path file = Path.of(doc.get(F_ID));
            boolean markdown = "md".equals(doc.get(F_KIND));
            String snippet = "";
            int line = 0;
            if (hits.size() < SNIPPET_LIMIT) {
                Snippet s = snippet(file, snippetTerms);
                snippet = s.html();
                line = s.line();
            }
            hits.add(new Hit(file,
                    doc.get(F_NAME),
                    doc.get(F_PROJECT),
                    markdown,
                    parseLong(doc.get(F_SIZE)),
                    parseLong(doc.get(F_MODIFIED)),
                    snippet,
                    line));
        }
        return hits;
    }

    private Query buildFilteredQuery(String query, Scope scope, Path projectScope) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(buildQuery(query), BooleanClause.Occur.MUST);
        if (scope == Scope.MARKDOWN) {
            builder.add(new TermQuery(new Term(F_KIND, "md")), BooleanClause.Occur.FILTER);
        } else if (scope == Scope.CODE) {
            builder.add(new TermQuery(new Term(F_KIND, "code")), BooleanClause.Occur.FILTER);
        }
        if (projectScope != null) {
            String scoped = projectScope.toAbsolutePath().normalize().toString();
            builder.add(new TermQuery(new Term(F_SCOPE, scoped)), BooleanClause.Occur.FILTER);
        }
        // README 是项目的入口，内容命中时优先展示它，方便直接判断是哪个项目
        builder.add(new BoostQuery(new TermQuery(new Term(F_README, "1")), 12f),
                BooleanClause.Occur.SHOULD);
        return builder.build();
    }

    public synchronized int documentCount() throws IOException {
        refreshReader();
        return reader.numDocs();
    }

    private Query buildQuery(String query) {
        if (query.isEmpty()) return new MatchAllDocsQuery();

        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) return new MatchAllDocsQuery();

        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String term : terms) {
            String pattern = toWildcard(term);
            BooleanQuery.Builder one = new BooleanQuery.Builder();
            one.add(new BoostQuery(new WildcardQuery(new Term(F_NAME_LOWER, pattern)), 5.0f),
                    BooleanClause.Occur.SHOULD);
            one.add(new WildcardQuery(new Term(F_CONTENT, pattern)), BooleanClause.Occur.SHOULD);
            all.add(one.build(), BooleanClause.Occur.MUST);
        }
        return all.build();
    }

    /**
     * 查询词拆分：按空白切分，最多 6 个；去掉首尾的中英文标点，
     * 这样“安装，”或“quick start.”这类自然语言输入也能命中。
     */
    private static List<String> queryTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (String raw : query.split("[\\s\\u3000]+")) {
            String term = stripEdgePunctuation(raw);
            if (term.isEmpty()) continue;
            terms.add(term);
            if (terms.size() >= 6) break;
        }
        return terms;
    }

    private static String stripEdgePunctuation(String term) {
        int start = 0;
        int end = term.length();
        while (start < end && isEdgePunctuation(term.charAt(start))) start++;
        while (end > start && isEdgePunctuation(term.charAt(end - 1))) end--;
        return term.substring(start, end);
    }

    private static boolean isEdgePunctuation(char c) {
        return "，。！？；：、,.!?;:\"'“”‘’()（）[]【】{}<>《》…—-".indexOf(c) >= 0;
    }

    /** 普通词包成 *term*，用户自己写了 * / ? 则按通配符处理。 */
    private static String toWildcard(String term) {
        String lower = term.toLowerCase(Locale.ROOT);
        if (!lower.contains("*") && !lower.contains("?")) {
            lower = "*" + lower + "*";
        }
        StringBuilder sb = new StringBuilder(lower.length() + 8);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '*' || c == '?') {
                sb.append(c);
            } else if ("\\+-!()^:[]{}~\"".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private List<String> snippetTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (String term : queryTerms(query)) {
            String core = term.replace("*", "").replace("?", "").trim();
            if (core.isEmpty()) core = term;
            terms.add(core.toLowerCase(Locale.ROOT));
        }
        return terms;
    }

    // ===========================================================
    //  摘要片段
    // ===========================================================

    private record Snippet(String html, int line) {
    }

    private Snippet snippet(Path file, List<String> terms) {
        String text;
        try {
            text = FileTypes.readText(file, SNIPPET_BYTES);
        } catch (IOException e) {
            return new Snippet("", 0);
        }
        if (text.isEmpty()) return new Snippet("", 0);

        String lower = text.toLowerCase(Locale.ROOT);
        int best = -1;
        int bestLen = 0;
        for (String term : terms) {
            int idx = lower.indexOf(term);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
                bestLen = term.length();
            }
        }

        int line = best < 0 ? 0 : (int) text.substring(0, best).chars().filter(c -> c == '\n').count() + 1;

        int start;
        int end;
        if (best < 0) {
            start = 0;
            end = Math.min(text.length(), 260);
        } else {
            start = Math.max(0, best - 120);
            end = Math.min(text.length(), best + bestLen + 220);
            int nl = text.lastIndexOf('\n', start);
            if (nl > 0 && start - nl < 40) start = nl + 1;
        }

        String raw = text.substring(start, end).replaceAll("\\s+", " ").trim();
        String html = highlight(raw, terms);
        if (start > 0) html = "…" + html;
        if (end < text.length()) html = html + "…";
        return new Snippet(html, line);
    }

    private static String highlight(String raw, List<String> terms) {
        String escaped = escapeHtml(raw);
        String result = escaped;
        for (String term : terms) {
            if (term.isEmpty()) continue;
            String escapedTerm = escapeHtml(term);
            result = result.replaceAll("(?i)" + Pattern.quote(escapedTerm),
                    Matcher.quoteReplacement("<font color=\"#D97706\"><b>") + "$0</font></b>");
        }
        return result;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ===========================================================
    //  Lucene Document 映射
    // ===========================================================

    private Document toDocument(Doc doc, String id, String text) {
        String fileName = doc.file().getFileName() == null ? id : doc.file().getFileName().toString();
        Document luceneDoc = new Document();
        luceneDoc.add(new StringField(F_ID, id, Field.Store.YES));
        luceneDoc.add(new StringField(F_NAME, fileName, Field.Store.YES));
        luceneDoc.add(new StringField(F_NAME_LOWER, fileName.toLowerCase(Locale.ROOT), Field.Store.NO));
        luceneDoc.add(new StringField(F_EXT, FileTypes.extension(doc.file()), Field.Store.YES));
        luceneDoc.add(new StringField(F_KIND, doc.markdown() ? "md" : "code", Field.Store.YES));
        String projectPath = doc.project().getRoot().toString();
        luceneDoc.add(new StringField(F_PROJECT, projectPath, Field.Store.YES));
        luceneDoc.add(new SortedDocValuesField(F_PROJECT_DV, new BytesRef(projectPath)));
        Path readme = doc.project().getReadme();
        if (readme != null && readme.toAbsolutePath().normalize()
                .equals(doc.file().toAbsolutePath().normalize())) {
            luceneDoc.add(new StringField(F_README, "1", Field.Store.NO));
        }
        for (String scope : doc.project().ancestorRoots()) {
            luceneDoc.add(new StringField(F_SCOPE, scope, Field.Store.NO));
        }
        long size = 0;
        long modified = 0;
        try {
            size = Files.size(doc.file());
            modified = Files.getLastModifiedTime(doc.file()).toMillis();
        } catch (IOException ignored) {
        }
        luceneDoc.add(new StoredField(F_SIZE, size));
        luceneDoc.add(new StoredField(F_MODIFIED, modified));

        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.isEmpty()) {
            int step = CHUNK_CHARS - CHUNK_OVERLAP;
            for (int start = 0; start < lower.length(); start += step) {
                int end = Math.min(lower.length(), start + CHUNK_CHARS);
                luceneDoc.add(new StringField(F_CONTENT, lower.substring(start, end), Field.Store.NO));
                if (end == lower.length()) break;
            }
        }
        return luceneDoc;
    }

    // ===========================================================
    //  指纹 / 生命周期
    // ===========================================================

    private static String idOf(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    private static String versionedRoot(String root) {
        return "v" + INDEX_VERSION + "|" + root;
    }

    private static String fingerprintOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    private void loadFingerprints() {
        if (!Files.isRegularFile(fingerprintFile)) return;
        try {
            List<String> lines = Files.readAllLines(fingerprintFile, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return;
            rootKey = new String(Base64.getDecoder().decode(lines.get(0)), StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String path = new String(Base64.getDecoder().decode(line.substring(0, tab)), StandardCharsets.UTF_8);
                fingerprints.put(path, line.substring(tab + 1));
            }
        } catch (Exception ignored) {
            fingerprints.clear();
            rootKey = "";
        }
    }

    private void saveFingerprints() {
        try {
            List<String> lines = new ArrayList<>(fingerprints.size() + 1);
            lines.add(Base64.getEncoder().encodeToString(rootKey.getBytes(StandardCharsets.UTF_8)));
            for (Map.Entry<String, String> entry : fingerprints.entrySet()) {
                lines.add(Base64.getEncoder().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8))
                        + "\t" + entry.getValue());
            }
            Path tmp = fingerprintFile.resolveSibling("fingerprints.tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, fingerprintFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private void refreshReader() throws IOException {
        if (reader == null) {
            reader = DirectoryReader.open(writer);
        } else {
            DirectoryReader changed = DirectoryReader.openIfChanged(reader);
            if (changed != null) {
                reader.close();
                reader = changed;
            }
        }
        searcher = new IndexSearcher(reader);
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 统计每个项目命中的全部文档数；不受结果列表 limit 限制。 */
    private final class ProjectCounter extends SimpleCollector {
        private SortedDocValues projectValues;
        private final Map<String, Integer> counts = new HashMap<>();
        private long total;

        @Override
        protected void doSetNextReader(LeafReaderContext context) {
            try {
                projectValues = DocValues.getSorted(context.reader(), F_PROJECT_DV);
            } catch (Exception e) {
                projectValues = null;
            }
        }

        @Override
        public void collect(int doc) throws IOException {
            total++;
            if (projectValues != null && projectValues.advanceExact(doc)) {
                String key = projectValues.lookupOrd(projectValues.ordValue()).utf8ToString();
                counts.merge(key, 1, Integer::sum);
            }
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }

        private List<ProjectCount> results() {
            return counts.entrySet().stream()
                    .map(e -> new ProjectCount(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingInt(ProjectCount::count).reversed()
                            .thenComparing(ProjectCount::projectPath))
                    .toList();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (reader != null) reader.close();
        writer.close();
        directory.close();
    }
}
