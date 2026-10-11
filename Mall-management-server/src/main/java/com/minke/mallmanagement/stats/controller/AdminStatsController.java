package com.minke.mallmanagement.stats.controller;

import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.stats.service.StatsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端「经营数据看板」Web 层（可视化段 commit 2）—— 2 个端点：读 / 全量重建。
 *
 * <h2>【⚠️ 为什么不给 {@code /admin/**} 设角色闸（TD-18）】</h2>
 * 同 {@code AdminOrderController} / {@code AdminTemplateController}：管理员 cookie 铸不出 token
 * ⇒ 加「须 role=3」的判据会让**默认 profile 上管理员自己也 401**（那是后退，不是加固）。
 * ⚠️ 别「顺手」补一个 {@code requireAdmin}。真正把端点挡在门外的是 `app.auth.enabled`
 * 与 `AuthInterceptor`（默认 profile 上开）—— 未登录打过来就是 401。
 *
 * <h2>【⚠️ 动词选 POST，不是 GET（与方案的一处刻意统一）】</h2>
 * 方案 §五 把这两个读端点写成 `` `/admin/stats` ``（不带动词）、把重建写成 {@code POST /admin/stats/rebuild}
 * （带动词）。落地时**读端点也选 POST**，理由是**同域内的一致性**：本仓管理端的读端点
 * **全部**是 POST（{@code /admin/order/list} · {@code /admin/order/monitor} · {@code /admin/audit/list}
 * · {@code /admin/audit/facets} · {@code /admin/catalog/*}）。为这两个读端点单开全仓第 2 个
 * GET 读端点，收益只是「更 REST」，代价是「管理端的动词从此有两种、读代码的人要多问一句」。
 * ⇒ 选一致。<b>这是对方案未指定处的填补，不是对方案的偏离</b>（方案没写动词）。
 *
 * <h2>【参数全 {@code required=false} + 手判，不加 Bean Validation 注解】</h2>
 * 同 {@code AdminOrderController}：约束异常会逃逸成 400 空体，且类级 {@code @Validated}
 * 撞 {@code xxxControllerIsNotAopProxied} 断言族。区间语义见 {@code StatsService.resolveRange}。
 *
 * <h2>【只有 {@code rebuild} 是写 ⇒ 只有它动了库】</h2>
 * ⚠️ 与 {@code AdminOrderController.intervene} 的区别：那一处挂了 {@code AuditService} 留痕，
 * 本处的 {@code rebuild} **不挂审计**。理由：留痕表 {@code admin_audit} 记的是「对**某个业务对象**
 * 做了处置」，而 {@code rebuild} 没有任何 {@code TargetId} 可传
 * （{@code admin_audit.TargetId} 是 NOT NULL —— 见 {@code AdminOrderController} 注释里 block 3 的教训），
 * 且它不改变任何业务对象的**状态**，只重建一份派生表。真要记，记的是运维日志，不是审计流。
 */
@RestController
@RequestMapping("/admin/stats")
public class AdminStatsController {

    private final StatsService statsService;

    public AdminStatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 看板数据：全站逐日 + 区间合计 + 环比 + 各商家汇总。
     *
     * <p>{@code days} 给了就按「最近 N 天」（相对**库时钟**，是唯一能给环比的形态）；
     * 否则用 {@code from}/{@code to}；都没给则回全部已聚合日（见 {@code StatsService.resolveRange}）。
     */
    @PostMapping
    public Result<Map<String, Object>> stats(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "days", required = false) Integer days
    ) {
        return Result.of(statsService.adminStats(from, to, days));
    }

    /**
     * 全量重建 {@code stats_daily} —— 清空 + 从 {@code order} 重算两个口径。
     *
     * <p>用途有二：① 首次部署 / 换数据之后把历史补上（滚动 job 只碰最近几天）；
     * ② 订单被全量重导后**清掉已经不存在的日期行**（滚动 job 做不到，见 {@code StatsAggregateJob} 注释）。
     */
    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuild() {
        return Result.of(statsService.rebuild());
    }
}
