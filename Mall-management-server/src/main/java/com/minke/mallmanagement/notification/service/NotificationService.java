package com.minke.mallmanagement.notification.service;

import com.minke.mallmanagement.notification.dao.NotificationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统通知服务（C3 块 1 · I2）—— 通知域的**全部业务动作**：
 * 一行投递（{@link #send}）+ 收件箱读写（{@link #list} / {@link #unreadCount} /
 * {@link #markRead} / {@link #markAllRead}）+ 投递历史（{@link #history}）+
 * 受众解析（{@link #resolveAudience}）。
 *
 * <p>⚠️ <b>块 1 时这里还有「模板存取」（{@code listTemplates} / {@code saveTemplate}）—— 2026-10-10
 * 就地改造后已删</b>：{@code notification_template} 的行搬进通用 {@code template} 表
 * （{@code Type = 'notification.' + 原 TemplateKey}）、原表物理删除，模板 CRUD 归
 * {@code template.service.TemplateService}（管理端点 {@code /admin/template/*}）。
 * ⚠️ 「占位符渲染与 send 时按 key 取模板」的口径**不变，仍不做**。
 *
 * <h2>【为什么收件箱读写与「管理端发信」的受众解析同处一个类】</h2>
 * 它们读写的是**同两张表、同一套列语义**，拆开会让「{@code ToType} 的取值域」「未读的唯一判据是
 * {@code ReadAt IS NULL}」这类口径分裂到两处。管理端那半的**工作流**（扇出、批次、模板套用）
 * 另起 {@code admin/service/NotificationBroadcastService} ——
 * 切法是**原语 vs 工作流**，不是**读 vs 写**。
 *
 * <h2>【{@link #send} 为什么**吞**异常 —— 与 {@code AuditService.record} 同一条裁决】</h2>
 * 调用点分两类：<b>块 6 的评论区触发</b>（「你被回复了」）与<b>管理端投递</b>。
 * 两类都在**别的业务动作已经成功之后**发生：评论已经落库了、公告本来就没有前置状态。
 * 若投递失败冒泡，用户看到的是「评论失败」——他会重发，于是**多一条评论**，而通知照样没发出去。
 * ⇒ 两害相权：**宁可丢一条通知，也不把成功翻成失败**。
 *
 * <p>⚠️ <b>代价必须写下来</b>（同 {@code AuditService}）：这等于「通知可以静默失效」。
 * 补偿手段是把失败记成 ERROR 日志并带 ASCII 标记 {@code NOTIFY}
 * （本仓日志是 GBK 字节，中文 pattern 检索不到 —— 同 {@code AUDIT} / {@code AUTH} 惯例）。
 * <b>运维口径：搜 ERROR + NOTIFY，命中即说明通知在漏。</b>
 *
 * <p>⚠️ <b>不发异步、不加 {@code @Transactional}、不重试</b>：单条 INSERT 自动提交，就是想要的形态。
 * 上了异步就得回答「应用关闭时队列里的通知怎么办」（又是一份新契约），而本块的目标是**可解释**：
 * 广播 = 在请求线程里逐条 INSERT，返回的 {@code sent} 就是**真实落库条数**，不是「已提交任务数」。
 *
 * <h2>【{@link #send} 刻意**不**校验 {@code eventType}】</h2>
 * 见 {@link com.minke.mallmanagement.notification.NotificationEvents} 的类注释：
 * 「加通知类型不改表、不改后端」这句话的兑现点就在**这里没有白名单**。
 * 未知 code 照落 —— 前端负责兜底显示，后端不负责拒收。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 列表默认条数（前端不传 {@code limit} 时）—— 与 {@code AuditService} 同值，便于心智统一。 */
    public static final int DEFAULT_LIMIT = 100;

    /** 列表上限 —— 防止一次拉爆整个收件箱。 */
    public static final int MAX_LIMIT = 500;

    // ==================== 取值域（本笔的口径唯一出处） ====================

    /** {@code ToType} = 客户。⚠️ 英文，对齐既有 {@code message.from_type} 的写法。 */
    public static final String TO_TYPE_CUSTOMER = "customer";

    /** {@code ToType} = 商家。 */
    public static final String TO_TYPE_MERCHANT = "merchant";

    /** 投递受众：全部（客户 + 商家）。 */
    public static final String AUDIENCE_ALL = "all";

    /** 投递受众：仅客户。 */
    public static final String AUDIENCE_CUSTOMER = "customer";

    /** 投递受众：仅商家。 */
    public static final String AUDIENCE_MERCHANT = "merchant";

    /**
     * {@code user.usertype} 的**客户**取值。
     *
     * <p>⚠️ 这是**中文魔法串**，且它是**别人的表**（user 域）的口径 —— 全仓只在
     * {@code FriendCacheService.java:159} / {@code UserCacheService} 各判一次。本类**再判一次**是
     * 有意的：受众解析要按它取号。
     *
     * <p>⚠️ <b>2026-10-09 在 VM 库实测（{@code HEX(usertype)} 分组），{@code usertype} 有
     * <u>三</u>类而不是两类</b>：
     * <pre>
     *   商家   E59586E5AEB6        ×3
     *   客户   E5AEA2E688B7        ×2
     *   管理员 E7AEA1E79086E59198   ×1   ← 第三类，一直存在
     * </pre>
     * ⇒ 「群发给客户 / 商家」的两条受众查询**天然把管理员排除在外**（{@code usertype = ?} 是第一条
     * 谓词），这正是想要的：管理员不发给自己。但**别照「只有两类」的心智去写新查询** ——
     * 任何「取全部用户」的写法都会把管理员卷进去。
     */
    private static final String USERTYPE_CUSTOMER = "客户";

    /** {@code user.usertype} 的**商家**取值。见 {@link #USERTYPE_CUSTOMER} 的实测清单与警告。 */
    private static final String USERTYPE_MERCHANT = "商家";

    private final NotificationDao notificationDao;

    public NotificationService(NotificationDao notificationDao) {
        this.notificationDao = notificationDao;
    }

    // ==================== 写：投递一行（fail-soft 原语） ====================

    /**
     * 投递**一条**通知给**一个**收件人。块 6 的触发点与广播扇出都调它。
     *
     * <p>⚠️ 参数不做业务校验（连 {@code title} 为空都照落）：本方法是**原语**，
     * 「标题不能为空」是**管理端发送页**的规则，属 {@code NotificationBroadcastService}。
     * 原语层加校验会让「块 6 想发一条空标题的评论通知」这种合法诉求被误拒。
     *
     * @param batchId 本次投递批次（调用方生成，单发也要给 —— 列是 {@code NOT NULL}）
     * @return {@code true} = 已落库；{@code false} = 落库失败（已记 ERROR + NOTIFY 日志，**不抛**）
     */
    public boolean send(String batchId, String toId, String toType, String eventType,
                        String title, String content, String targetType, String targetId) {
        try {
            notificationDao.insertOne(batchId, toId, toType, eventType, title, content, targetType, targetId);
            return true;
        } catch (Exception e) {
            // fail-soft：调用方的业务动作已经成功了，不能让通知把它翻成失败。见类注释。
            log.error("通知投递失败（NOTIFY）：toId={} toType={} eventType={} batchId={} —— 调用方业务已完成，本行丢失",
                    toId, toType, eventType, batchId, e);
            return false;
        }
    }

    // ==================== 读：收件箱（三端共用） ====================

    /**
     * 收件箱列表。响应形状随全仓列表端点惯例：
     * {@code {"success":true,"list":[…],"total":N,"unread":M,"limit":K}}。
     *
     * <p>{@code list} 的每一行**额外补一个 {@code unread} 布尔**（源自 {@code ReadAt} 是否为
     * {@code null}）—— 让前端不必去理解「未读的判据是 NULL 而不是 0 / 空串」，
     * 也就不会有人去写 {@code if (row.ReadAt == '')} 这种错判据。
     */
    public Map<String, Object> list(String toId, String toType, Boolean onlyUnread, Integer limit) {
        if (isBlank(toId) || isBlank(toType)) {
            return fail("缺少参数：toId / toType");
        }
        int effective = normalizeLimit(limit);
        boolean unreadOnly = Boolean.TRUE.equals(onlyUnread);
        List<Map<String, Object>> rows = notificationDao.selectInbox(toId, toType, unreadOnly, effective);
        for (Map<String, Object> row : rows) {
            row.put("unread", row.get("ReadAt") == null);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", rows);
        // ⚠️ total 与 list **同一筛选条件**（含 onlyUnread）—— 否则「只看未读」时列表空而 total 为全量，
        //    前端的「已截断」判据会误报。另给一个恒为全量的 unread，前端红点/计数用它。
        result.put("total", notificationDao.countInbox(toId, toType, unreadOnly));
        result.put("unread", notificationDao.countUnread(toId, toType));
        result.put("limit", effective);
        return result;
    }

    /** 未读数 —— 三端顶栏红点靠它（5s 轮询）。 */
    public Map<String, Object> unreadCount(String toId, String toType) {
        if (isBlank(toId) || isBlank(toType)) {
            return fail("缺少参数：toId / toType");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("unread", notificationDao.countUnread(toId, toType));
        return result;
    }

    // ==================== 写：标记已读 ====================

    /**
     * 标记一条已读。
     *
     * <p>⚠️ {@code updated=0} 是**正常结果**（已被标过 / Id 不属于该收件人），**不是失败** ——
     * 前者靠 SQL 的 {@code AND ReadAt IS NULL} 幂等，后者靠 {@code AND ToId = ?} 兜住。
     * 两者都回 {@code success=true}：前端重复点击不该弹出「操作失败」。
     */
    public Map<String, Object> markRead(String toId, String toType, Long id) {
        if (isBlank(toId) || isBlank(toType) || id == null || id <= 0) {
            return fail("缺少参数：toId / toType / id");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("updated", notificationDao.markRead(toId, toType, id));
        return result;
    }

    /** 该收件人的全部未读 → 已读。语义同 {@link #markRead}（幂等、{@code updated=0} 不是失败）。 */
    public Map<String, Object> markAllRead(String toId, String toType) {
        if (isBlank(toId) || isBlank(toType)) {
            return fail("缺少参数：toId / toType");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("updated", notificationDao.markAllRead(toId, toType));
        return result;
    }

    // ==================== 读：管理端「通知历史」（按批归组） ====================

    /** 投递历史（按 {@code BatchId} 归组，时间倒序）。三个筛选与 {@code limit} 都可为 {@code null}。 */
    public Map<String, Object> history(String eventType, String from, String to, Integer limit) {
        int effective = normalizeLimit(limit);
        List<Map<String, Object>> rows = notificationDao.selectHistory(eventType, from, to, effective);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", rows);
        result.put("total", notificationDao.countHistory(eventType, from, to));
        result.put("limit", effective);
        return result;
    }

    // ==================== 受众解析（管理端广播用） ====================

    /** 一批同类型收件人。{@code toType} 取 {@link #TO_TYPE_CUSTOMER} / {@link #TO_TYPE_MERCHANT}。 */
    public record Audience(String toType, List<String> ids) {
    }

    /**
     * 把受众代号解析成「收件人类型 + 业务号清单」。
     *
     * <p>{@code all} ⇒ 两条（客户一批、商家一批）；未知代号 ⇒ **空列表**（由调用方判空并回错，
     * 本方法不抛 —— 全仓「失败即 200 + success:false」惯例）。
     *
     * <p>⚠️ {@code ids} 可能只有 1 个元素，也**可能为空**（库里一个用户都没有）。调用方必须
     * 把「受众为空」当**正常结果**处理并如实回 {@code sent=0}，不能答 success:false
     * —— 空库上发公告本来就该是「发给了 0 人」。
     */
    public List<Audience> resolveAudience(String audience) {
        List<Audience> out = new ArrayList<>();
        if (AUDIENCE_ALL.equals(audience) || AUDIENCE_CUSTOMER.equals(audience)) {
            out.add(new Audience(TO_TYPE_CUSTOMER, notificationDao.selectCustomerIds(USERTYPE_CUSTOMER)));
        }
        if (AUDIENCE_ALL.equals(audience) || AUDIENCE_MERCHANT.equals(audience)) {
            out.add(new Audience(TO_TYPE_MERCHANT, notificationDao.selectMerchantIds(USERTYPE_MERCHANT)));
        }
        return out;
    }

    /** 受众代号是否在取值域内 —— 供管理端发送页在动手前判错。 */
    public static boolean isKnownAudience(String audience) {
        return AUDIENCE_ALL.equals(audience)
                || AUDIENCE_CUSTOMER.equals(audience)
                || AUDIENCE_MERCHANT.equals(audience);
    }

    // ==================== 内部 ====================

    /** {@code null} / 非正数 ⇒ 默认 100；超上限 ⇒ 截到 500（与 {@code AuditService} 同口径）。 */
    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 参数缺失的**统一作答** —— 全仓惯例：失败也走 200，形状 {@code {success:false,msg}}。 */
    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
