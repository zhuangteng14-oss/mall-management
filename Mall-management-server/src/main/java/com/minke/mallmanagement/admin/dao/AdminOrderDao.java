package com.minke.mallmanagement.admin.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理端订单治理的**只读**数据出口（C3 块 4）。
 *
 * <h2>【本类为什么不是「OrderDao 加几个方法」】</h2>
 * {@code OrderDao} 是**订单域**的数据出口，它的每条语句都有**业务调用方**
 * （下单 / 三个 CAS / 三个面）。本类的语句只有一个调用方 —— 管理端的**全量视图与异常监控**
 * （无 {@code CustomerId}/{@code MerchantId} 约束的跨用户查询）。把跨用户的全表扫描塞进
 * {@code OrderDao}，会让「订单域的 SQL」这件事从「业务路径」扩成「业务 + 治理」，
 * 而治理查询的**谓词自由度**（按状态 / 按时间 / 不分人）与业务路径完全不同。
 * ⇒ 分开放，是让两边各自读得懂。
 *
 * <h2>【本类**只有读**】</h2>
 * 管理端的**写**（介入）走的不是本类，而是 {@code OrderService.payIfUp / deliveryIfUp /
 * receiveIfUp} —— 那是**唯一**的订单写入口（锁 + 五道闸都在那儿）。
 * 「管理端自己拼一条 UPDATE 改状态位」= 绕过状态机守卫，是 A3 明确不要的乙案。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code CartDao} / {@code TemplateDao}。⚠️ 别照抄 {@code OrderDao} 的
 * {@code mall_management_sql.} 前缀（那是 F#1 存量债）。
 */
@Repository
public class AdminOrderDao {

    /**
     * 全量视图一次最多回多少行。**有界的理由是「别把管理页变成全表导出」**：
     * {@code order} 表没有分页键（{@code Id} 在并发下不保证与时间序一致 —— 见
     * {@code order_state_transition} 的同类注释），所以本笔**不做真分页**，
     * 只做一个**上限截断**并把「是否截断」如实回报给前端。
     */
    public static final int MAX_LIMIT = 500;

    /** 未付款超时的阈值（小时）。**拍出来的数字，待业务裁**（见 {@link #selectMonitor}）。 */
    public static final int UNPAID_HOURS = 24;

    /** 已付款未发货超时的阈值（小时）。**同样待裁**。 */
    public static final int UNDELIVERED_HOURS = 48;

    private static final String COLUMNS = "Id, OrderId, CustomerId, MerchantId, Price, Quantity, "
            + "ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf, CreateTime, SettlementDate";

    private final JdbcClient jdbcClient;

    public AdminOrderDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 全量视图（按用户 / 商家 / 状态 / 时间筛）。**每个筛选条件都可空** —— 空 ⇒ 不加该谓词。
     *
     * <p>⚠️ {@code status} 的**取值白名单**由 {@link com.minke.mallmanagement.admin.service.AdminOrderService}
     * 把守，本方法收到未知值 ⇒ 回**空列表**（不是「不加谓词」）—— 静默忽略一个筛选条件，
     * 会让「筛了空态」和「筛了全量」看起来一样，那是最坏的一种错。
     *
     * <p>⚠️ {@code CreateTime} 是 {@code varchar(50)}（{@code mall_management_sql.sql:152}），
     * 不是 {@code datetime} ⇒ 时间筛是**字符串比较**。这对既有格式
     * （{@code 'YYYY-MM-DD HH:MM:SS'}，ISO 式零填充）**恰好**成立（字典序 = 时间序），
     * 是一条**依赖格式的**判据 —— 谁把写入格式改成非零填充，这条筛会静默失准。
     */
    public List<Map<String, Object>> selectOrders(String customerId, String merchantId,
                                                 String status, String from, String to, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM `order` WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (notBlank(customerId)) {
            sql.append(" AND CustomerId = ?");
            args.add(customerId.trim());
        }
        if (notBlank(merchantId)) {
            sql.append(" AND MerchantId = ?");
            args.add(merchantId.trim());
        }
        String statusWhere = statusWhere(status);
        if (statusWhere == null) {
            return List.of();
        }
        if (!statusWhere.isEmpty()) {
            sql.append(" AND ").append(statusWhere);
        }
        if (notBlank(from)) {
            sql.append(" AND CreateTime >= ?");
            args.add(from.trim());
        }
        if (notBlank(to)) {
            sql.append(" AND CreateTime <= ?");
            args.add(to.trim());
        }
        sql.append(" ORDER BY CreateTime DESC, Id DESC LIMIT ?");
        args.add(clampLimit(limit));
        return jdbcClient.sql(sql.toString()).params(args.toArray()).query().listOfRows();
    }

