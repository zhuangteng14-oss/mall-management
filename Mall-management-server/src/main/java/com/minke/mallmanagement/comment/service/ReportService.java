package com.minke.mallmanagement.comment.service;

import com.minke.mallmanagement.shared.web.PageLimits;
import com.minke.mallmanagement.admin.service.UserGovernanceService;
import com.minke.mallmanagement.catalog.service.GoodsViolationService;
import com.minke.mallmanagement.comment.dao.ReportDao;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 举报域的服务层（C3 块 6 · commit 4「举报 + 图片审核 + 3 次警告封号」）—— <b>业务规则 + 响应组装</b>。
 * 路由与入参绑定在 {@code ReportController}，数据访问在 {@link ReportDao}；
 * 契约与规则见 {@code docs/agent/context-packs/块6参考.md} §4.4（下称「方案」）。
 *
 * <h2>【三条路径各自复用了哪一处的既有能力（不重写第二份）】</h2>
 * <ul>
 *   <li><b>删评论</b> → {@link CommentService#delete}（{@code admin=true}）：连带回复的规则、
 *       逻辑删的谓词全在那一处，本类只传 {@code admin=true}（管理端处置 = 第 2 档权限）。</li>
 *   <li><b>下架商品</b> → {@link GoodsViolationService#delist}：谓词是
 *       {@code (MerchantId, GoodName, ProductCode)} 三元组（不是自增 {@code Id}），
 *       顺带失效买家侧缓存。⚠️ 方案 §4.4 说的是「删内容」；商品侧的既有处置分「下架 / 删除」两档，
 *       举报处置取**下架** {@code ShelfState=0}（可恢复，与块 3 违规处置同一档 —— 举报不一定等于违规到要抹掉）。</li>
 *   <li><b>封号</b> → {@link UserGovernanceService#ban}：方案 §4.4「封禁接口复用『用户与商家治理』的封禁」
 *       逐字。</li>
 * </ul>
 *
 * <h2>【⚠️ 通知发两处，不是一处 —— 方案两处都写了】</h2>
 * 方案 §二 A1 写 {@code report.handled} 的收件人是**举报人**（「举报处理结果 → 举报人」）；
 * 方案 §4.4 写「管理员通过 → 删内容 + 通知**原内容作者**（理由）」。两处是**两个**收件人
 * ⇒ 通过时**各发一条**（同一 code、不同 ToId/ToType），各带各的正文：
 * <ul>
 *   <li>原内容作者：告诉他内容因哪条理由被处理（他要能申诉/知悉）；</li>
 *   <li>举报人：告诉他举报已被处理（否则他永远不知道结果，「举报」这个动作没有回音）。</li>
 * </ul>
 * 驳回时只回**举报人**一条 —— 原内容作者没被处置，不该收到任何通知（那等于告诉他「有人举报过你」）。
 *
 * <h2>【⚠️ 举报匿名 = 对被举报人匿名，不是对管理端匿名】</h2>
 * 方案 §4.4「举报匿名（管理端可见全名 + 类型）」⇒ {@link #list} 原样带出
 * {@code ReporterId}（管理端要看得见）；而发给**被举报人**的通知正文里**绝不出现举报人**
 * （出了就等于把匿名举报实名化了）。
 */
@Service
public class ReportService {

    // ==================== 被举报对象类型（写侧收紧） ====================

    /** 评论 / 回复 —— 都是 {@code comment} 表的行（方案 §4.4「可举报：评论 / 回复」，共用一型）。 */
    public static final String TARGET_COMMENT = "comment";

    /** 商品。 */
    public static final String TARGET_GOODS = "goods";

    /** 用户。 */
    public static final String TARGET_USER = "user";

    private static final Set<String> TARGET_TYPES = Set.of(TARGET_COMMENT, TARGET_GOODS, TARGET_USER);

    // ==================== 举报理由（方案 §4.4 四个，写侧收紧） ====================

    public static final String REASON_ABUSE = "辱骂";
    public static final String REASON_AD = "广告";
    public static final String REASON_ILLEGAL = "违法";
    public static final String REASON_OTHER = "其他";

    private static final Set<String> REASONS =
            Set.of(REASON_ABUSE, REASON_AD, REASON_ILLEGAL, REASON_OTHER);

    /** 「其他」必填文本框的字数上限 —— 方案 §4.4「其他 → 下方 100 字文本框」。 */
    public static final int MAX_DESCRIPTION = 100;

    // ==================== 举报单状态（与建表列注释同源） ====================

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_HANDLED = "handled";
    public static final String STATUS_REJECTED = "rejected";

    // ==================== 警告 / 封号 ====================

    /** 警告累计到 3 次自动封号（方案 §4.4）。 */
    public static final int WARN_THRESHOLD = 3;

    /** 自动封禁时写给被封者看的理由（落 {@code user.banReason}，见 {@code UserGovernanceService.ban}）。 */
    private static final String AUTO_BAN_REASON = "内容多次违规，累计 3 次警告，系统自动封禁";

    public static final int DEFAULT_LIMIT = PageLimits.DEFAULT_PAGE_SIZE;
    public static final int MAX_LIMIT = PageLimits.MAX_PAGE_SIZE;

    /** 通知的 {@code TargetType} —— 与 {@code CommentService.NOTIFY_TARGET_COMMENT} 同值（各域各一份）。 */
    private static final String NOTIFY_TARGET_COMMENT = "comment";
    private static final String NOTIFY_TARGET_GOODS = "goods";
    private static final String NOTIFY_TARGET_USER = "user";

    private final ReportDao reportDao;
    private final CommentService commentService;
    private final GoodsViolationService goodsViolationService;
    private final UserGovernanceService userGovernanceService;
    private final NotificationService notificationService;

    public ReportService(ReportDao reportDao,
                         CommentService commentService,
                         GoodsViolationService goodsViolationService,
                         UserGovernanceService userGovernanceService,
                         NotificationService notificationService) {
        this.reportDao = reportDao;
        this.commentService = commentService;
        this.goodsViolationService = goodsViolationService;
        this.userGovernanceService = userGovernanceService;
        this.notificationService = notificationService;
    }

    // ==================== 写：举报 ====================

    /**
     * 提交一条举报（方案 §4.4）。
     *
     * @param reporterAccount 举报人（登录名，{@code login.UserName}）—— 由 Controller 验明是本人
     * @param targetType      {@code comment / goods / user}
     * @param targetId        被举报对象 Id：评论 = {@code comment.Id}；商品 = {@code goods.Id}；
     *                        用户 = 业务号（{@code customerid} / {@code merchantid}）
     * @param reason          辱骂 / 广告 / 违法 / 其他
     * @param description     理由 = 其他 时**必填**，≤ 100 字
     */
    public Map<String, Object> add(String reporterAccount, String targetType, String targetId,
                                   String reason, String description) {
        if (isBlank(reporterAccount)) {
            return fail("缺少参数：Account");
        }
        String reporter = reporterAccount.trim();
        String tt = trimToNull(targetType);
        if (tt == null || !TARGET_TYPES.contains(tt)) {
            return fail("未知的举报对象类型：" + targetType + "（只能是 comment / goods / user）");
        }
        String tid = trimToNull(targetId);
        if (tid == null) {
            return fail("缺少参数：TargetId");
        }
        String why = trimToNull(reason);
        if (why == null || !REASONS.contains(why)) {
            return fail("未知的举报理由：" + reason + "（只能是 辱骂 / 广告 / 违法 / 其他）");
        }
        String note = trimToNull(description);
        if (REASON_OTHER.equals(why) && note == null) {
            return fail("选择「其他」时必须填写说明");
        }
        if (note != null && note.length() > MAX_DESCRIPTION) {
            return fail("举报说明最多 " + MAX_DESCRIPTION + " 字（当前 " + note.length() + " 字）");
        }

        try {
            Integer role = reportDao.selectRoleByUsername(reporter);
            if (role == null) {
                return fail("账号不存在：" + reporter);
            }
            // ── 解析被举报对象 + 它的作者（「不能举报自己」要它） ──────────────────────
            String author = resolveTargetAuthor(tt, tid);
            if (author == null) {
                return fail("被举报的内容不存在或已不可举报");
            }
            if (author.equals(reporter)) {
                return fail("不能举报自己发布的内容");
            }
            // ── 同内容不能二次举报（先查；后一半是唯一键 uk_report_target_reporter） ─────
            Map<String, Object> existing = reportDao.selectByTargetReporter(tt, tid, reporter);
            if (existing != null) {
                String status = str(existing.get("Status"));
                if (STATUS_REJECTED.equals(status)) {
                    // 方案 §4.4「驳回后可再举报（需重选/填理由）」= UPDATE 同一行复活。
                    long id = ((Number) existing.get("Id")).longValue();
                    if (reportDao.revive(id, why, note) == 0) {
                        return fail("再举报失败，请重试");
                    }
                    Map<String, Object> ok = new HashMap<>();
                    ok.put("success", true);
                    ok.put("Id", id);
                    ok.put("revived", true);
                    ok.put("msg", "已重新提交举报");
                    return ok;
                }
                return fail("您已举报过该内容，不能重复举报");
            }
            if (reportDao.insert(tt, tid, reporter, role, why, note) == 0) {
                return fail("举报提交失败，请重试");
            }
            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            ok.put("Id", reportDao.lastInsertId());
            ok.put("msg", "举报已提交，等待管理员处理");
            return ok;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("举报提交失败：" + e.getMessage());
        }
    }

    // ==================== 读：列表（管理端） ====================

    /** 举报列表。{@code status} 空白 = 不筛（待处理与历史一并回）。 */
    public Map<String, Object> list(String status, Integer limit, Integer offset) {
        String st = trimToNull(status);
        if (st != null && !STATUS_PENDING.equals(st) && !STATUS_HANDLED.equals(st)
                && !STATUS_REJECTED.equals(st)) {
            return fail("未知的状态：" + status + "（只能是 pending / handled / rejected）");
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = (offset == null || offset < 0) ? 0 : offset;
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", reportDao.selectReports(st, effectiveLimit, effectiveOffset));
            result.put("total", reportDao.countReports(st));
            result.put("limit", effectiveLimit);
            result.put("offset", effectiveOffset);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("举报列表加载失败：" + e.getMessage());
        }
    }

    // ==================== 写：处理（管理端） ====================

    /**
     * 处理一条举报（方案 §4.4）。
     *
     * @param id      举报单 Id
     * @param approve {@code true} = 通过（删内容 + 警告 + 通知双方）；{@code false} = 驳回
     * @param note    处理说明（可空）
     * @param handler 处理人 **会话身份号**（{@code AuthSession.identify().userId()}，
     *                管理员即其 {@code login.Id}）—— ⚠️ 与 {@code ReporterId}（登录名）
     *                **不是同一个空间**，见 {@code ReportDao} 里 {@code HandlerId} 那条 ⚠️。
     */
    public Map<String, Object> handle(long id, boolean approve, String note, String handler) {
        if (isBlank(handler)) {
            return fail("缺少参数：Handler");
        }
        try {
            Map<String, Object> row = reportDao.selectReport(id);
            if (row == null) {
                return fail("举报不存在");
            }
            if (!STATUS_PENDING.equals(str(row.get("Status")))) {
                return fail("该举报已被处理");
            }
            String targetType = str(row.get("TargetType"));
            String targetId = str(row.get("TargetId"));
            String reporter = str(row.get("ReporterId"));

            if (!approve) {
                // 驳回：方案 §4.4「驳回后可再举报」⇒ 留成 rejected 供举报人复活。
                if (reportDao.updateHandle(id, STATUS_REJECTED, handler.trim(), trimToNull(note)) == 0) {
                    return fail("该举报已被处理");
                }
                Map<String, Object> ok = new HashMap<>();
                ok.put("success", true);
                ok.put("Id", id);
                ok.put("Status", STATUS_REJECTED);
                // 改前态 —— 给调用方挂钩审计用（`ReportController` 的 `report.handle`）。
                // 口径同 `AdminCatalogController` / `AdminTemplateController` 的 `result.get("before")`。
                ok.put("before", row);
                ok.put("msg", "已驳回（举报人可在原理由上重新举报）");
                return ok;
            }

            // 通过：先 CAS 占住这一单（并发下第二个人的 affected = 0），再动内容。
            if (reportDao.updateHandle(id, STATUS_HANDLED, handler.trim(), trimToNull(note)) == 0) {
                return fail("该举报已被处理");
            }
            String author = resolveTargetAuthor(targetType, targetId);
            int contentAffected = removeContent(targetType, targetId, author);
            int warn = bumpWarn(author);
            boolean banned = warn >= WARN_THRESHOLD;
            if (banned) {
                // 方案 §4.4「3 次 → 自动封号」，复用「用户与商家治理」的封禁接口。
                userGovernanceService.ban(author, AUTO_BAN_REASON);
            }
            notifyHandled(targetType, targetId, reporter, author, str(row.get("Reason")), banned);

            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            ok.put("Id", id);
            ok.put("Status", STATUS_HANDLED);
            ok.put("contentAffected", contentAffected);
            ok.put("warnCount", warn);
            ok.put("banned", banned);
            // 改前态 —— 同上面驳回那条（`before` 同时是「这次是不是一次写」的判据）。
            ok.put("before", row);
            ok.put("msg", banned ? "已处理，该用户累计 3 次警告已自动封禁" : "已处理");
            return ok;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("举报处理失败：" + e.getMessage());
        }
    }

    // ==================== 内部：处置 ====================

    /**
     * 删被举报的内容。
     *
     * <ul>
     *   <li>{@code comment} → {@link CommentService#delete}（{@code admin=true}，顶层连带回复）；</li>
     *   <li>{@code goods} → {@link GoodsViolationService#delist}（三元组下架）；</li>
     *   <li>{@code user} → **没有可删的「内容」**（被举报的就是这个人本身）⇒ 不删，只走警告 / 封号。</li>
     * </ul>
     *
     * <p>⚠️ 全程 fail-soft：内容可能已被作者自删 / 商品已被商家下架 ⇒ 那一步回 0/失败，
     * 但**举报单已经 handled 了**（CAS 已消费）。此处**不翻案**：处置的语义是「管理端判定举报成立」，
     * 内容此刻还在不在是另一件事。真把它翻成失败会让管理员反复点、而 CAS 已过 ⇒ 永远失败。
     */
    private int removeContent(String targetType, String targetId, String author) {
        try {
            if (TARGET_COMMENT.equals(targetType)) {
                Long cid = parseLong(targetId);
                if (cid == null) {
                    return 0;
                }
                Map<String, Object> r = commentService.delete(cid, author, true);
                return Boolean.TRUE.equals(r.get("success")) ? 1 : 0;
            }
            if (TARGET_GOODS.equals(targetType)) {
                Long gid = parseLong(targetId);
                if (gid == null) {
                    return 0;
                }
                Map<String, Object> g = reportDao.selectGoodsTarget(gid);
                if (g == null) {
                    return 0;
                }
                Map<String, Object> r = goodsViolationService.delist(
                        str(g.get("MerchantId")), str(g.get("GoodName")), str(g.get("ProductCode")));
                Object affected = r.get("affected");
                return (affected instanceof Number n) ? n.intValue()
                        : (Boolean.TRUE.equals(r.get("success")) ? 1 : 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * 警告 +1 并回**加完之后**的数（方案 §4.4「{@code user.WarnCount +1}」）。
     *
     * <p>⚠️ 用 {@code UPDATE ... WarnCount = WarnCount + 1} 的**原子自增**再读回来，
     * 不是「先读后写」—— 后者在并发下会丢计数，而 3 次封号正靠这个数。
     *
     * @return 加完后的警告数（账号不在 {@code user} 表 ⇒ 0）
     */
    private int bumpWarn(String author) {
        if (isBlank(author)) {
            return 0;
        }
        try {
            reportDao.incrWarnCount(author);
            Integer n = reportDao.selectWarnCount(author);
            return n == null ? 0 : n;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * 通过时通知**双方**（见类注释那一节）：原内容作者（带理由）+ 举报人（结果回执）。
     * 全程 fail-soft —— 通知是派生物，不该把已完成的处置翻成失败。
     */
    private void notifyHandled(String targetType, String targetId, String reporter,
                               String author, String reason, boolean banned) {
        try {
            if (!isBlank(author)) {
                String who = "您发布的" + targetLabel(targetType) + "因「" + reason + "」已被管理员处理";
                if (banned) {
                    who += "；因累计 3 次违规警告，账号已被封禁";
                }
                // ⚠️ 正文里**不出现举报人**（举报对被举报人匿名，见类注释）。
                send(author, "举报处理结果", who, targetType, targetId);
            }
            if (!isBlank(reporter)) {
                send(reporter, "举报已处理",
                        "您举报的" + targetLabel(targetType) + "已被管理员处理（理由：" + reason + "）",
                        targetType, targetId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 按收件人角色选 {@code ToType}，发一条 {@code report.handled}。
     *
     * <p>⚠️ <b>【一列两种号】{@code to} 是**登录名**（本类全长都按登录名走，见
     * {@link #resolveTargetAuthor}），而 {@code notification.ToId} 的契约是**业务号**
     * （{@code NotificationController} 的 javadoc 逐字）⇒ 中间必须换算一次。</b>
     * 漏这一步时这条通知对收件人等于没发：行写进去了，收件箱按业务号拉 ⇒ 拉不到
     * （2026-10-13 实测：8 行 {@code report.handled} 的 ToId 全是登录名，举报人一条都看不见）。
     * 两个收件人（原内容作者 / 举报人）同走本方法 ⇒ 一处修，两条都对。
     */
    private void send(String to, String title, String content, String targetType, String targetId) {
        Integer role = reportDao.selectRoleByUsername(to);
        String toType = (role != null && role == 2)
                ? NotificationService.TO_TYPE_MERCHANT : NotificationService.TO_TYPE_CUSTOMER;
        String toBizId = reportDao.selectUserIdByUsername(to);
        if (isBlank(toBizId)) {
            return;   // 换算不出业务号 ⇒ 写了也是一条谁都读不到的死行（同 CommentService 那三处）
        }
        notificationService.send(UUID.randomUUID().toString(), toBizId, toType,
                NotificationEvents.REPORT_HANDLED, title, content,
                notifyTargetType(targetType), targetId);
    }

    // ==================== 内部：解析 ====================

    /**
     * 把 {@code (目标类型, 目标 Id)} 还原成「作者 / 归属人的**登录名**」——
     * 「不能举报自己」的判据与警告 / 通知的收件人都用它。解析不出 ⇒ {@code null}。
     *
     * <ul>
     *   <li>{@code comment} ⇒ 该行的 {@code AuthorId}（已是登录名）；</li>
     *   <li>{@code goods}   ⇒ 商品所属**商家**的登录名（{@code MerchantId}(业务号) → {@code user.username}）；</li>
     *   <li>{@code user}    ⇒ 被举报那个人自己的登录名（业务号 → 登录名）。</li>
     * </ul>
     *
     * <p>⚠️ {@code comment} 侧**已删的行也算「不可举报」**（返回 {@code null}）——
     * 举报一条已经删掉的评论没有处置对象，让它落单只会给管理端一堆点不动的单子。
     */
    private String resolveTargetAuthor(String targetType, String targetId) {
        if (TARGET_COMMENT.equals(targetType)) {
            Long cid = parseLong(targetId);
            if (cid == null) {
                return null;
            }
            Map<String, Object> c = reportDao.selectCommentTarget(cid);
            if (c == null || "deleted".equals(str(c.get("Status")))) {
                return null;
            }
            return trimToNull(str(c.get("AuthorId")));
        }
        if (TARGET_GOODS.equals(targetType)) {
            Long gid = parseLong(targetId);
            if (gid == null) {
                return null;
            }
            Map<String, Object> g = reportDao.selectGoodsTarget(gid);
            if (g == null) {
                return null;
            }
            return reportDao.selectUsernameByUserId(str(g.get("MerchantId")));
        }
        if (TARGET_USER.equals(targetType)) {
            Map<String, Object> u = reportDao.selectUserTargetByBusinessId(targetId);
            return u == null ? null : trimToNull(str(u.get("username")));
        }
        return null;
    }

    // ==================== 内部：小工具 ====================

    private static String targetLabel(String targetType) {
        if (TARGET_COMMENT.equals(targetType)) {
            return "评论";
        }
        if (TARGET_GOODS.equals(targetType)) {
            return "商品";
        }
        return "账号";
    }

    /**
     * 通知的 {@code TargetType} 是**通知域自己的**三值（{@code comment / goods / user}）——
     * 与举报的 {@code TargetType} 恰好同形，但两者是**不同的列、不同的语义**
     * （一个指「举报了什么」，一个指「通知点开跳到哪」）。这里显式映射而不是直接透传，
     * 是为了让「将来两者分叉」这件事只需要改这一处。
     */
    private static String notifyTargetType(String targetType) {
        if (TARGET_GOODS.equals(targetType)) {
            return NOTIFY_TARGET_GOODS;
        }
        if (TARGET_USER.equals(targetType)) {
            return NOTIFY_TARGET_USER;
        }
        return NOTIFY_TARGET_COMMENT;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Long parseLong(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }
}
