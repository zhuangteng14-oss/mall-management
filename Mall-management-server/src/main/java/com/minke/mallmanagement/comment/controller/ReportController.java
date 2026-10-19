package com.minke.mallmanagement.comment.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.comment.service.ReportService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 举报域 Web 层（C3 块 6 · commit 4「举报 + 图片审核 + 3 次警告封号」）—— 3 个端点：
 * 提交 / 列表（管理端）/ 处理（管理端）。契约与规则见 {@code docs/agent/context-packs/块6参考.md}
 * §4.4（下称「方案」）；业务规则在 {@link ReportService}，数据访问在 {@code ReportDao}。
 *
 * <h2>【挂 {@code /report}，与方案 §三 的端点清单逐字一致】</h2>
 * 方案 A4 定死 13 个端点，{@code /report/add | /report/list | /report/handle} 三条归本类。
 *
 * <h2>【⚠️ {@code /report/list} 与 {@code /report/handle} 是管理端端点，但**刻意不挂 {@code requireAdmin}**】</h2>
 * 本仓对管理端页面上的端点一贯如此（见 {@code admin/**Controller} 的既有做法）：
 * 闸开着时 {@code AuthInterceptor} 已把非管理员的 {@code role=3} cookie 挡在外面；
 * 闸关着时（{@code app.auth.enabled=false}）本就是本地开发 / 演示场景。
 * 此处**不新造**一道与全仓不一致的鉴权 —— 那是本次整改的「不降级」之外的**另一个**话题，
 * 不该由这一个 Controller 私自改。
 *
 * <h2>【{@code /report/add} 走 {@code Account} 入参判据（登录名版）】</h2>
 * {@code report.ReporterId} 存的是**登录名**（{@code login.UserName}，与
 * {@code comment.AuthorId} 同口径）⇒ 走 {@link IdentityGuard#requireSelfOrAdminByUsername}。
 *
 * <h2>【⚠️ {@code UserStateLookup} 一律走 {@code ObjectProvider}】</h2>
 * 它刻意不被 {@code AbstractSmokeTest} {@code @Import}（见该文件那条 ⚠️）⇒ 裸注入会让
 * {@code @WebMvcTest} 切片在上下文加载期就红。取不到时 {@code getIfAvailable()} 回 {@code null}，
 * {@code requireSelfOrAdminByUsername} 的第三处 fail-open 接住它。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}；参数一律 {@code required=false}】</h2>
 * 同 {@code CommentController} / {@code CartController}：约束异常会逃逸成 400 空体，
 * 且撞 {@code xxxControllerIsNotAopProxied} 断言族。
 *
 * <h2>【⚠️ 新增 Controller 必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 本类依赖 {@code ReportController → ReportService → ReportDao}，三个 bean 都要登记，
 * 否则切片在上下文加载期红（本仓第 14 次同一处地雷）。
 */
@RestController
@RequestMapping("/report")
public class ReportController {

    private final ReportService reportService;

    /**
     * 审计写入口（2026-10-13 整改补笔，「处理举报」留痕）。
     * ⚠️ 它**已在** {@code AbstractSmokeTest} 的 {@code @Import} 里（块 1 起就在）——
     * 这次不必动那个文件，但地雷形状不变：给 Controller 加构造依赖 = 那个 bean 必须在切片里拿得到。
     */
    private final AuditService auditService;

    /** ⚠️ 刻意是 {@link ObjectProvider}，不是裸的 {@code UserStateLookup} —— 见类注释。 */
    private final ObjectProvider<UserStateLookup> userStateLookupProvider;

    public ReportController(ReportService reportService,
                            AuditService auditService,
                            ObjectProvider<UserStateLookup> userStateLookupProvider) {
        this.reportService = reportService;
        this.auditService = auditService;
        this.userStateLookupProvider = userStateLookupProvider;
    }

    /**
     * 提交一条举报（方案 §4.4）。
     *
     * @param Account     举报人（登录名）—— 验明是本人
     * @param TargetType  {@code comment}（评论 / 回复共用）/ {@code goods} / {@code user}
     * @param TargetId    被举报对象 Id：评论 = {@code comment.Id}；商品 = {@code goods.Id}；
     *                    用户 = 业务号（{@code customerid} / {@code merchantid}）
     * @param Reason      辱骂 / 广告 / 违法 / 其他
     * @param Description 理由 = 其他 时**必填**，≤ 100 字
     */
    @PostMapping("/add")
    public ResponseEntity<Object> add(
            @RequestParam(value = "Account", required = false) String Account,
            @RequestParam(value = "TargetType", required = false) String TargetType,
            @RequestParam(value = "TargetId", required = false) String TargetId,
            @RequestParam(value = "Reason", required = false) String Reason,
            @RequestParam(value = "Description", required = false) String Description,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(reportService.add(
                Account, TargetType, TargetId, Reason, Description)));
    }

    /**
     * 举报列表（管理端「举报中心」）。
     *
     * @param Status {@code pending} / {@code handled} / {@code rejected}；空白 = 不筛
     * @param Limit  页大小（默认 100，上限 100；D3-3 统一上限）
     * @param Offset 偏移（默认 0）
     */
    @PostMapping("/list")
    public ResponseEntity<Object> list(
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "Limit", required = false) Integer Limit,
            @RequestParam(value = "Offset", required = false) Integer Offset
    ) {
        return ResponseEntity.ok(Result.of(reportService.list(Status, Limit, Offset)));
    }

    /**
     * 处理一条举报（管理端）：通过 ⇒ 删内容 + 警告 +（满 3 次）封号 + 通知双方；驳回 ⇒ 留待再举报。
     *
     * <p>处理人（落 {@code report.HandlerId}）取**会话身份**里的 {@code userId}：
     * 与 {@code auditService.record(AuthSession.identify(request), …)} 同一口径、同一个来源
     * （{@code AdminAuditDao.ActorId} 存的也是它）。⚠️ 不用入参 —— 那等于让人随便署别人的名。
     * <p>⚠️ 2026-10-13 整改补笔起，这一发**真的**会留痕（{@link AuditActions#REPORT_HANDLE}）：
     * 上面那句以前只是「口径一致」的说明，现在是同一个 {@code identity} 同时也喂给审计
     * —— 两处**不是各取一次**，是同一个值。
     *
     * <p>⚠️ 用 <b>{@link AuthSession#identify}</b>（识别口径）而不是 {@link AuthSession#currentUserId}
     * （授权口径）：后者会把 **cookie 登录的管理员**（{@code role=3}、{@code viaToken=false}）
     * 折成 {@code null} ⇒ 管理端正常用 cookie 登录时反而「查无处理人」。识别口径正是审计侧
     * 一贯用的那一个（管理员本就只能靠它被认出来）。
     *
     * @param Id      举报单 Id
     * @param Approve {@code "1"} / {@code "true"} = 通过；其余（含省略）= 驳回
     * @param Note    处理说明（可空）
     */
    @PostMapping("/handle")
    public ResponseEntity<Object> handle(
            @RequestParam(value = "Id", required = false) Integer Id,
            @RequestParam(value = "Approve", required = false) String Approve,
            @RequestParam(value = "Note", required = false) String Note,
            HttpServletRequest request
    ) {
        if (Id == null) {
            return ResponseEntity.ok(Result.fail("缺少参数：Id"));
        }
        AuthSession.Identity identity = AuthSession.identify(request);
        String handler = identity == null ? null : identity.userId();
        if (handler == null || handler.isBlank()) {
            return ResponseEntity.ok(Result.fail("无法确定处理人身份"));
        }
        boolean approve = "1".equals(Approve) || "true".equalsIgnoreCase(Approve);
        Map<String, Object> result = reportService.handle(Id.longValue(), approve, Note, handler);
        if (Boolean.TRUE.equals(result.get("success"))) {
            // ⚠️ 处理一条举报**不可逆**（通过 = 删被举报内容 + 作者警告 + 可能封号）
            //    ⇒ 审计行是它事后的追溯依据。`BeforeJson` 是举报单处理前的整行快照
            //    （含举报人 `ReporterId` / 被举报对象 `TargetType:TargetId` / `Reason`）；
            //    `AfterJson` 是**这次判了什么、动了什么**。
            // ⚠️ `TargetId` 这里是举报单的自增 `Id`，**不是**业务串 —— `report` 表没有任何
            //    业务串（它自己的身份就是这一行的 Id），且举报单是运行期数据、从不被重新播种
            //    ⇒ 块 2 那种「重导换号」的风险在此不存在。管理端页面上认的也是这个 Id（对得上）。
            Map<String, Object> after = new HashMap<>();
            after.put("Approve", approve);
            after.put("Status", result.get("Status"));
            after.put("Note", Note);
            after.put("ContentAffected", result.get("contentAffected"));
            after.put("WarnCount", result.get("warnCount"));
            after.put("Banned", result.get("banned"));
            auditService.record(identity, AuditActions.REPORT_HANDLE, "report",
                    String.valueOf(Id), result.get("before"), after);
        }
        return ResponseEntity.ok(Result.of(result));
    }
}
