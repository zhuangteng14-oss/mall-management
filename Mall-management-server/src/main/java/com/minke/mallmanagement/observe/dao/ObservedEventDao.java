package com.minke.mallmanagement.observe.dao;

import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code observed_event} 的**写入出口**（块 7 · commit 8c）+ **回放的读出口**（块 7 收尾）——
 * 全仓唯一往这张表 INSERT 的地方，也是唯一按批次把它读回来的地方（见 {@link #selectByBatch}）。
 *
 * <p>范式照 {@link ObservedEventCleanupDao}（同域、同批）：构造器收 {@code JdbcTemplate}
 * （让 {@code @WebMvcTest} 切片里既有的 {@code @MockitoBean JdbcTemplate} 继续喂给本类）+
 * 语句以 Java 字符串内联；表名带 {@code mall_management_sql.} 前缀。
 *
 * <h2>【⚠️ 2026-10-19：本类不再标 {@code @Repository}，由 {@code ObserveDataSourceConfig} 装配<b>两份</b>】</h2>
 * 一份给业务读路径（回放，{@code @Primary}，走业务主池），一份给 MQ 消费者（写路径，
 * 走独立的小池 {@code observe-consumer-pool}，4 条连接）。理由：后台台账写入不能在压测时
 * 抢占业务主池的 10 条连接 —— 实测 500 并发下 91–122 条 Tomcat 线程卡在
 * {@code HikariPool.getConnection}，消费者线程也在这条队列里。
 * ⚠️ 两份是**同一个类**的两个实例，SQL 仍只有这一份 —— 改 {@link #insertBatch} 不需要改两处。
 * ⚠️ 切片里没有 {@code ObserveDataSourceConfig}，{@code AbstractSmokeTest} 仍直接
 * {@code @Import(ObservedEventDao.class)}，落回那个 {@code @MockitoBean JdbcTemplate}。
 * 建表在 {@code mall_management_sql.sql} 的 Migration ⑲-1（12 列，4 个非空之外的都允许 NULL）；
 * 块 7 · 阶段 3a.3 由迁移 ㉑ 追加了 {@code ActorId / ActorRole / DurationUs} 三列。
 *
 * <h2>【⚠️ 落 13 列：{@code detail} 与 {@code key} 两个字段**没有列可落**】</h2>
 * 传进来的 {@link ObservedEvent} 有 16 个分量，本表接 13 个
 * （{@code Kind / Severity / Title / ObjectType / ObjectId / TraceId / BatchId / ActorId /
 * ActorRole / DurationUs / RemainingStock / FieldsJson / OccurredAt}）。
 * 缺的两列是<b>刻意缺的</b>（见 ⑲-1 与 ㉑ / ㉒ 的列清单），口径是「<b>有界标量进列，无界自由文本不进</b>」：
 * {@code detail} 是给人读的一行过程串（含 {@code src=环名#seq}，只对<b>当下那一拍</b>有意义，
 * 事后读它反而会被误导）；{@code key} 的业务标识已经在 {@code ObjectId} 里。
 * ⇒ 别给本表加这两列然后在这里补两个 {@code ?} —— 那会让台账变成日志页的副本。
 * <p>⚠️ <b>2026-10-16（块 7 · 阶段 3a.3）加了 {@code ActorId / ActorRole / DurationUs} 三列</b>
 * —— 它们**不是**上面那条口径的例外，恰恰是它的正面例子：三个都是<b>有界标量</b>
 * （业务号 + 角色号 + 微秒整数），能被索引、能被聚合、跨时间含义不变。
 *
 * <h2>【⚠️ 2026-10-17 · 阶段 3a.3 第二步：口径**修订** —— {@code fields} 进 {@code FieldsJson}】</h2>
 * 本类注释的上一个版本逐字写着「{@code detail} / {@code fields} 与 {@code key} 三个字段
 * **没有列可落** …… ⇒ 别给本表加这三列然后在这里补三个 {@code ?}」。
 * <b>那条口径在本步被用户裁定推翻，此处如实改写而不是偷偷改</b>：
 * <ul>
 *   <li><b>裁定原文</b>：「加（不然会缺失当时的数据流程，没办法朔源性找寻病因，那这个回放的
 *       作用就降低了）」。</li>
 *   <li><b>被推翻的只是 {@code fields} 这一半，判据没被推翻</b>：{@code detail} 与 {@code fields}
 *       说的是同一批事实，但「无界」这条判据对两者的结论**不一样** —— {@code detail} 是拼好的
 *       自由文本（无界、且含 {@code src=环名#seq} 这种只对当下那一拍有意义的碎片），
 *       而 {@code fields} 是**结构化的 kv**：能逐键读、能直接被前端拼句子。
 *       把它排除在台账外的代价实测就是「实时页看得到、回放页看不到」—— 同一件事两个页面讲不一样，
 *       而回放页的存在意义正是**复盘当时到底发生了什么**。</li>
 *   <li><b>{@code detail} 与 {@code key} 仍然不进列</b>（这一半口径没变，理由同上一节）。</li>
 *   <li><b>列类型取 {@code json}</b>：这列不参与索引、不参与聚合，只被「按批次读回来」
 *       ⇒ 要的是「原样存、原样取」，不是「能 {@code where}」。</li>
 * </ul>
 * <p>⚠️ <b>{@code RemainingStock} 是同一步加的另一个有界标量</b>（{@code int}，扣减那一刻该 SKU
 * 还剩几件）—— 它对上一节那条口径是**正面例子**（能索引、能聚合、跨时间含义不变），
 * 要小心的恰恰相反：它和 {@code DurationUs} **同类型**（都是整数）⇒ 占位符顺序错了
 * 不会报错、只会静静地把两个数写反（本类的「四处同改」纪律就是为这一类准备的）。
 *
 * <h2>【⚠️ 截断：列宽是本类的责任，不是调用方的】</h2>
 * {@code Title varchar(255)} / {@code ObjectId varchar(128)} / {@code ObjectType varchar(32)} …
 * 而事件里的值来自业务键与请求路径（**长度不受控**，{@code identity} 环的 objectId 就是请求路径）。
 * MySQL 严格模式下超长是**报错**，一条超长会把**整批**打回 DLQ。
 * 故本类在写之前统一按列宽截断 —— 与 {@code AttackEngine} 对 {@code drill_record.Name} 的做法同源。
 * ⚠️ 截断会**悄无声息地丢尾巴**，这是取舍：宁可台账里的标题短一截，不可整批事件进死信。
 * <p>⚠️ <b>唯一的例外是 {@code FieldsJson} —— 它**绝不截断**</b>（块 7 · 阶段 3a.3 第二步）：
 * 截断一段 JSON 得到的是**非法 JSON**，而 MySQL 的 {@code json} 列在严格模式下会**整条拒绝**
 * ⇒ 那就不再是「少一截」而是「整批进死信」，比上面那条取舍严酷得多。
 * 它的护栏不是本类切字符串，而是**构造约定**：各 {@code fromXxx} 往 {@code fields} 里放的都是
 * 业务键与短串（{@code sku} / {@code result} / {@code key}……）。
 * ⚠️ 真要有人往里塞一整段正文，后果是那一批进 DLQ —— <b>可见、可查</b>，
 * 而不是悄悄落一条被切坏的 JSON 让回放页解析失败。
 *
 * <h2>【批写：一条多行 INSERT】</h2>
 * 消费者的批（{@link com.minke.mallmanagement.observe.mq.ObservedEventConsumer}）一次最多
 * {@code BATCH_SIZE} 条（50），拼成一条多行 INSERT —— 一次往返落一批。
 * ⚠️ 批很大时这条语句会很长，但 50 × 13 个占位符在 MySQL 的包上限与占位符上限面前都很小
 * （{@code max_allowed_packet} 默认 64MB、预处理语句占位符上限 65535）。
 */
public class ObservedEventDao {

    /** 列宽上限：与 ⑲-1 的 DDL 逐列对齐。改 DDL 就要改这里（否则超长会在严格模式下报错）。 */
    private static final int KIND_MAX = 16;
    private static final int SEVERITY_MAX = 16;
    private static final int TITLE_MAX = 255;
    private static final int OBJECT_TYPE_MAX = 32;
    private static final int OBJECT_ID_MAX = 128;
    private static final int TRACE_ID_MAX = 64;
    private static final int BATCH_ID_MAX = 36;
    /** 块 7 · 阶段 3a.3（㉑）：与 {@code admin_audit.ActorId} 同宽 —— 两处的 `ActorId` 是同一个东西。 */
    private static final int ACTOR_ID_MAX = 50;

    /**
     * 只为 {@code FieldsJson} 这一个用途存在的序列化器（块 7 · 阶段 3a.3 第二步）。
     *
     * <p>⚠️ <b>为什么不从构造器注入 Spring 那个 {@code ObjectMapper}（本仓别处都那么做）</b>：
     * 那会给本类多一个依赖 ⇒ {@code @WebMvcTest} 切片的 {@code @Import} 清单要跟着改。
     * 本类「**只吃 {@code JdbcTemplate}**」是那条被登记了**二十次**的切片不变量的一部分
     * （逐字写在 {@code AbstractSmokeTest} 里 {@code ObservedEventDao.class} 那一行的注释上：
     * 「只吃 JdbcTemplate ⇒ 落回下面那个 @MockitoBean，**SQL 仍不出切片**」）
     * —— 为一个 {@code Map → String} 去动那条不变量不划算。
     *
     * <p>⚠️ <b>它写出来的串与 MVC 用的那个**逐字相同**，这一条是可复核的</b>：
     * 全仓没有 {@code spring.jackson.*} 配置项、也没有 {@code Jackson2ObjectMapperBuilderCustomizer}
     * （实测 grep，两处都为空）⇒ 两边跑的都是默认配置；而本列的取值全是标量
     * （{@code String} / {@code Integer} / {@code Boolean}），没有任何需要定制的东西
     * （日期格式 / 多态类型 / 属性命名策略在这个取值面上都不出现）。
     *
     * <p>⚠️ 类型写**全限定名**而非 import：本文件的行号被三处文档引用（见 {@code db-schema.md}
     * 与 {@code api-contract.md} 里那几处 {@code ObservedEventDao} 的行号），
     * 少一行 import 就少一次集体漂移 —— 同 {@code StockMetrics#event} 里那句
     * 「写全限定名而非 import，保本文件 {@code :75} 的引用不下移」。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 每行的 **14** 个占位符（顺序即下面 {@link #insertBatch} 里的 {@code params.add} —— 两处必须一起改）。
     *  ⚠️ 块 7 收尾（㉓）在首位新增 {@code Seq}（A3 幂等键的另一半，见 {@link #insertBatch}）。 */
    private static final String ROW_PLACEHOLDERS = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * 回放要读的列清单（块 7 收尾 · 演练回放）。
     *
     * <p>⚠️ <b>{@code OccurredAt} 用 {@code CAST(… AS CHAR)} 而不是本域惯用的
     * {@code DATE_FORMAT(OccurredAt, '%Y-%m-%d %H:%i:%s')}（{@code DrillRecordDao.COLUMNS} 那份）
     * —— 差别就是**毫秒**：回放要按原时间戳撒点，秒级会把同一秒里的事件压成一坨。
     * 列是 {@code datetime(3)} ⇒ {@code CAST} 按列精度出 3 位小数（{@code 2026-10-15 11:30:22.123}），
     * 与 {@code DATE_FORMAT} 一样是**无时区文本**，但落的是 **Asia/Shanghai 本地墙钟**（不是 UTC）：
     * 本列的 {@code Timestamp} 绑定按连接时区写库，DB 服务器时区为 UTC ⇒ 前端解析要补 {@code +08:00}。
     *
     * <p>⚠️ 块 7 · 阶段 3a.3 两步各加了列，回放响应（{@code ReplayService#replay} 把行**原样**
     * 塞进 {@code list}）会**多带** {@code ActorId / ActorRole / DurationUs / RemainingStock /
     * FieldsJson} 五个键 —— 既有列的名字与顺序一个字没动。两条给前端的提醒：
     * <ul>
     *   <li>{@code FieldsJson} 从 JDBC 出来是一段 <b>JSON 文本（字符串）</b>，不是对象
     *       ⇒ 前端要用它得先 {@code JSON.parse}（回放页与实时页的 {@code fields} 形状不同，
     *       这不是错，是「库里存的就是文本」）。</li>
     *   <li>{@code RemainingStock} 只有库存环的**扣减档**有值（其余为 {@code null}），
     *       口径见 {@code StockMetrics.StockEvent#remaining()}。</li>
     * </ul>
     */
    private static final String COLUMNS =
            "Id, Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId, "
                    + "ActorId, ActorRole, DurationUs, RemainingStock, FieldsJson, "
                    + "CAST(OccurredAt AS CHAR) AS OccurredAt ";

    private final JdbcClient jdbcClient;

    public ObservedEventDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 批量追加。返回**真写进去的行数**（{@code 0} 表示这一批是空的，不是失败）。
     *
     * <p>⚠️ <b>本方法<b>不吞异常</b></b>：写库失败必须让调用方（消费者）知道 —— 它要靠这个
     * 来决定「重试 / 转 DLQ」，而不是 ACK 掉一条没落库的证据。fail-soft 在**消费者**那一层
     * （见该类注释），不在本层：DAO 假装成功是 TD-20 那一族里最坏的一种。
     *
     * @param events 一批事件；空表直接返回 0（连 SQL 都不发）。顺序 = 写入顺序
     * @return 受影响行数
     */
    public int insertBatch(List<ObservedEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        StringBuilder values = new StringBuilder(events.size() * ROW_PLACEHOLDERS.length());
        List<Object> params = new ArrayList<>(events.size() * 14);
        for (ObservedEvent e : events) {
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append(ROW_PLACEHOLDERS);
            // A3（㉓）：Seq 与 TraceId 组成幂等键。⚠️ 它是**合并环的进程内序号**，
            // 不是 DB 自增；重投的同一条消息带着同一个 seq ⇒ 幂等成立（见下方 ON DUPLICATE KEY UPDATE）。
            params.add(e.seq());
            params.add(clip(e.kind(), KIND_MAX));
            params.add(clip(e.severity(), SEVERITY_MAX));
            params.add(clip(e.title(), TITLE_MAX));
            params.add(clip(e.objectType(), OBJECT_TYPE_MAX));
            params.add(clip(e.objectId(), OBJECT_ID_MAX));
            params.add(clip(e.traceId(), TRACE_ID_MAX));
            params.add(clip(e.batchId(), BATCH_ID_MAX));
            // 块 7 · 阶段 3a.3（㉑）：谁 / 什么角色 / 多久。⚠️ 三个**除了 actorId 都不截断、不做转换**
            // —— actorRole 是 Integer、durationUs 是 Long，直接把可空的包装类型交给 JDBC
            // （null 落 NULL，这是本表这三列的正常取值：定时任务没有身份、瞬时事件没有耗时）。
            // ⚠️ actorId 要截断：它的来源不受控（会话里的业务号），而列宽 50 是硬约束。
            params.add(clip(e.actorId(), ACTOR_ID_MAX));
            params.add(e.actorRole());
            params.add(e.durationUs());
            // 块 7 · 阶段 3a.3 第二步（㉒）：当时剩余 / 结构化明细。
            // ⚠️ 这两个的顺序**必须与上面的列清单逐位对齐** —— 而 remaining 与 durationUs
            //    都是整数类型，写反了 MySQL 不报错，只会把「耗时 171µs」记成「剩余 171 件」
            //    （静默错数据，本类「四处同改」纪律针对的就是这一条）。
            // ⚠️ remaining 是 Integer（可空包装）：null 落 NULL，口径见
            //    StockMetrics.StockEvent#remaining()（三类 null 都不是「用了兜底值」）。
            params.add(e.remaining());
            // ⚠️ FieldsJson 由本类**现序列化**（见 fieldsJson）：它**不截断**（截断=非法 JSON=整批 DLQ）。
            //    序列化失败会抛 ⇒ 那一批走重试→DLQ，理由写在 fieldsJson 的 javadoc 里。
            params.add(fieldsJson(e.fields()));
            // OccurredAt 是 datetime(3)：用 Timestamp 让它走 JDBC 的时间类型绑定，
            // 不用字符串（字符串会带上连接串的时区解释，是另一条更隐蔽的错路）。
            params.add(new Timestamp(e.epochMilli()));
        }
        return jdbcClient.sql("INSERT INTO mall_management_sql.observed_event "
                        + "(Seq, Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId, "
                        + "ActorId, ActorRole, DurationUs, RemainingStock, FieldsJson, OccurredAt) VALUES "
                        + values
                        // ⚠️ 只对「唯一键冲突」做 no-op，**不吞其它错误**（数据截断 / 类型错 / 连接断仍然抛，
                        //    由消费者重试→DLQ；那是 TD-20 的底线）。用 ON DUPLICATE KEY UPDATE 而不是
                        //    INSERT IGNORE：后者会把非唯一键错误也静默成 0 行。
                        //    「Id = Id」在 MySQL 里是合法的自赋值，冲突行受影响行数为 0。
                        + " ON DUPLICATE KEY UPDATE Id = Id")
                .params(params)
                .update();
    }

    // ==================== 读：回放（块 7 收尾 · 演练回放）====================

    /** 回放行过滤（主分支 + 无批次号的汇总取消）；完整理由见 {@link #selectByBatch}。 */
    private static final String BATCH_PREDICATE = "BatchId = ? OR (BatchId IS NULL AND Kind = 'order' "
            + "AND FieldsJson LIKE '%\"count\"%' AND FieldsJson LIKE '%CANCELLED%' "
            + "AND OccurredAt BETWEEN (SELECT MIN(OccurredAt) FROM mall_management_sql.observed_event WHERE BatchId = ?) "
            + "AND (SELECT MAX(OccurredAt) FROM mall_management_sql.observed_event WHERE BatchId = ?)) ";

    /**
     * 按批次读事件（**演练回放**的读出口）—— 按发生时刻**升序**，同一个毫秒内按 {@code Id} 升序。
     *
     * <p>⚠️ 按 {@code OccurredAt} 升序是回放的语义前提：前端照此撒回原时间戳，顺序错了就是重排历史。
     * 同一毫秒内（演练压满时一秒几百条）只有 {@code Id} 能分先后 ⇒ <b>两列都要</b>；MySQL 不保证稳定序。
     *
     * <p>⚠️ {@code LIMIT} 由调用方（{@code ReplayService}）收好再传进来 —— 那是策略，不是 SQL。
     *
     * <p>⚠️ 主分支走 {@code idx_observed_event_batch}；第二支补「批次首末之间、无批次号的 order 汇总取消」——
     * 超时任务在调度线程上没有 {@code X-Drill-Batch}，只按 {@code BatchId = ?} 读会让它在回放里整段消失。
     *
     * @param batchId 批次 UUID（**非空**；调用方已判空，空值不做「查全部」兜底 —— 那会把误传变成全表读）
     * @param limit   最多读多少行（&gt; 0；调用方已收进区间）
     * @return 行（列见 {@link #COLUMNS}）；没有该批次 ⇒ 空表，**不是** {@code null}
     */
    public List<Map<String, Object>> selectByBatch(String batchId, int limit) {
        return jdbcClient.sql("SELECT " + COLUMNS + "FROM mall_management_sql.observed_event "
                        + "WHERE " + BATCH_PREDICATE + "ORDER BY OccurredAt ASC, Id ASC LIMIT ?")
                .params(batchId, batchId, batchId, limit)
                .query().listOfRows();
    }

    /**
     * 该批次**一共有多少条**（回放要如实说「放的是不是全部」—— 见 {@code ReplayService} 的
     * 「为什么多这一条 COUNT」）。
     *
     * <p>⚠️ 与 {@link #selectByBatch} 同一套过滤（批次主分支 + 无批次号汇总取消）；计数口径必须与
     * 取行一致，否则前端「共 N 条」会把补进来的取消行漏掉。
     */
    public long countByBatch(String batchId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.observed_event WHERE " + BATCH_PREDICATE)
                .params(batchId, batchId, batchId).query(Long.class).single();
    }

    /** 按列宽截断；{@code null} 原样回去（这几列的 NULL 语义是「没有值」，不是空串）。 */
    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * {@code fields}（结构化明细）→ 台账 {@code FieldsJson} 列的 JSON 文本。
     * {@code null} / 空表 ⇒ {@code null}（落 NULL：「这条事件没有结构化明细」，
     * 与「有明细但序列化失败」在库里必须长得不一样 —— 后者压根落不进去，见下）。
     *
     * <p>⚠️ <b>序列化失败**抛**，不静默落 NULL</b>（块 7 · 阶段 3a.3 第二步）。
     * 这一条与 {@code AuditService} 那个「记一行 ERROR + 落 NULL」的先例**刻意不同**，
     * 区别在两者的分量：那一列是审计行的**附属**信息（丢了审计行还在），
     * 而这一列是「回放凭什么能溯源」的**全部依据**（本步加它的理由原文见类注释）。
     * 悄悄写 NULL 会让「回放少了一段当时的数据流程」重新变成**看不见的损害** —— 正是本仓
     * TD-20 那一族要抓的形状，而这一步的全部意义就是把那件事变得可见。
     *
     * <p>⚠️ <b>代价如实记账</b>：本类**不吞异常**（见 {@link #insertBatch}）⇒ 这里抛出去
     * 会被消费者当落库失败重试，最终把**整批**（≤ {@code BATCH_SIZE} 50 条）转 DLQ。
     * 接受它的两条理由：① 在本类的取值面上它**不该发生** —— {@code fields} 的值全部是
     * {@code String} / {@code Integer} / {@code Boolean} 标量，由各 {@code fromXxx} 就地
     * {@code put}，不可能自引用成环；② 一旦发生就是**代码缺陷**，缺陷该以「一批进 DLQ」
     * 这种看得见的方式暴露，而不是被降级成一个 NULL 默默藏起来。
     */
    private static String fieldsJson(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalStateException("事件字段（fields）序列化失败：本列不静默落 NULL"
                    + "（回放要靠它溯源，悄悄丢等于台账里少了一段当时的数据流程）", e);
        }
    }
}
