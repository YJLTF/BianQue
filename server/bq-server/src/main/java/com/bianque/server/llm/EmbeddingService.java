package com.bianque.server.llm;

import com.bianque.server.service.DocsService;
import com.bianque.server.store.DocEmbeddingEntity;
import com.bianque.server.store.DocEmbeddingJpa;
import com.bianque.server.store.LlmConfigEntity;
import com.bianque.server.store.LlmConfigJpa;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档向量化（RAG 升级）：OpenAI 兼容 /embeddings 端点 → 按页分块建索引（内容 hash 增量）→
 * 余弦 Top-K 检索。未配置端点或检索异常时返回空，调用方回落现有关键词检索（内网离线零负担）。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    /** 分块目标长度（字符）：页 excerpt 3000 字 → 约 3 块 */
    static final int CHUNK_SIZE = 1100;
    static final int CHUNK_OVERLAP = 120;

    private final LlmConfigJpa cfgRepo;
    private final DocEmbeddingJpa vecRepo;
    private final DocsService docs;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** 向量内存缓存：slug → 行列表（首查加载；重建后失效） */
    private volatile List<DocEmbeddingEntity> cache = List.of();
    private final AtomicBoolean cacheLoaded = new AtomicBoolean(false);
    private final AtomicBoolean indexing = new AtomicBoolean(false);

    public EmbeddingService(LlmConfigJpa cfgRepo, DocEmbeddingJpa vecRepo, DocsService docs, ObjectMapper json) {
        this.cfgRepo = cfgRepo;
        this.vecRepo = vecRepo;
        this.docs = docs;
        this.json = json;
    }

    public record EmbConfig(String baseUrl, String apiKey, String model, int dims) {
    }

    /** embedding 配置（未配置返回 empty）；dims 缺省 1024 */
    public java.util.Optional<EmbConfig> config() {
        LlmConfigEntity e = cfgRepo.findById(1L).orElse(null);
        if (e == null || e.embedBaseUrl == null || e.embedBaseUrl.isBlank()
                || e.embedModel == null || e.embedModel.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new EmbConfig(e.embedBaseUrl.trim(), e.embedApiKey == null ? "" : e.embedApiKey.trim(),
                e.embedModel.trim(), e.embedDims == null || e.embedDims <= 0 ? 1024 : e.embedDims));
    }

    public boolean configured() {
        return config().isPresent();
    }

    /** 调 /embeddings（批量）；失败抛 502 语义异常（调用方降级） */
    public List<double[]> embed(EmbConfig cfg, List<String> inputs) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.model());
            body.put("input", inputs);
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl().replaceAll("/$", "") + "/embeddings"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "embedding 端点返回 " + resp.statusCode() + "：" + truncate(resp.body()));
            }
            JsonNode data = json.readTree(resp.body()).path("data");
            List<double[]> out = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode arr = item.path("embedding");
                double[] v = new double[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = arr.get(i).asDouble();
                }
                out.add(v);
            }
            if (out.size() != inputs.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "embedding 返回数量不匹配：请求 " + inputs.size() + " 回 " + out.size());
            }
            return out;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "embedding 端点不可达：" + e.getMessage());
        }
    }

    /** 余弦相似度（零向量返回 0） */
    public static double cosine(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na <= 0 || nb <= 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ---- 索引 ----

    /** 按内容 hash 增量重建全量索引；返回统计 */
    public synchronized Map<String, Object> reindex() {
        EmbConfig cfg = config().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "未配置 embedding 端点：请先在「AI 问诊 → 模型接入」填写向量检索配置"));
        if (!indexing.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "索引重建进行中，请稍候");
        }
        try {
            int pages = 0, chunks = 0, skipped = 0;
            for (DocsService.DocHit page : docs.catalog()) {
                pages++;
                List<String> parts = chunk(page.snippet());
                String pageHash = hash(page.snippet());
                List<DocEmbeddingEntity> existing = vecRepo.findBySlug(page.slug());
                boolean unchanged = existing.size() == parts.size()
                        && existing.stream().allMatch(e -> pageHash.equals(e.contentHash));
                if (unchanged) {
                    skipped += parts.size();
                    continue;
                }
                List<double[]> vectors = embed(cfg, parts);
                vecRepo.deleteBySlug(page.slug());
                for (int i = 0; i < parts.size(); i++) {
                    vecRepo.save(new DocEmbeddingEntity(page.slug(), i, page.title(), parts.get(i),
                            toJson(vectors.get(i)), vectors.get(i).length, cfg.model(), pageHash));
                    chunks++;
                }
            }
            invalidateCache();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pages", pages);
            out.put("indexed", chunks);
            out.put("unchangedChunks", skipped);
            out.put("model", cfg.model());
            log.info("文档向量索引完成：{} 页 / {} 块新增", pages, chunks);
            return out;
        } finally {
            indexing.set(false);
        }
    }

    /** 启动后台建索引：已配置且库为空时触发一次（独立线程，不阻塞启动；失败不影响功能） */
    public void reindexIfEmptyAsync() {
        if (!configured() || vecRepo.count() > 0) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                reindex();
            } catch (Exception e) {
                log.warn("启动时文档向量索引失败（可稍后手动重建）: {}", e.getMessage());
            }
        }, "doc-embedding-index");
        t.setDaemon(true);
        t.start();
    }

    // ---- 检索 ----

    public record VecHit(String slug, String title, String path, int chunkIdx, double score, String content) {
    }

    /** 向量检索 Top-K；未配置/异常返回空列表（调用方回落关键词） */
    public List<VecHit> search(String query, int k) {
        EmbConfig cfg = config().orElse(null);
        if (cfg == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            double[] qv = embed(cfg, List.of(query)).get(0);
            List<Map.Entry<DocEmbeddingEntity, Double>> scored = new ArrayList<>();
            for (DocEmbeddingEntity row : rows()) {
                double[] v = fromJson(row.vecJson);
                if (v.length == 0) {
                    continue;
                }
                scored.add(Map.entry(row, cosine(v, qv)));
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<VecHit> out = new ArrayList<>();
            for (int i = 0; i < Math.min(k, scored.size()); i++) {
                DocEmbeddingEntity row = scored.get(i).getKey();
                out.add(new VecHit(row.slug, row.title, "doc/" + row.slug + ".html", row.chunkIdx,
                        scored.get(i).getValue(), row.content));
            }
            return out;
        } catch (Exception e) {
            log.warn("向量检索失败，回落关键词: {}", e.getMessage());
            return List.of();
        }
    }

    /** 状态（管理页展示） */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", configured());
        m.put("chunks", vecRepo.count());
        m.put("indexing", indexing.get());
        config().ifPresent(c -> {
            m.put("model", c.model());
            m.put("dims", c.dims());
        });
        return m;
    }

    // ---- helpers ----

    private List<DocEmbeddingEntity> rows() {
        if (!cacheLoaded.get()) {
            synchronized (this) {
                if (!cacheLoaded.get()) {
                    cache = vecRepo.findAll();
                    cacheLoaded.set(true);
                }
            }
        }
        return cache;
    }

    public void invalidateCache() {
        cache = List.of();
        cacheLoaded.set(false);
    }

    /** 定长滑窗分块（带重叠），块数不足 2 时整段单块 */
    static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        if (text.length() <= CHUNK_SIZE) {
            out.add(text);
            return out;
        }
        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        for (int i = 0; i < text.length(); i += step) {
            out.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
            if (i + CHUNK_SIZE >= text.length()) {
                break;
            }
        }
        return out;
    }

    private String toJson(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(java.math.BigDecimal.valueOf(v[i]).setScale(6, java.math.RoundingMode.HALF_UP));
        }
        return sb.append(']').toString();
    }

    private double[] fromJson(String s) {
        try {
            JsonNode arr = json.readTree(s == null ? "[]" : s);
            double[] v = new double[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = arr.get(i).asDouble();
            }
            return v;
        } catch (Exception e) {
            return new double[0];
        }
    }

    static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.valueOf((s == null ? "" : s).hashCode());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
