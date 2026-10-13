package com.minke.mallmanagement.comment.controller;

import com.minke.mallmanagement.comment.service.SensitiveWordService;
import com.minke.mallmanagement.shared.web.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
@RequestMapping("/sensitive")
public class SensitiveWordController {

    private final SensitiveWordService sensitiveWordService;

    public SensitiveWordController(SensitiveWordService sensitiveWordService) {
        this.sensitiveWordService = sensitiveWordService;
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
            @RequestParam(value = "Word", required = false) String Word
    ) {
        return ResponseEntity.ok(Result.of(sensitiveWordService.add(Word)));
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
            @RequestParam(value = "Id", required = false) Integer Id
    ) {
        if (Id == null) {
            return ResponseEntity.ok(Result.fail("缺少参数：Id"));
        }
        return ResponseEntity.ok(Result.of(sensitiveWordService.delete(Id.longValue())));
    }
}
