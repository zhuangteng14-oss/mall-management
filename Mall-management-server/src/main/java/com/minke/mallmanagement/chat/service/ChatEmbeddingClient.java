package com.minke.mallmanagement.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow BAAI/bge-m3 Embedding 客户端（{@code /v1/embeddings}）。
 *
 * <h2>【边界】</h2>
 * 只负责「文本 → 向量」与失败分类，不知道任何意图。API Key 从配置 / 环境变量取；
 * 本类不打日志、不把 Authorization 写进异常消息。
 *
 * <p>单次 input 有上限，本类按 {@code batchSize} 自动分批；返回按 {@code index}
 * 排序，保证与入参逐条对齐。向量维度不写死：bge-m3 为 1024 维，由首条向量自适应。
 */
@Service
public class ChatEmbeddingClient implements TextEmbedder {

    static final String DEFAULT_URL = "https://api.siliconflow.cn/v1/embeddings";
    static final String DEFAULT_MODEL = "BAAI/bge-m3";
    static final int DEFAULT_TIMEOUT_SECONDS = 8;
    static final int DEFAULT_BATCH_SIZE = 32;

    private final boolean enabled;
    private final String url;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int batchSize;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public ChatEmbeddingClient(
            @Value("${chat.embedding.enabled:true}") boolean enabled,
            @Value("${chat.embedding.url:https://api.siliconflow.cn/v1/embeddings}") String url,
            @Value("${chat.embedding.api-key:}") String apiKey,
            @Value("${chat.llm.api-key:}") String llmApiKey,
            @Value("${chat.embedding.model:BAAI/bge-m3}") String model,
            @Value("${chat.embedding.timeout-seconds:8}") int timeoutSeconds,
            @Value("${chat.embedding.batch-size:32}") int batchSize,
            ObjectMapper objectMapper) {
        this(enabled, url, isBlank(apiKey) ? llmApiKey : apiKey, model, timeoutSeconds, batchSize,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .build());
    }

    /** 测试用：可换本地 HttpServer / 更短超时；不读 Spring 配置。 */
    ChatEmbeddingClient(boolean enabled, String url, String apiKey, String model,
                        int timeoutSeconds, int batchSize, ObjectMapper objectMapper,
                        HttpClient httpClient) {
        this.enabled = enabled;
        this.url = isBlank(url) ? DEFAULT_URL : url.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = isBlank(model) ? DEFAULT_MODEL : model.trim();
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.batchSize = Math.max(1, batchSize);
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isEnabled() {
        return enabled && !isBlank(apiKey) && !isBlank(url);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!isEnabled()) {
            throw new EmbeddingException("Embedding 未启用或未配置 API Key");
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> all = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(texts.size(), from + batchSize);
            all.addAll(embedBatch(texts.subList(from, to)));
        }
        return all;
    }

    private List<float[]> embedBatch(List<String> texts) {
        String requestBody;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", texts);
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new EmbeddingException("构造 Embedding 请求失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new EmbeddingException("Embedding 请求超时（>" + timeoutSeconds + "s）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Embedding 请求被中断", e);
        } catch (Exception e) {
            throw new EmbeddingException("Embedding 请求失败：" + e.getClass().getSimpleName(), e);
        }

        if (response.statusCode() != 200) {
            throw new EmbeddingException("Embedding HTTP " + response.statusCode());
        }
        return parse(response.body(), texts.size());
    }

    private List<float[]> parse(String responseBody, int expected) {
        try {
            JsonNode data = objectMapper.readTree(responseBody).path("data");
            if (!data.isArray() || data.size() != expected) {
                throw new EmbeddingException("Embedding 返回条数不符：expected=" + expected
                        + ", actual=" + (data.isArray() ? data.size() : 0));
            }
            List<IndexedVector> indexed = new ArrayList<>(expected);
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray() || embedding.isEmpty()) {
                    throw new EmbeddingException("Embedding 缺少向量");
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                indexed.add(new IndexedVector(item.path("index").asInt(indexed.size()), vector));
            }
            indexed.sort(Comparator.comparingInt(IndexedVector::index));
            List<float[]> out = new ArrayList<>(expected);
            for (IndexedVector item : indexed) {
                out.add(item.vector());
            }
            return out;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Embedding 响应不是合法 JSON", e);
        }
    }

    private record IndexedVector(int index, float[] vector) {
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
