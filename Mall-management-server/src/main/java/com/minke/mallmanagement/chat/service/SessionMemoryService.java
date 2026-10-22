package com.minke.mallmanagement.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.chat.model.ChatIntent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 对话的**短期上下文**（2026-10-22 增量：Redis 会话记忆）。
 *
 * <h2>【做什么】</h2>
 * 每次 {@code /chat/ask} 成功后，把「用户这句话 + 本轮意图 + 回复」追加到
 * {@code chat:session:{userId}}，只留最近 {@value #MAX_ENTRIES} 条（5 轮问答），
 * TTL {@value #TTL_MINUTES} 分钟。下一句如果是「那…呢 / 到哪了 / 进度」这类短追问，
 * 而规则识别器给出 {@code UNKNOWN}，就用上一轮的意图补上下文（如上一轮查订单、
 * 这一轮「到哪了」→ 仍按订单查询，并复用上一轮的单号）。
 *
 * <h2>【与 Q9=A 的边界（别把这两件事混为一谈）】</h2>
 * Q9 管的是**前端会话状态**：聊天记录只放组件内 {@code ref}，刷新即清、不做持久化。
 * 本服务是**后端短期上下文**，不是会话存储：不落 MySQL、不跨设备、不做用户画像，
 * TTL 30 分钟到期即焚；{@code userId} 为空（未登录）时**完全不写**。
 * 订单号 / 商品名等业务字段会随回复进入这段临时内存（最长 30 分钟），这是
 * 「能接住『到哪了』」的必要代价；不做跨会话留存，也不把内容写日志。
 *
 * <h2>【失败面】</h2>
 * Redis 不可用 / 序列化异常一律吞掉：客服回答是主链路，短期记忆只是锦上添花。
 * 连接用的 {@link StringRedisTemplate} 通过 {@link ObjectProvider} 取 —— 切片测试
 * （{@code @WebMvcTest}）里没有 Redis 自动配置时，本 bean 仍能构造起来并退化为空转。
 */
@Service
public class SessionMemoryService {

    /** Redis key 前缀；完整键为 {@code chat:session:{userId}}。 */
    static final String KEY_PREFIX = "chat:session:";

    /** 每轮问答两条（user + assistant），10 条 = 5 轮。 */
    static final int MAX_ENTRIES = 10;

    /** 用完即焚：30 分钟。 */
    static final Duration TTL = Duration.ofMinutes(30);
    static final long TTL_MINUTES = 30L;

    /** 单条文本上限，防止一条长回复把 key 撑大。 */
    private static final int MAX_TEXT = 500;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    public SessionMemoryService(ObjectProvider<StringRedisTemplate> redisProvider,
                                ObjectMapper objectMapper) {
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
    }

    /** 上一轮可复用的意图与单号。 */
    public record Context(ChatIntent intent, String orderId) {
    }

    /** 存储单元：一轮里的一条（用户或助手）。 */
    record Entry(String role, String text, String intent, String orderId, long at) {
    }

    /**
     * 追加一轮问答。任何异常都不外抛。
     *
     * @param userId   会话里的业务号；为空则不写
     * @param userText 用户原话
     * @param intent   本轮意图（调用方保证是业务意图）
     * @param orderId  本轮实际涉及的订单号，可为 {@code null}
     * @param replyText 系统回复
     */
    public void remember(String userId, String userText, ChatIntent intent,
                         String orderId, String replyText) {
        if (isBlank(userId)) {
            return;
        }
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return;
            }
            String key = KEY_PREFIX + userId;
            long now = System.currentTimeMillis();
            redis.opsForList().rightPush(key,
                    objectMapper.writeValueAsString(
                            new Entry("user", clip(userText), null, null, now)));
            redis.opsForList().rightPush(key,
                    objectMapper.writeValueAsString(
                            new Entry("assistant", clip(replyText),
                                    intent == null ? null : intent.name(), orderId, now)));
            redis.opsForList().trim(key, -MAX_ENTRIES, -1);
            redis.expire(key, TTL);
        } catch (Exception ignored) {
            // 主链路是「回答这句话」；记忆写失败不影响它，也不在这里打日志（避免把对话正文带进日志）。
        }
    }

    /**
     * 取最近一轮**业务意图**（跳过 FAQ / 问候 / 兜底与用户条目）。
     *
     * @return 无记忆 / Redis 不可用 / 解析失败 ⇒ {@code null}
     */
    public Context lastContext(String userId) {
        if (isBlank(userId)) {
            return null;
        }
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return null;
            }
            List<String> rows = redis.opsForList().range(KEY_PREFIX + userId, -MAX_ENTRIES, -1);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            // 从新到旧找：最近一条带业务意图的助手回复。
            for (int i = rows.size() - 1; i >= 0; i--) {
                Entry entry = objectMapper.readValue(rows.get(i), Entry.class);
                if (entry == null || !"assistant".equals(entry.role()) || entry.intent() == null) {
                    continue;
                }
                ChatIntent intent = parse(entry.intent());
                if (intent != null && memoryWorthy(intent)) {
                    return new Context(intent, entry.orderId());
                }
            }
        } catch (Exception ignored) {
            // 同 remember：记忆坏了就当没有上下文。
        }
        return null;
    }

    /** 只接受有业务含义的意图；FAQ / 问候 / 兜底不能成为下一句的上下文。 */
    private static boolean memoryWorthy(ChatIntent intent) {
        return intent == ChatIntent.ORDER_QUERY
                || intent == ChatIntent.REFUND_QUERY
                || intent == ChatIntent.OPEN_STORE
                || intent == ChatIntent.ADD_TO_SECKILL
                || intent == ChatIntent.IDENTITY_CLAIM;
    }

    private static ChatIntent parse(String name) {
        try {
            return ChatIntent.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
