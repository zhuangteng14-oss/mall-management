package com.minke.mallmanagement.comment.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.comment.service.SensitiveWordService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 敏感词库 Web 层（C3 块 6 · 整改 commit 5）—— 3 个端点：词表 / 加词 / 删词。契约与规则见
 * {@code docs/agent/context-packs/块6参考.md} §4.5（下称「方案」）；业务规则在
 * {@link SensitiveWordService}，数据访问在 {@code SensitiveWordDao}。
 *
 * <h2>【挂 {@code /sensitive}，与方案 §三 的端点清单逐字一致】</h2>
 * 方案 A4 定死 13 个端点，本类占 {@code /sensitive/list | /sensitive/add | /sensitive/delete}
 * 三条 —— 至此内容域 13 条**全部落地**（{@code /comment/*} 七条 + {@code /report/*} 三条 +
 * 本类三条），见 {@code CommentController} 类注释那张分账表。
 *
 * <h2>【⚠️ 三条都是管理端端点，但**刻意不挂 {@code requireAdmin}**】</h2>
 * 本仓对管理端页面上的端点一贯如此（见 {@code admin/**Controller} 那一族类注释里
 * 逐字重复的「别『顺手』补一个 {@code requireAdmin}」）：闸开着时 {@code AuthInterceptor}
 * 已把非管理员的 {@code role=3} cookie 挡在外面；闸关着时（{@code app.auth.enabled=false}）
 * 本就是本地开发 / 演示场景。此处不新造一道与全仓不一致的鉴权 —— 那会让默认 profile
 * （闸开）上的管理员进不来，是**降级**而**不是**加固。
 *
 * <h2>【⚠️ 本类**不收 {@code Account}】—— 与评论 / 举报那两族的写法不同，这是有意的】</h2>
 * {@code comment} / {@code report} 的写端点收 {@code Account} 是因为**那几行要落作者**、
 * 且要被 {@code IdentityGuard} 验明「是我本人」。词表**没有作者列**（DDL 就四列），
 * 加词 / 删词不需要声明身份 ⇒ 造一个只为满足仪式感的入参，只会给下一个人一个
 * 「原来这里也该有身份」的错误信号。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}；参数一律 {@code required=false}】</h2>
 * 同 {@code CommentController} / {@code ReportController}：约束异常会逃逸成 400 空体，
 * 且撞 {@code xxxControllerIsNotAopProxied} 断言族。
 *
 * <h2>【⚠️ 新增 Controller 必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 本类依赖 {@code SensitiveWordController → SensitiveWordService → SensitiveWordDao /
 * CommentDao}，前两个 bean 要登记（{@code CommentDao} 已在），否则切片在上下文加载期红
 * （本仓第 **15** 次同一处地雷）。
 * <p>⚠️ 2026-10-13 整改补笔又给本类加了一个构造参数 {@link AuditService} —— 它**已在**
 * {@code AbstractSmokeTest} 的 {@code @Import} 里（块 1 起就在，见那条注释里的「块 1 已在」），
 * 故这次**不需要**动那个文件；但地雷的形状不变：给 Controller 加构造依赖 = 那个 bean
 * 必须在切片里拿得到。
 *
 * <h2>【审计：加词 / 删词各挂一条（2026-10-13 整改补笔）】</h2>
 * 两条都是**改敏感词表**这一件事（用户裁决：词表维护是平台动作，要留痕）：
 * <ul>
 *   <li>{@link AuditActions#SENSITIVE_WORD_ADD} —— {@code BeforeJson} 恒 NULL（新增类），
 *       {@code AfterJson} 带这次**副作用**（历史评论被打码的条数）；</li>
 *   <li>{@link AuditActions#SENSITIVE_WORD_DELETE} —— {@code BeforeJson} 是被删那一行的快照
 *       （所以 Service 得在删之前把它取下来），{@code AfterJson} 恒 NULL。</li>
 * </ul>
 * ⚠️ 处理人身份一律取 {@link AuthSession#identify}（**会话**），**不加**入参
 * —— 那等于让人随便署别人的名。
 */
@RestController
@RequestMapping("/sensitive")
public class SensitiveWordController {

    private final SensitiveWordService sensitiveWordService;
    private final AuditService auditService;

    public SensitiveWordController(SensitiveWordService sensitiveWordService,
                                   AuditService auditService) {
        this.sensitiveWordService = sensitiveWordService;
        this.auditService = auditService;
    }

    /**
     * 词表（管理端「敏感词库」tab）。
     *
     * @param Limit  页大小（默认 100，上限 500）
     * @param Offset 偏移（默认 0）
     */
    @PostMapping("/list")
    public ResponseEntity<Object> list(
            @RequestParam(value = "Limit", required = false) Integer Limit,
            @RequestParam(value = "Offset", required = false) Integer Offset
    ) {
        return ResponseEntity.ok(Result.of(sensitiveWordService.list(Limit, Offset)));
    }

    /**
     * 加一个词 —— <b>并立刻扫一遍历史评论</b>（方案 §4.5「加词后扫老评论」）。
     *
     * <p>⚠️ 回体里的 {@code masked} 是**真被改写**的行数（原本已打码的不算），
     * 前端该把它如实报给管理员（「已屏蔽 N 条历史评论」），别用 {@code success} 一个字段概括
     * —— 加了词一条都没命中是**正常**结果，不是失败。
     *
     * @param Word 词面，≤ 64 字符；重复 ⇒ {@code success:false} + 「该敏感词已存在」（业务规则）
     */
    @PostMapping("/add")
    public ResponseEntity<Object> add(
            @RequestParam(value = "Word", required = false) String Word,
            HttpServletRequest request
    ) {
        Map<String, Object> result = sensitiveWordService.add(Word);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("Id", result.get("Id"));
            after.put("Word", result.get("Word"));
            // ⚠️ `Masked` 是**这次加词**顺手打码掉的历史评论条数（副作用，方案 §4.5）
            //    —— 事后问「这条词当时屏蔽了多少条」只有这里答得出。
            after.put("Masked", result.get("masked"));
            auditService.record(AuthSession.identify(request), AuditActions.SENSITIVE_WORD_ADD,
                    "sensitive_word", businessKey(result.get("Word"), Word), null, after);
        }
        return ResponseEntity.ok(Result.of(result));
    }

    /**
     * 删一个词。
     *
     * <p>⚠️ <b>历史评论里的 {@code ***} 不回填</b>（原文没有第二份副本）——
     * 见 {@code SensitiveWordService.delete} 的注释。前端确认文案里要写清这一条。
     *
     * @param Id 词条 Id
     */
    @PostMapping("/delete")
    public ResponseEntity<Object> delete(
            @RequestParam(value = "Id", required = false) Integer Id,
            HttpServletRequest request
    ) {
        if (Id == null) {
            return ResponseEntity.ok(Result.fail("缺少参数：Id"));
        }
        Long id = Id.longValue();
        Map<String, Object> result = sensitiveWordService.delete(id);
        if (Boolean.TRUE.equals(result.get("success"))) {
            // ⚠️ `TargetId` 用**词面**而不是 `Id`：`sensitive_word.Word` 上有唯一键，
            //    词面才是这个词的身份（「删了再加同一个词」会是一个新的 Id，但仍是同一个词）。
            //    词面由 Service 在删除**之前**取下来带回来（见 `SensitiveWordService.delete`）。
            auditService.record(AuthSession.identify(request), AuditActions.SENSITIVE_WORD_DELETE,
                    "sensitive_word", businessKey(result.get("Word"), null), result.get("before"), null);
        }
        return ResponseEntity.ok(Result.of(result));
    }

    /**
     * 审计的 {@code TargetId}（业务键）—— `admin_audit.TargetId` 是 **NOT NULL**：
     * 传 {@code null} 会让整条 INSERT 失败，而 {@code AuditService.record} 是 fail-open 的
     * ⇒ 表现是「这一笔**静默没有留痕**」（TD-20 的教训）。故宁可用入参兜底、也绝不传 null。
     *
     * @param fromResult Service 回体里的业务键（词面）
     * @param fallback   兜底（调用方手里的入参）；两个都拿不到时回一句不会与任何真实词面混淆的占位
     */
    private static String businessKey(Object fromResult, String fallback) {
        if (fromResult != null && !fromResult.toString().isBlank()) {
            return fromResult.toString();
        }
        return (fallback != null && !fallback.isBlank()) ? fallback : "(未取到词面)";
    }
}
