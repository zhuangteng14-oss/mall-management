package com.minke.mallmanagement.admin.service;

import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端**投递工作流**（C3 块 1 · I2）：受众解析 → 逐条扇出 → 汇总。
 *
 * <h2>【为什么它不直接调 {@code NotificationDao}】</h2>
 * 三层约定（{@code context-packs/three-layers.md}）里 Service 只调**自己域的** DAO。
 * 受众解析看似要读 {@code user} 表，但读它的 SQL 已在 {@code NotificationDao}
 * （{@code selectCustomerIds} / {@code selectMerchantIds}，只读投影），
 * 由 {@link NotificationService#resolveAudience} 收口 ⇒ 本类只跟**公共层的 Service**打交道，
 * admin → notification 的单向依赖得以保持（块 6 的触发点将来也要引 notification，反向依赖会把它锁死）。
 *
 * <h2>【扇出：一次投递 = 一个 {@code BatchId} + N 行】</h2>
 * 「已读时刻落在行上」这条表结构决定（见建表块注释）⇒ 广播**必须**扇出，不能一行多人共享读态。
 * {@code BatchId} 在**循环外**生成一次，扇出的每一行共享它 —— 管理端「通知历史」就是按它把 N 行
 * 收成一行显示（{@code recipientCount}）。⚠️ <b>单发也生成一个</b>：列是 {@code NOT NULL}，
 * 且让「单发的历史」与「群发的历史」在同一个视图里形状一致。
 *
 * <h2>【返回的 {@code sent} 是**真实落库条数**，不是「提交了几个任务」】</h2>
 * 逐条 {@code INSERT} 在请求线程里同步做完，{@link NotificationService#send} 回布尔
 * ⇒ {@code sent} 可以小于受众数（某几条写失败，已各自记 ERROR + NOTIFY）。
 * <b>刻意不因部分失败而回 {@code success:false}</b>：那会诱导调用方重发整批，
 * 于是已经成功的那部分**再发一遍**。宁可如实回「发出 N 条」并靠日志追丢失的那几条。
 *
 * <h2>【两处比方案更严的校验 —— 都是「宁可当场报错，不要静默产生读不到的行」】</h2>
 * <ol>
 *   <li><b>受众必须显式给</b>：{@code audience} 与 {@code toId} 都不传时**报错**，
 *       不按方案里写的「默认 all」。理由：本端点是**对外的**（一次调用触达全站），
 *       而「漏传参数」是最常见的调用事故 —— 默认 all 会把一次笔误变成一次全站广播。
 *       发送页本来就显式选受众，故这条对正常路径零成本。</li>
 *   <li><b>{@code toType} 必须在取值域内</b>：定向投递时只认 {@code customer} / {@code merchant}。
 *       否则一个拼错的 {@code toType} 会落成一行**永远无人能读到**的通知
 *       （收件箱查询永远带 {@code ToType = ?}），且不报任何错。</li>
 * </ol>
 * ⚠️ 由「加类型不改表」这条口径**不**延伸到这两处：{@code EventType} 是**开放**取值域
 * （后端不校验，见 {@link NotificationEvents}），{@code ToType} 是**封闭**取值域（跟着 user 表走）。
 */
@Service
public class NotificationBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(NotificationBroadcastService.class);

    private final NotificationService notificationService;

    public NotificationBroadcastService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 发一条通知：要么**定向**给一个人（给 {@code toId} + {@code toType}），
     * 要么**群发**给一类/全体受众（给 {@code audience}）。二者只能取其一，且必须取其一。
     *
     * @param eventType  事件类型 code；不传 ⇒ 默认 {@link NotificationEvents#SYSTEM_NOTICE}
     *                   （⚠️ 不校验白名单 —— 「加类型不改表」的兑现点）
     * @param audience   {@code all} / {@code customer} / {@code merchant}；与 {@code toId} 二选一
     * @param targetType 关联对象类型，可空（软引用，无 FK）
     * @param targetId   关联对象标识，可空（软引用，无 FK，前端渲染时容错）
     * @return {@code {success, sent, batchId}}；参数不合规时 {@code {success:false, msg}}
     */
    public Map<String, Object> send(String title, String body, String eventType, String audience,
                                    String toId, String toType, String targetType, String targetId) {
        if (isBlank(title)) {
            return fail("缺少参数：title");
        }
        if (isBlank(body)) {
            return fail("缺少参数：body");
        }
        // 不校验白名单：未知 code 照落（前端负责兜底显示）。见 NotificationEvents 类注释。
        String event = isBlank(eventType) ? NotificationEvents.SYSTEM_NOTICE : eventType.trim();

        String batchId = UUID.randomUUID().toString();
        int sent;

        if (!isBlank(toId)) {
            // —— 定向：一个人 ——
            if (isBlank(toType)) {
                return fail("定向投递缺少参数：toType");
            }
            if (!NotificationService.TO_TYPE_CUSTOMER.equals(toType)
                    && !NotificationService.TO_TYPE_MERCHANT.equals(toType)) {
                return fail("未知收件人类型：toType 只能是 customer / merchant");
            }
            sent = notificationService.send(batchId, toId.trim(), toType, event, title, body, targetType, targetId)
                    ? 1 : 0;
        } else {
            // —— 群发：一类或全体 ——
            if (isBlank(audience)) {
                return fail("缺少投递受众：请给 audience（all / customer / merchant）或 toId");
            }
            if (!NotificationService.isKnownAudience(audience)) {
                return fail("未知受众：audience 只能是 all / customer / merchant");
            }
            sent = 0;
            for (NotificationService.Audience target : notificationService.resolveAudience(audience)) {
                for (String id : target.ids()) {
                    if (notificationService.send(batchId, id, target.toType(), event,
                            title, body, targetType, targetId)) {
                        sent++;
                    }
                }
            }
        }

        // 一条 INFO 作运维证据：批次号是事后把「这次点击」与「库里那 N 行」对上的唯一钥匙。
        log.info("通知投递完成（NOTIFY）：batchId={} eventType={} 受众={} 定向ToId={} 落库={} 条",
                batchId, event, isBlank(audience) ? "-" : audience, isBlank(toId) ? "-" : toId, sent);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sent", sent);
        result.put("batchId", batchId);
        // ⚠️ 回带**生效的** eventType（2026-10-09 C3 块 1 · I2 加）：调用方给的可能是 null
        //    （⇒ 上面已折成 SYSTEM_NOTICE），而 Controller 的审计挂钩要把「实际用了哪个 code」
        //    写进 AfterJson。**不**让 Controller 自己再算一遍默认值 —— 那是同一条规则的第二个副本，
        //    迟早与这里走岔（同 AuditActions 类注释说的「抄错字面量双方都不报错」）。
        result.put("eventType", event);
        return result;
    }

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
