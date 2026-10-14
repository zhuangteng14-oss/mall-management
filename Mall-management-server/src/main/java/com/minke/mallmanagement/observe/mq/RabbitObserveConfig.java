package com.minke.mallmanagement.observe.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 事件台账这条 MQ 链路的**拓扑 + 两侧装配**（块 7 · commit 8c）。
 *
 * <pre>
 *   EventBroadcaster#add ──publish──▶ [RabbitEventPublisher]
 *                                        │  mall.observe.events (topic, durable)
 *                                        ▼  rk=observed.event
 *                                 observed_event.queue (durable, prefetch=500, 手工 ACK)
 *                                        │  [ObservedEventConsumer] 批 → observed_event 表 → LPUSH events:latest
 *                                        │  失败 3 次 → basicNack(requeue=false)
 *                                        ▼
 *                                 mall.observe.events.dlx (direct) ──▶ observed_event.dlq
 * </pre>
 *
 * <h2>【拓扑的每一处都是有理由的，逐条】</h2>
 * <ul>
 *   <li><b>exchange 用 topic</b>（而不是 direct / fanout）：今天只有一条路由键（{@code observed.event}），
 *       但事件域**必然要再分**（按 kind 分队列给不同消费方是下一步最自然的动作）。topic 让
 *       「将来加一个只收 {@code observed.error} 的队列」变成一条 binding，不动发布方一行。
 *       代价：一次通配匹配，可忽略。</li>
 *   <li><b>队列 durable + exchange durable</b>：这一层是**台账**，重启 broker 后拓扑不该消失
 *       （非 durable 的队列在 broker 重启后连定义都没了，此时发布方的消息会被静默丢弃）。</li>
 *   <li><b>DLQ 走 direct + 显式路由键</b>：死信是「机器写给机器看的」，不需要模式匹配；
 *       direct 的语义更窄，读配置时不会误以为它会扇出。</li>
 *   <li><b>DLQ 不设 TTL</b>：它是给人**事后查**的（出问题时先看一眼堆积了什么），
 *       自动过期会让「昨天那批为什么没落库」变成不可答。真正的清理问题在
 *       {@code observed_event} 表上（有保留期 job），不在 DLQ 上。</li>
 * </ul>
 *
 * <h2>【⚠️ 为什么本类显式声明 {@code RabbitTemplate} 与监听容器工厂，而不是吃 Boot 的默认装配】</h2>
 * Boot 的自动装配**确实**能把这些拼出来（它会把容器里唯一的 {@code MessageConverter} bean
 * 装到两侧），但那样有三件事**在代码里看不见**：转换器是哪一个、确认模式是哪一种、prefetch 是多少。
 * 而这三件恰好是「批文点名要的」（块 7 · commit 8c §三 补丁 1 / 3）⇒ 显式声明，
 * 让 grep 一次就能核。代价：Boot 的 {@code spring.rabbitmq.listener.simple.*} 属性仍然生效
 * （工厂用 Boot 的 {@code SimpleRabbitListenerContainerFactoryConfigurer} 配），只是**下面这几项
 * 覆写在代码里、不读属性** —— 这是刻意的，见每个 setter 上的注释。
 *
 * <h2>【⚠️ 两侧都用 {@code Jackson2JsonMessageConverter}：不许退化成默认的 SimpleMessageConverter】</h2>
 * 默认的 {@code SimpleMessageConverter} 只认 {@code String} / {@code byte[]} / {@code Serializable}
 * —— 一条 record 会被它拒掉（抛 {@code MessageConversionException}），而这抛在**发布侧**，
 * 于是结果不是「落不了库」而是「每发一条都炸一次」。两侧同一个转换器还有第二个作用：
 * 消息里的 {@code contentType=application/json} 是**消费者能读懂**这件事的凭据，
 * 而不是靠两边约定「就按 JSON 解」。
 *
 * <h2>【⚠️ 本类在 {@code @WebMvcTest} 切片里不会被加载】</h2>
 * 切片只扫 web 相关的 bean（{@code @Controller} / {@code @ControllerAdvice} / 过滤器……），
 * 普通 {@code @Configuration} 不在其中 —— 这是本仓既有的事实（见 {@code application-smoke.properties}
 * 里那几条「本 line 今日是空转」的坦白）。故切片里既没有 {@code RabbitTemplate} 也没有
 * {@code Queue}，{@code EventPublisher} 只能靠 {@code @MockitoBean} 顶上。
 */
