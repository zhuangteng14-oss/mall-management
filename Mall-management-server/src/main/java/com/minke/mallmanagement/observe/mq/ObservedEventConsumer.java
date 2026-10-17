package com.minke.mallmanagement.observe.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.observe.dao.ObservedEventDao;
import com.minke.mallmanagement.observe.service.EventBroadcaster;
import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import com.minke.mallmanagement.shared.web.ObserveContext;
import com.minke.mallmanagement.shared.web.TraceIdFilter;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 事件台账的**消费者**（块 7 · commit 8c）—— 把 {@link RabbitObserveConfig#QUEUE} 里的一批事件
 * 变成「{@code observed_event} 的行 + Redis {@code events:latest} 的条目」。
 *
 * <h2>【顺序是**刻意**的：落库 → 尽力进 Redis → ACK】</h2>
 * <ol>
 *   <li><b>先落库</b>：它是**证据**（可查、有保留期、按 traceId 归组）。失败要能被发现
 *       ⇒ 失败路径是「重试 3 次 → 转 DLQ」，<b>绝不 ACK</b>。</li>
 *   <li><b>再尽力 LPUSH</b>：{@code events:latest} 是**给人看的最近一屏**，丢了不影响证据链
 *       ⇒ 整段包在 {@code try/catch} 里，失败只 WARN，<b>不改变后面那个 ACK</b>。
 *       （Redis 挂了就落不了库？不 —— 这两条路互不依赖，Redis 挂不该连累台账。）</li>
 *   <li><b>最后 ACK</b>：ACK 的时点 = 「这件事我已经做完」。自动 ACK 会在**写库之前**就把消息
 *       标成已消费 ⇒ 写库失败 = 事件永久丢失且**不留痕迹**（队列里也没有了）。</li>
 * </ol>
 *
 * <h2>【「批」是怎么来的：消费者批，不是发布方打包】</h2>
 * 容器工厂开了 {@code consumerBatchEnabled}（见 {@link RabbitObserveConfig}）⇒ 本方法收到的是
 * {@code List<Message>}。⚠️ 因此有三个后果必须写在明处：
 * <ul>
 *   <li>空闲队列的消息要等到 {@code receiveTimeout}（Boot 默认 1 秒）才成批交付 ⇒
 *       单条事件的落库延迟最多约 1 秒；</li>
 *   <li>本方法**一次触达一批**，失败就是**整批**进 DLQ：一条脏数据会带走同一批里干净的 49 条。
 *       这是刻意的取舍（批写换吞吐），被牺牲的那部分**看得见**（DLQ 里躺着），不是静默丢失；</li>
 *   <li>ACK 用 {@code multiple=true}（一次把这一批全确认）。⚠️ 前面因为「不是合法 JSON」已经
 *       单独 {@code basicNack} 掉的那几条**不在未确认集合里**，不会被这次 ACK 误伤；
 *       反过来，本方法也**不会**对已经 nack 过的 deliveryTag 再 nack 一次（那会让通道直接报错）。</li>
 * </ul>
 *
 * <h2>【消息体是原样的 JSON 字节】</h2>
 * 发布方写的是 {@code ObservedEvent}（Jackson 序列化），本方法用**同一个** {@code ObjectMapper}
 * 反序列化回同一个 record。⚠️ 进 Redis 的是**原始字节**（{@code new String(body, UTF_8)}），
 * 不是「解析后再序列化一遍」的结果 —— 后者多一次转换，且会让「Redis 里那条」与「队列里那条」
 * 在字段顺序/转义上悄悄不同，排查时没法直接对。
 *
 * <h2>【⚠️ 为什么本 bean 是**有条件**的：实测出来的一个硬约束（8c 验证时发现并修掉）】</h2>
 * 批文对 a3/profile 缺 broker 的预期是「<b>连不上只 WARN</b>」降级。**实测不成立** ——
 * 把 dev 的 {@code spring.rabbitmq.password} 置空起一次，Spring 上下文<b>起不来</b>：
 * <pre>
 *   ApplicationContextException: Failed to start bean 'org.springframework.amqp.rabbit.config.internalRabbitListenerEndpointRegistry'
 *     ← FatalListenerStartupException: Authentication failure
 *     ← AmqpAuthenticationException: com.rabbitmq.client.AuthenticationFailureException: ACCESS_REFUSED - Login was refused using authentication mechanism PLAIN
 * </pre>
 * （逐字照抄实测日志，连 bean 名的全限定前缀一起 —— 省掉前缀会让人按短名去搜、搜不到。）
 * 两条都必须知道：
 * <ol>
 *   <li><b>「不可达」与「凭据错」是两种失败，行为不同</b>：broker 根本连不上（端口无监听 /
 *       网络不通）时容器只在后台重试，应用照常启动（这才是「只 WARN」）；而 <b>broker 在、
 *       但认不上</b>时 Spring AMQP 把它当**致命**（{@code FatalListenerStartupException}，
 *       刻意的：凭据错了重试一万次也没用）⇒ 整个上下文 refresh 失败。</li>
 *   <li>于是「a3 不配 rabbitmq」这个前提**本身不够**：Boot 的默认 host 是 localhost，
 *       只要那台机器上恰好有个 broker（CI 就是 —— 见 ci.yml 的 rabbitmq service），
 *       guest 从容器外登录会被拒 ⇒ <b>a3 启动即失败</b>。</li>
 * </ol>
 * ⇒ 处置：给消费者加一个**存在性**条件 —— 只有 profile 里配了 {@code spring.rabbitmq.host}
 * 才注册它。理由（为什么用「配了 host」而不是另立一个 {@code mall.observe.mq.enabled} 开关）：
 * 消费者与 broker **是一条事实的两半**，多一个开关就多一种「配了 broker 却没有消费者」的
 * 半配置（消息只进不出、队列悄悄涨）。⚠️ 与之**相对**，发布侧 {@link RabbitEventPublisher}
 * 是**无条件**注册的：它的失败路径本来就只是「探针记 WARN（首条 + 按间隔重复）+ 页面画黄点」，
 * 正是批文要的那条降级 —— 两者的差别在**启动期能不能失败**，不在「MQ 相关与否」。
 */
@Component
@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "host")
public class ObservedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ObservedEventConsumer.class);

    /** Redis 里的最近事件列表（最新在前）。⚠️ 与前端/后续读取方共用的键名，改它要全仓检索。 */
    public static final String REDIS_KEY = "events:latest";

    /** 列表容量：批文点名 5000。⚠️ 它**不是**「保留最近 5000 条」的承诺（那是 TABLE 的活），
     *  是「列表不会无限长」的上界 —— Redis 只当最近一屏用。 */
    public static final int REDIS_CAPACITY = 5000;

    /** 落库失败的重试次数（批文点名 3）。用完仍失败 ⇒ 整批转 DLQ。 */
    static final int MAX_ATTEMPTS = 3;

    /**
     * 死信计数指标（A4 · 块 7 收尾）。指标面板顶部读它，单位=条、累计。
     * ⚠️ 与 {@link EventBroadcaster#deadLetterCount()} 是同一件事的两个读口：
     * 计数器给指标帧（本类在 MQ 配置存在时才注册，指标帧不能依赖本 bean），
     * broadcaster 的计数给测试与排障。两处都在**同一行**递增（见 {@link #reportDeadLetters}）。
     */
    static final String DEAD_LETTER_METER = "mall.observe.dead.letters";

    /** 重试退避（递增：200ms / 400ms）。⚠️ 这会把消费者线程**睡住** —— 单并发下同一队列
     *  的下一批因此被推迟，这是「宁可慢、不要丢」的取舍（同期消息在 prefetch 里等着，不会丢）。 */
    private static final long RETRY_BACKOFF_MS = 200L;

    private final ObservedEventDao observedEventDao;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final EventBroadcaster broadcaster;
    private final MeterRegistry registry;

    public ObservedEventConsumer(ObservedEventDao observedEventDao, StringRedisTemplate redis,
                                 ObjectMapper objectMapper, EventBroadcaster broadcaster,
                                 MeterRegistry registry) {
        this.observedEventDao = observedEventDao;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.registry = registry;
    }

    /**
     * 消费一批。**本方法是本链路唯一把一个事件变成「库里一行」的地方。**
     *
     * @param messages 这一批原始消息（容器攒的批，顺序 = 投递顺序）
     * @param channel  手工 ACK 用的通道（{@code AcknowledgeMode.MANUAL} 下由容器传入）
     */
    @RabbitListener(queues = RabbitObserveConfig.QUEUE,
            containerFactory = "observeListenerContainerFactory")
    public void onBatch(List<Message> messages, Channel channel) throws IOException {
        // ⚠️ 消费线程是**复用**的（simple listener 的并发从 2 起、最多 8）：本方法里的日志必须
        //    带着「这一批」的 traceId，结束时还原成进来时的上下文 —— 否则下一条消息顶着一批的号。
        //    一批最多 50 条、可能来自不同的 traceId ⇒ 日志统一挂**第一个非空 traceId**，
        //    并在帧的 fields.traces 里如实记「本批涉及几个 traceId」（不假装整批同号）。
        ObserveContext previous = ObserveContext.capture();
        try {
            List<ObservedEvent> events = new ArrayList<>(messages.size());
            List<String> bodies = new ArrayList<>(messages.size());
            List<Message> okMessages = new ArrayList<>(messages.size());
            for (Message message : messages) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                try {
                    events.add(objectMapper.readValue(body, ObservedEvent.class));
                    bodies.add(body);
                    okMessages.add(message);
                } catch (Exception e) {
                    // 解不开的消息**单独**送死信：它不可能是「重试就能好」的（重试解的还是同一串字节），
                    // 留在队列里只会无限重投。同时它不该连累同一批里其余那些好事件。
                    log.warn("[EVENT] 消息不是合法的事件 JSON ⇒ 单条转 DLQ：cause={} body={}",
                            e.toString(), abbreviate(body));
                    channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
                    reportDeadLetters(null, 1, e, "消息不是合法的事件 JSON");
                }
            }
            if (events.isEmpty()) {
                return;
            }

            // 绑定本批（日志口径）；finally 里还原，避免污染复用线程。
            TraceIdFilter.bind(firstTraceId(events), null, null, null);

            if (!insertWithRetry(events, okMessages, channel)) {
                return;     // 已经各自 NACK 过（转 DLQ），此处不再 ACK
            }
            pushToRedis(bodies);
            // multiple=true：一次确认这一批（批 ACK）。⚠️ 理由与「为什么不会误伤」见类注释。
            channel.basicAck(okMessages.get(okMessages.size() - 1).getMessageProperties().getDeliveryTag(), true);
        } finally {
            TraceIdFilter.bind(previous.traceId(), previous.batchId(), previous.actorId(), previous.actorRole());
        }
    }

    /**
     * 落库（最多 {@value #MAX_ATTEMPTS} 次）。失败 ⇒ 把这一批**各自** NACK（{@code requeue=false}）
     * ⇒ 由队列的 {@code x-dead-letter-exchange} 送进 {@link RabbitObserveConfig#DLQ}。
     *
     * @return true = 已经落库（可以继续走 Redis 与 ACK）；false = 已转 DLQ
     */
    private boolean insertWithRetry(List<ObservedEvent> events, List<Message> okMessages, Channel channel)
            throws IOException {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                observedEventDao.insertBatch(events);
                return true;
            } catch (Exception e) {
                last = e;
                log.warn("[EVENT] 落库失败（第 {}/{} 次）：{} 条 cause={}",
                        attempt, MAX_ATTEMPTS, events.size(), e.toString());
                if (attempt < MAX_ATTEMPTS && !sleep(RETRY_BACKOFF_MS * attempt)) {
                    break;      // 被中断：不再空等，直接走失败路径（否则关闭时的等待会拖住容器）
                }
            }
        }
        log.warn("[EVENT] 落库连续 {} 次失败 ⇒ 整批 {} 条转 DLQ（{}）：队列里已留不住它们，"
                + "DLQ 里躺着等人查 —— 别把它当成「已经处理过了」", MAX_ATTEMPTS, okMessages.size(),
                RabbitObserveConfig.DLQ);
        // A4（块 7 收尾）：重试彻底失败 = 这条链路的「最终异常」。在 NACK 之前先把死信这件事
        // 送进合并环（实时日志页的 ERROR + 链路抽屉 + 指标面板的死信数），否则它只存在于 broker
        // 的一个队列里，页面看上去一切正常。
        reportDeadLetters(events, okMessages.size(), last, "落库连续 " + MAX_ATTEMPTS + " 次失败");
        for (Message message : okMessages) {
            // 逐条 NACK（multiple=false）：multiple=true 会连带这一批**之外**的 tag，
            // 而那些早已被上一次 ACK/NACK 消化掉了 ⇒ 对已确认的 tag 再操作是通道级错误。
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
        }
        return false;
    }

    /**
     * 死信上报（A4）：一条 ERROR 事件进合并环 + 指标计数器累加。
     *
     * <p>两条路都 fail-soft：上报/计数失败不能反过来改变 NACK 的结果（死信已经在 broker 里了，
     * 页面少一条说明是「少一条说明」，不是「转运失败」）。
     *
     * @param events 失败的那批事件（可空：非法 JSON 的那条没有解析结果）
     * @param count  本次进入死信的条数
     * @param last   最后一次异常（可空）
     * @param stage  失败阶段的人话（「落库连续 3 次失败」/「消息不是合法的事件 JSON」）
     */
    private void reportDeadLetters(List<ObservedEvent> events, int count, Exception last, String stage) {
        if (count <= 0) {
            return;
        }
        String traceId = firstTraceId(events);
        Set<String> traceIds = new LinkedHashSet<>();
        if (events != null) {
            for (ObservedEvent e : events) {
                if (e.traceId() != null && !e.traceId().isEmpty()) {
                    traceIds.add(e.traceId());
                }
            }
        }
        String exClass = last == null ? "-" : last.getClass().getName();
        String exMsg = rootMessage(last);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stage", stage);
        fields.put("count", count);
        fields.put("exception", exClass);
        fields.put("error", exMsg);
        fields.put("traces", traceIds.size());
        // 原始 traceId 已在事件记录的 traceId 字段里；fields 里再放一份，链路抽屉不读记录也能看到。
        fields.put("traceId", traceId);
        try {
            broadcaster.emitDeadLetter(traceId,
                    "stage=" + stage + " count=" + count + " exception=" + exClass + " error=" + exMsg,
                    fields);
        } catch (RuntimeException e) {
            log.warn("[EVENT] 死信事件上报失败（不影响 DLQ 转运）：{}", e.toString());
        }
        try {
            registry.counter(DEAD_LETTER_METER).increment(count);
        } catch (RuntimeException e) {
            log.warn("[EVENT] 死信计数失败：{}", e.toString());
        }
    }

    /** 批内第一个非空 traceId（日志与死信事件的归属口径）。 */
    private static String firstTraceId(List<ObservedEvent> events) {
        if (events == null) {
            return null;
        }
        for (ObservedEvent e : events) {
            String traceId = e.traceId();
            if (traceId != null && !traceId.isEmpty()) {
                return traceId;
            }
        }
        return null;
    }

    /** 根因的 message（异常链走到最里层；「最终异常」的人话就是它）。 */
    private static String rootMessage(Throwable e) {
        if (e == null) {
            return "-";
        }
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getName() : msg;
    }

    /**
     * 尽力把这一批塞进 Redis 列表（最新在前）+ 收口长度。
     *
     * <p>⚠️ 一个 {@code try} 包住整段（连 LPUSH 与 LTRIM 一起）：失败只 WARN，
     * <b>绝不上抛</b> —— 上抛会让 {@link #onBatch} 在落库成功之后失去 ACK 的机会，
     * 于是「库里已经有这一行」与「消息被重投」同时成立（重复写台账）。
     */
    private void pushToRedis(List<String> bodies) {
        try {
            for (String body : bodies) {
                redis.opsForList().leftPush(REDIS_KEY, body);
            }
            redis.opsForList().trim(REDIS_KEY, 0, REDIS_CAPACITY - 1);
        } catch (Exception e) {
            log.warn("[EVENT] Redis {} 写入失败（不影响落库与 ACK）：cause={}", REDIS_KEY, e.toString());
        }
    }

    /** 睡 {@code millis}。{@code false} = 被中断（调用方应立即走失败路径，不再等）。 */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 日志里给一条**说不清是什么**的字节串留个可读的头（它已经确定解不成 JSON 了）。 */
    private static String abbreviate(String body) {
        String oneLine = body.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
