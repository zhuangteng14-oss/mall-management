package com.minke.mallmanagement.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.admin.dao.AdminAuditDao;
import com.minke.mallmanagement.observe.metrics.AuditMetrics;
import com.minke.mallmanagement.shared.web.AuthSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计服务（C3 批 A）—— **写入口**（{@link #record}）与**读 API**（{@link #list} /
 * {@link #detail} / {@link #facets}）共处一处，因为二者读写的是同一张表、同一套列语义。
 *
 * <h2>【{@link #record} 为什么**吞**异常 —— 这是本类最重要的一条裁决】</h2>
 * 挂钩点在**业务操作成功之后**（见 {@code GoodsController.goodsUp}）。若审计写入抛异常并冒泡，
 * 一个**已经改库成功**的请求会变成 HTTP 500 —— 调用方以为失败、可能重试，而库其实已经改了。
 * ⇒ 两害相权：**宁可丢一条审计行，也不把成功翻成失败**。故 {@code record} 内 catch 一切、
 * 只 log。
 *
 * <p>⚠️ <b>代价必须写下来</b>：这等于「审计可以静默失效」。补偿手段是把失败**记成 ERROR 日志**
 * 且带 ASCII 标记 {@code AUDIT}（本仓日志是 GBK 字节，中文 pattern 检索不到 —— 同
 * {@code WebAuthConfig} 启动日志带 {@code AUTH} 的惯例）。**运维口径：搜 ERROR + AUDIT，
 * 命中即说明审计在漏。** 这是本批登记的一处已知接缝，不是疏忽。
 *
 * <h2>【{@link #record} 刻意**不**加 {@code @Transactional}】</h2>
 * 它有两条事务语义可选，两条都不该要：① 与业务操作同事务 ⇒ 业务回滚会把审计行一起抹掉
 * （那就不是「日志」了）；② 自己开新事务 ⇒ 在业务已提交之后开一个只为 INSERT 一行的事务，
 * 徒增代理与边界。今日形态 = **单条 INSERT、自动提交**，正是想要的。
 *
 * <h2>【{@link #record} 的 {@code actor} 为什么是 {@link AuthSession.Identity} 而不是两个字符串】</h2>
 * 「谁」的取值必须跟**识别**口径一致（{@link AuthSession#identify}）：管理员带 cookie 时
 * {@code authorize} 会把他折成 {@code null}，若这里用授权口径，**管理员的动作会全部记成匿名** ——
 * 而管理员恰恰是审计最该记的人。故类型直接收 {@code Identity}（它已把 userId / role 打包），
 * 把「用哪条口径」这件事**在调用点一次性定死**，而不是让本方法去猜。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 列表默认条数（前端不传 {@code limit} 时）。 */
    public static final int DEFAULT_LIMIT = 100;

    /** 列表上限 —— 防止一次拉爆整表。 */
    public static final int MAX_LIMIT = 500;

    private final AdminAuditDao auditDao;
    private final ObjectMapper objectMapper;

    /** 观测出口（块 7 · commit 2b）—— 只读的一个计数器 + 一条环形事件，见 {@link AuditMetrics}。 */
    private final AuditMetrics auditMetrics;

    public AuditService(AdminAuditDao auditDao, ObjectMapper objectMapper, AuditMetrics auditMetrics) {
        this.auditDao = auditDao;
        this.objectMapper = objectMapper;
        this.auditMetrics = auditMetrics;
    }

    // ==================== 写 ====================

    /**
     * 追加一条审计记录。**挂钩点调它，业务侧不感知失败**（见类注释）。
     *
     * @param actor      操作者身份；{@code null} = 认不出（记 NULL 两列）
     * @param action     动作 tag，取值见 {@link com.minke.mallmanagement.admin.AuditActions}
     * @param targetType 被改对象类型（如 {@code goods}）
     * @param targetId   被改对象标识（如 {@code MerchantId:ProductCode}）
     * @param before     改前快照 —— ⚠️ 传 **Map / List / 可序列化对象**，**别传已序列化的 JSON 字符串**
     *                   （那会被再序列化一层，变成带引号的 JSON 字面量）。无改前态（新增类动作）传 {@code null}
     * @param after      改后快照，同上口径
     */
    public void record(AuthSession.Identity actor, String action, String targetType, String targetId,
                       Object before, Object after) {
        try {
            auditDao.insert(
                    actor == null ? null : actor.userId(),
                    actor == null ? null : actor.role(),
                    action, targetType, targetId,
                    toJson(before), toJson(after));
            // 块 7 · 观测（commit 2b）：走到这里 = 那条 INSERT 真的发出去了。actor 取**会话**
            //（与上面几行同源），失败率这个数只有本环读得到 —— 本方法吞异常。
            auditMetrics.written(actor == null ? null : actor.userId(), action);
        } catch (Exception e) {
            // fail-open：业务已经成功了，不能让审计把它翻成失败。见类注释「为什么吞异常」。
            log.error("审计写入失败（AUDIT）：action={} targetType={} targetId={} —— 业务操作已完成，本行丢失",
                    action, targetType, targetId, e);
            auditMetrics.failed(actor == null ? null : actor.userId(), action, e.toString());
        }
    }

    // ==================== 读 ====================

    /**
     * 列表（时间倒序）。四个筛选参数与 {@code limit} 都**可为 {@code null}**。
     *
     * <p>响应形状随全仓列表端点惯例：{@code {"success":true,"list":[…],"total":N}}，
     * 另带一个 {@code limit} 回显生效值（前端要能显示「按 N 条截断」）。
     */
    public Map<String, Object> list(String action, String targetType, String actorId,
                                    String from, String to, Integer limit) {
        int effective = normalizeLimit(limit);
        List<Map<String, Object>> rows = auditDao.selectAudits(action, targetType, actorId, from, to, effective);
        long total = auditDao.countAudits(action, targetType, actorId, from, to);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", rows);
        result.put("total", total);
        result.put("limit", effective);
        return result;
    }

    /** 详情。主键不存在时答 {@code {success:false,msg:"记录不存在"}} —— **不抛、不 404**，随全仓「失败即 200 + success:false」惯例。 */
    public Map<String, Object> detail(long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("success", true);
            result.put("row", auditDao.selectAuditById(id));
        } catch (EmptyResultDataAccessException e) {
            result.put("success", false);
            result.put("msg", "记录不存在");
        }
        return result;
    }

    /** 筛选面：库中已出现过的 {@code Action} / {@code TargetType} 去重值 —— 供前端下拉。 */
    public Map<String, Object> facets() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("actions", auditDao.selectDistinctActions());
        result.put("targetTypes", auditDao.selectDistinctTargetTypes());
        return result;
    }

    // ==================== 内部 ====================

    /** {@code null → null}（写 NULL 列）；否则序列化成 JSON 文本。序列化失败一并归 {@code null}，不打断审计。 */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("审计快照序列化失败（AUDIT）—— 该列记 NULL", e);
            return null;
        }
    }

    /** {@code null} / 非正数 ⇒ 默认 100；超上限 ⇒ 截到 500。 */
    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