@Configuration
public class RabbitObserveConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitObserveConfig.class);

    /** 事件交换机（topic）。⚠️ 改这里要同时改 {@code RabbitEventPublisher} 用的常量 —— 两者同源。 */
    public static final String EXCHANGE = "mall.observe.events";

    /** 主路由键。⚠️ 与 {@link #EXCHANGE} 一样是**发布方与消费方共用的字面**。 */
    public static final String ROUTING_KEY = "observed.event";

    /** 事件队列。名字不带 {@code mall.} 前缀是**刻意**的：它是队列名（不是指标名），
     *  与 Redis 的 {@code events:latest} 同属「一眼看得出是什么东西」的一档。 */
    public static final String QUEUE = "observed_event.queue";

    /** 死信交换机。 */
    public static final String DLX = "mall.observe.events.dlx";

    /** 死信路由键（队列的 {@code x-dead-letter-routing-key}）。 */
    public static final String DLQ_ROUTING_KEY = "observed.event.dead";

    /** 死信队列。 */
    public static final String DLQ = "observed_event.dlq";

    /** prefetch：批文点名 500。⚠️ 它必须 ≥ {@link #BATCH_SIZE}（Spring 会当场断言这一点）。 */
    public static final int PREFETCH = 500;

    /** 消费者批大小。见 {@link #observeListenerContainerFactory} 里对「批」的口径说明。 */
    public static final int BATCH_SIZE = 50;

    // ==================== 拓扑 ====================

    @Bean
    public TopicExchange observeEventExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * 主队列。⚠️ {@code deadLetterExchange} / {@code deadLetterRoutingKey} 是**队列的属性、
     * 不是消费者的行为** —— 也就是说「消息进 DLQ」这件事由 broker 完成，
     * 消费端只负责说「我不接受它」（{@code basicNack}）。这是本链路里最容易被误解的一处。
     */
    @Bean
    public Queue observeEventQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding observeEventBinding(Queue observeEventQueue, TopicExchange observeEventExchange) {
        return BindingBuilder.bind(observeEventQueue).to(observeEventExchange).with(ROUTING_KEY);
    }

    @Bean
    public DirectExchange observeEventDlx() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue observeEventDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding observeEventDlqBinding(Queue observeEventDlq, DirectExchange observeEventDlx) {
        return BindingBuilder.bind(observeEventDlq).to(observeEventDlx).with(DLQ_ROUTING_KEY);
    }

    // ==================== 两侧的转换器与模板 ====================

    /**
     * <b>两侧共用</b>的那个 JSON 转换器（装配理由见类注释）。
     * ⚠️ 它在容器里必须是**唯一**的 {@code MessageConverter} bean：Boot 的自动装配按类型取，
     * 出现第二个就会退回「不装转换器」，且**不会报错**（静默退化正是 TD-20 那一族）。
     */
    @Bean
    public MessageConverter observeJsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 发布侧模板。显式声明（理由见类注释），三处刻意设置：
     * <ul>
     *   <li>{@code setMandatory(true)} + 退回回调：消息**到了交换机但没路由到任何队列**
     *       是本链路唯一一种「broker 收了、却什么也没发生」的失败 —— 没有这一对，
     *       它静默消失（发布方拿到的是 ack）。</li>
     *   <li>确认回调只记 WARN、**不做任何重发**：重发要么把重复写进台账（无幂等键），
     *       要么需要一个真正的重试队列。本笔的取舍是「如实记一笔，不假装送达」——
     *       事件仍在内存环里，页面看得见。</li>
     * </ul>
     * ⚠️ 确认与退回要生效，连接工厂那边还得有 {@code publisher-confirm-type} /
     * {@code publisher-returns}（配置在 {@code application-dev.properties}，不在本类）。
     */
    @Bean
    public RabbitTemplate observeRabbitTemplate(ConnectionFactory connectionFactory,
                                               MessageConverter observeJsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(observeJsonMessageConverter);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> log.warn(
                "[EVENT] 消息被退回（到了交换机但没路由到队列）：exchange={} rk={} reply={} —— "
                        + "事件不落库，只留在内存环里",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        template.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.warn("[EVENT] broker 未确认（nack）：id={} cause={} —— 事件不落库，只留在内存环里",
                        correlation == null ? "-" : correlation.getId(), cause == null ? "-" : cause);
            }
        });
        return template;
    }

    /**
     * 消费侧容器工厂。用 Boot 的 configurer 打底（继承 {@code spring.rabbitmq.listener.simple.*}），
     * 再覆写这五项 —— 每一项都是批文点名的：
     * <ul>
     *   <li><b>MANUAL + prefetch=500</b>：ACK 的时点必须由消费者决定（「落库成功之后再 ACK」），
     *       自动 ACK 会在写库之前就把消息标成已消费 ⇒ 写库失败 = 事件**永久丢失**且无痕迹。</li>
     *   <li><b>consumerBatchEnabled + batchSize</b>：这是「批量消费」的落点 ——
     *       一次取一批、一条多行 INSERT 落库、一次 {@code basicAck(last, true)} 批量确认。
     *       ⚠️ 批的**代价**要写在明处：队列空闲时消息会等到 {@code receiveTimeout}
     *       （Boot 默认 1 秒）才成批交付 ⇒ 单条事件的落库延迟最多约 1 秒。
     *       本笔接受它（页面本来就是 1 秒 1 帧），但这是一条**用延迟换吞吐**的取舍，不是免费。</li>
     *   <li><b>deBatchingEnabled</b>：批是由**消费者**攒的（不是发布方打的包），
     *       关掉它反而会让「发布方未来真去发一个 List」变成解不开的批。</li>
     *   <li><b>defaultRequeueRejected=false</b>：兜底 —— 监听器若抛出**未被自己接住**的异常，
     *       默认是 requeue=true，那会变成「同一条消息无限重投 + 无限刷日志」（活锁）。
     *       宁可进 DLQ 被人看见。</li>
     * </ul>
     * ⚠️ 本工厂**另起名字**（{@code observeListenerContainerFactory}），不叫 Boot 默认的
     * {@code rabbitListenerContainerFactory}：名字即绑定（{@code @RabbitListener} 上显式写它），
     * 将来谁再加第二个监听器时，不会「悄悄」用上本工厂的手工 ACK 语义。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory observeListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter observeJsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(observeJsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(PREFETCH);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(BATCH_SIZE);
        factory.setDeBatchingEnabled(true);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
