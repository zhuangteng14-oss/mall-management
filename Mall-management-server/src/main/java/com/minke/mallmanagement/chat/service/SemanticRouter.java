package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义路由：启动时把注册表里的标准问法向量化并缓存，运行时只算余弦相似度。
 *
 * <h2>【护栏】</h2>
 * 最高相似度低于 {@code chat.embedding.threshold}（默认 <b>0.75</b>）时**不猜**：
 * {@link Result#confident()} 为 {@code false}，编排层回澄清问句。
 *
 * <h2>【降级】</h2>
 * 未配置 Key / 启动预加载失败 / 单次请求失败 ⇒ {@link #route} 返回 {@code null}，
 * 编排层退回既有的 LLM 分类器与规则引擎。语义层故障不会让客服失联。
 *
 * <h2>【不做的事】</h2>
 * 不查库、不拼话术、不决定「引导还是直达」（后者是用户词面事实，见
 * {@code ChatAgentService.isDirectNavigation}）。
 */
@Service
public class SemanticRouter {

    private static final Logger log = LoggerFactory.getLogger(SemanticRouter.class);

    static final double DEFAULT_THRESHOLD = 0.75;
    /** 预加载失败后 30s 内不再重试，避免每条消息都打一次远端。 */
    static final long RETRY_AFTER_FAILURE_MS = 30_000;
    private static final int QUERY_CACHE_SIZE = 256;

    private final TextEmbedder embedder;
    private final double threshold;
    private final boolean enabled;
    private final boolean preload;

    private final Object loadLock = new Object();
    private volatile List<Entry> entries;
    private volatile long failedAt;

    private final Map<String, float[]> queryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > QUERY_CACHE_SIZE;
                }
            });

    @Autowired
    public SemanticRouter(ChatEmbeddingClient embedder,
                          @Value("${chat.embedding.enabled:true}") boolean enabled,
                          @Value("${chat.embedding.threshold:0.75}") double threshold,
                          @Value("${chat.embedding.preload:true}") boolean preload) {
        this((TextEmbedder) embedder, enabled, threshold, preload);
    }

    /** 测试用：可注入内存替身、关掉启动预加载。 */
    SemanticRouter(TextEmbedder embedder, boolean enabled, double threshold, boolean preload) {
        this.embedder = embedder;
        this.enabled = enabled;
        this.threshold = threshold > 0 && threshold <= 1 ? threshold : DEFAULT_THRESHOLD;
        this.preload = preload;
    }

    /**
     * 启动预加载（异步、守护线程）。
     *
     * <p>不阻塞 Spring 启动：Embedding 是远端调用，挂了也不该让整个应用起不来；
     * 第一条请求若还没预热完，会在 {@link #ensureLoaded()} 里等这一次加载。
     */
    @PostConstruct
    void warmUp() {
        if (!isEnabled() || !preload) {
            return;
        }
        Thread thread = new Thread(() -> {
            List<Entry> loaded = ensureLoaded();
            if (loaded != null) {
                log.info("语义路由预加载完成：{} 条标准问法，阈值 {}", loaded.size(), threshold);
            }
        }, "semantic-router-preload");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isEnabled() {
        return enabled && embedder != null && embedder.isEnabled();
    }

    public boolean isReady() {
        return entries != null;
    }

    public int size() {
        List<Entry> current = entries;
        return current == null ? 0 : current.size();
    }

    public double threshold() {
        return threshold;
    }

    /**
     * 路由一句话。
     *
     * @return 最高相似度结果（含 {@code confident}）；语义层不可用 / 加载失败 ⇒ {@code null}
     */
    public Result route(String message, Integer role) {
        if (!isEnabled() || message == null || message.trim().isEmpty()) {
            return null;
        }
        List<Entry> loaded = ensureLoaded();
        if (loaded == null || loaded.isEmpty()) {
            return null;
        }
        float[] query = queryVector(message.trim());
        if (query == null) {
            return null;
        }

        Entry best = null;
        double bestScore = -1;
        double secondScore = -1;
        for (Entry entry : loaded) {
            if (!audienceMatches(entry.route().audience(), role)) {
                continue;
            }
            double score = dot(query, entry.vector());
            if (score > bestScore) {
                if (best != null) {
                    secondScore = bestScore;
                }
                best = entry;
                bestScore = score;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        if (best == null) {
            return null;
        }
        return new Result(best.route().intent(), best.route().targetPath(), bestScore,
                best.utterance(), secondScore, bestScore >= threshold);
    }

    private List<Entry> ensureLoaded() {
        List<Entry> current = entries;
        if (current != null) {
            return current;
        }
        if (failedAt > 0 && System.currentTimeMillis() - failedAt < RETRY_AFTER_FAILURE_MS) {
            return null;
        }
        synchronized (loadLock) {
            if (entries != null) {
                return entries;
            }
            if (failedAt > 0 && System.currentTimeMillis() - failedAt < RETRY_AFTER_FAILURE_MS) {
                return null;
            }
            try {
                List<Entry> loaded = buildEntries();
                entries = loaded;
                failedAt = 0;
                return loaded;
            } catch (RuntimeException e) {
                failedAt = System.currentTimeMillis();
                log.warn("语义路由预加载失败，降级到意图分类器：{}: {}",
                        e.getClass().getSimpleName(), e.getMessage());
                return null;
            }
        }
    }

    private List<Entry> buildEntries() {
        List<String> texts = new ArrayList<>();
        List<SemanticRouteRegistry.SemanticRoute> routes = SemanticRouteRegistry.all();
        for (SemanticRouteRegistry.SemanticRoute route : routes) {
            texts.addAll(route.utterances());
        }
        List<float[]> vectors = embedder.embed(texts);
        if (vectors == null || vectors.size() != texts.size()) {
            throw new TextEmbedder.EmbeddingException("向量条数与标准问法不一致：expected="
                    + texts.size() + ", actual=" + (vectors == null ? 0 : vectors.size()));
        }
        List<Entry> built = new ArrayList<>(texts.size());
        int index = 0;
        Integer dim = null;
        for (SemanticRouteRegistry.SemanticRoute route : routes) {
            for (String utterance : route.utterances()) {
                float[] vector = normalize(vectors.get(index++));
                if (vector == null) {
                    throw new TextEmbedder.EmbeddingException("向量为空：" + utterance);
                }
                if (dim == null) {
                    dim = vector.length;
                } else if (dim != vector.length) {
                    throw new TextEmbedder.EmbeddingException("向量维度不一致：expected=" + dim
                            + ", actual=" + vector.length);
                }
                built.add(new Entry(route, utterance, vector));
            }
        }
        return List.copyOf(built);
    }

    private float[] queryVector(String text) {
        float[] cached = queryCache.get(text);
        if (cached != null) {
            return cached;
        }
        try {
            List<float[]> vectors = embedder.embed(List.of(text));
            if (vectors == null || vectors.size() != 1) {
                return null;
            }
            float[] vector = normalize(vectors.get(0));
            if (vector != null) {
                queryCache.put(text, vector);
            }
            return vector;
        } catch (RuntimeException e) {
            log.warn("查询向量化失败，降级到意图分类器：{}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static boolean audienceMatches(int audience, Integer role) {
        if (role == null || role != 1 && role != 2) {
            return true;
        }
        return audience == SemanticRouteRegistry.BOTH || audience == role;
    }

    /** 归一化后余弦相似度退化为点积；零向量直接判失败。 */
    private static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        double sum = 0;
        for (float v : vector) {
            if (!Float.isFinite(v)) {
                return null;
            }
            sum += (double) v * v;
        }
        if (sum <= 0) {
            return null;
        }
        double norm = Math.sqrt(sum);
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            return -1;
        }
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private record Entry(SemanticRouteRegistry.SemanticRoute route, String utterance, float[] vector) {
    }

    /**
     * 路由结果。
     *
     * @param targetPath  导航类候选路径；数据类为 {@code null}
     * @param score       与最高标准问法的余弦相似度
     * @param utterance   命中的标准问法（排障 / 证据用）
     * @param secondScore 次高意图的分数；只有一个候选时为 {@code -1}
     * @param confident   {@code score >= threshold}；{@code false} ⇒ 澄清，不猜
     */
    public record Result(ChatIntent intent, String targetPath, double score, String utterance,
                         double secondScore, boolean confident) {
    }
}