    /**
     * 状态 → SQL 谓词。**返回值有三态，别把它压成两态**：
     * <ul>
     *   <li>{@code ""} —— 合法且「不筛」（{@code all} / 空）</li>
     *   <li>非空谓词 —— 合法且要筛</li>
     *   <li>{@code null} —— **未知状态值** ⇒ 调用方回空列表（不静默降级成「不筛」）</li>
     * </ul>
     * <p>谓词与三位状态机对齐（{@code OrderState} 的 5 合法 + 3 非法）：{@code abnormal}
     * 就是「不满足 A-S4a-8 不变式」的那一支，**与状态机用的是同一条判据**，
     * 不是另编一套（另编必漂移）。
     */
    private static String statusWhere(String status) {
        String s = status == null ? "" : status.trim().toLowerCase();
        return switch (s) {
            case "", "all" -> "";
            case "unpaid" -> "PayIf = 0";
            case "paid" -> "PayIf = 1 AND DeliveryIf = 0";
            case "shipped" -> "DeliveryIf = 1 AND ReceiveIf = 0";
            case "completed" -> "ReceiveIf = 1";
            case "abnormal" -> "PayIf = 0 AND (DeliveryIf = 1 OR ReceiveIf = 1)";
            default -> null;
        };
    }

    /** 单条订单（管理端详情）。不存在 ⇒ {@code null}。 */
    public Map<String, Object> selectByOrderId(String orderId) {
        String sql = "SELECT " + COLUMNS + " FROM `order` WHERE OrderId = ? LIMIT 1";
        return jdbcClient.sql(sql).param(orderId).query().listOfRows().stream().findFirst().orElse(null);
    }

    /**
     * 该单的状态流转时间线（{@code order_state_transition}，B3 起就在写）。
     *
     * <p>⚠️ 排序键是 {@code OccurredAt}（**不是** {@code Id}）—— 同单内的 4 条事件是并发 INSERT、
     * {@code Id} 在 INSERT 时才分配 ⇒ 它**不保证**与发生顺序一致。这是那张表自己的列注释
     * 写明的，别按 {@code Id} 排。
     */
    public List<Map<String, Object>> selectTimeline(String orderId) {
        String sql = "SELECT Action, FromState, ToState, OccurredAt FROM order_state_transition "
                + "WHERE OrderId = ? ORDER BY OccurredAt ASC, Id ASC";
        return jdbcClient.sql(sql).param(orderId).query().listOfRows();
    }

    /** 全表总单数（供「库内共 N 单」显示；**不随筛**）。⚠️ **不是**「是否被截断」的判据 —— 那由
     *  {@code rows.size() >= }{@link #clampLimit(int)}{@code (limit)} 判（两者刻意分流，见 {@code AdminOrderService.list}）。 */
    public int countAll() {
        return jdbcClient.sql("SELECT COUNT(*) FROM `order`").query(Integer.class).single();
    }

    // ==================== 异常监控（只读识别） ====================

    /**
     * 异常监控的四个计数。**全部是「读一眼就能判」的只读识别，没有一个会改库。**
     *
     * <p>超时口径是**写死的两个阈值**（24h 未付款 / 48h 未发货），依据只有一条：
     * 「本仓没有任何 SLA / 时效配置，任何阈值都是**拍的**」⇒ 把它们**显式写成常量并标注为待裁**，
     * 而不是埋在 SQL 里当既有事实。真正的时效需要业务输入，登记待办。
     *
     * <p>⚠️ 时间比较同 {@link #selectOrders}：{@code CreateTime} 是字符串列，
     * 与 {@code DATE_FORMAT(..., '%Y-%m-%d %H:%i:%s')} 比较依赖 ISO 式零填充格式。
     */
    public Map<String, Object> selectMonitor() {
        String sql = "SELECT "
                + "COALESCE(SUM(CASE WHEN PayIf = 0 AND CreateTime < DATE_FORMAT(DATE_SUB(NOW(), INTERVAL " + UNPAID_HOURS + " HOUR), '%Y-%m-%d %H:%i:%s') THEN 1 ELSE 0 END), 0) AS unpaidTimeout, "
                + "COALESCE(SUM(CASE WHEN PayIf = 1 AND DeliveryIf = 0 AND CreateTime < DATE_FORMAT(DATE_SUB(NOW(), INTERVAL " + UNDELIVERED_HOURS + " HOUR), '%Y-%m-%d %H:%i:%s') THEN 1 ELSE 0 END), 0) AS undeliveredTimeout, "
                + "COALESCE(SUM(CASE WHEN PayIf = 0 AND (DeliveryIf = 1 OR ReceiveIf = 1) THEN 1 ELSE 0 END), 0) AS abnormalState, "
                + "COALESCE(SUM(CASE WHEN ReceiveIf != 1 THEN 1 ELSE 0 END), 0) AS unfinished, "
                + "COUNT(*) AS total "
                + "FROM `order`";
        return jdbcClient.sql(sql).query().listOfRows().stream().findFirst().orElse(Map.of());
    }

    // ==================== 内部 ====================

    /**
     * 生效上限：{@code limit <= 0 ⇒ 200}，否则 {@code min(limit, }{@link #MAX_LIMIT}{@code )}。
     *
     * <p>公开给 {@link com.minke.mallmanagement.admin.service.AdminOrderService} —— 它要拿**同一个**
     * 生效上限判「本次结果是否已达上限」（{@code rows.size() >= clampLimit(limit)}），
     * 不能各算各的（否则「截断」判定又和真实取回量脱钩）。
     */
    public static int clampLimit(int limit) {
        if (limit <= 0) {
            return 200;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
