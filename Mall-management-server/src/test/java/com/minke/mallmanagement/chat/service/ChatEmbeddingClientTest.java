package com.minke.mallmanagement.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GLM Embedding 客户端 —— 全部打本地 {@link HttpServer}，**不出网**。
 */
class ChatEmbeddingClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("返回按 index 排序，与入参逐条对齐（GLM 不保证 data 顺序）")
    void ordersByIndex() throws IOException {
        String url = serve(200, true);
        ChatEmbeddingClient client = client(url, 32);

        List<float[]> vectors = client.embed(List.of("a", "b"));

        // 服务端故意把 data 倒序：index=0 的向量值是 2，index=1 的是 1。
        // 客户端按 index 排序后，输出必须与入参 ["a","b"] 对齐。
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(2f, 0f);
        assertThat(vectors.get(1)).containsExactly(1f, 0f);
    }

    @Test
    @DisplayName("分批：5 条 / batchSize=2 ⇒ 3 次请求，顺序不丢")
    void splitsIntoBatches() throws IOException {
        String url = serve(200, false);
        ChatEmbeddingClient client = client(url, 2);

        List<float[]> vectors = client.embed(List.of("a", "b", "c", "d", "e"));

        assertThat(vectors).hasSize(5);
        assertThat(vectors.get(0)).containsExactly(1f, 0f);
        assertThat(vectors.get(4)).containsExactly(5f, 0f);
        assertThat(batches).containsExactly(2, 2, 1);
    }

    @Test
    @DisplayName("非 200 ⇒ EmbeddingException，不把响应体 / Key 回显")
    void non200Fails() throws IOException {
        String url = serve(500, false);
        ChatEmbeddingClient client = client(url, 32);

        assertThatThrownBy(() -> client.embed(List.of("a")))
                .isInstanceOf(TextEmbedder.EmbeddingException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("未配置 Key ⇒ isEnabled=false，调用直接失败（不产生非法出网）")
    void disabledWithoutKey() {
        ChatEmbeddingClient client = new ChatEmbeddingClient(true, "http://127.0.0.1:1/x", "",
                "embedding-3", 1, 32, JSON, HttpClient.newHttpClient());

        assertThat(client.isEnabled()).isFalse();
        assertThatThrownBy(() -> client.embed(List.of("a")))
                .isInstanceOf(TextEmbedder.EmbeddingException.class);
    }

    // ==================== 本地服务 ====================

    private final List<Integer> batches = new ArrayList<>();

    private String serve(int status, boolean reverse) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger seq = new AtomicInteger();
        server.createContext("/embed", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            int count = body.path("input").size();
            batches.add(count);
            String response;
            if (status != 200) {
                response = "{\"error\":\"boom\"}";
            } else {
                List<String> items = new ArrayList<>();
                int start = seq.getAndAdd(count);
                for (int i = 0; i < count; i++) {
                    int index = reverse ? count - 1 - i : i;
                    items.add("{\"index\":" + index + ",\"embedding\":[" + (start + i + 1) + ",0]}");
                }
                response = "{\"data\":[" + String.join(",", items) + "]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/embed";
    }

    private static ChatEmbeddingClient client(String url, int batchSize) {
        return new ChatEmbeddingClient(true, url, "test-key", "embedding-3",
                3, batchSize, JSON, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
    }
}
