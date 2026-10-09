package com.minke.mallmanagement.admin.controller;

import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.shared.web.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * admin 域的 Web 层（C3 批 A：权限与审计）—— **只读** 3 个端点：列表 / 详情 / 筛选面。
 * 写入口在 {@link AuditService#record}（由各业务端点的挂钩点调用），**不**经 HTTP 暴露：
 * 审计行只能由**系统**追加，不能由任何调用方「补一条」。
 *
 * <h2>【类级前缀 {@code /admin}，路径段 {@code /audit}】</h2>
 * 与前端侧边栏「可观测域 → 权限与审计」对应（该子项的 URL 段是 {@code /admin/observe/audit}，
 * 那是**前端路由**；本类是**后端 API 前缀**，两者不同名、也不该强行同名 —— 前端路由是
 * 「页面在哪」，后端前缀是「资源在哪」）。全 POST，随全仓（无 PUT / DELETE）。
 *
 * <h2>⚠️【本批**不**给 {@code /admin/**} 设服务端门禁 —— 已知接缝，登记不修】</h2>
 * 理想态是「只有 role=3（管理员）能调」。今天做不到，有两个**已登记**的前因：
 * <ol>
 *   <li><b>a3 的鉴权闸整体是关的</b>（{@code application-a3.properties} 的
 *       {@code app.auth.enabled=false}）⇒ 在演示环境里 {@code /admin/audit/*} 与其余所有端点
 *       一样**不设防**。这是 a3 的既有姿态，不是本批引入的。</li>
 *   <li><b>管理员铸不出 token</b>（TD-18：{@code AuthSession.authorize} 把 cookie 来的
 *       {@code role=3} 折成 {@code null}）⇒ 若本批把 {@code /admin/**} 加进
 *       {@code AUTH_WHITELIST}，那是**放宽**；若给它加「须 role=3」的判据，那么在**默认 profile
 *       （闸开）**上管理员自己也进不来 —— 两头都不对。</li>
 * </ol>
 * ⇒ 本批**不动鉴权面**（既不进白名单、也不加角色判据），把这条接缝与 TD-18 绑在一起登记，
 * 等铸票路径定案后**一并**收口。⚠️ 别在本类「顺手」加一个 {@code requireAdmin} —— 它会同时
 * 让默认 profile 上的管理员 401（见上第 2 条）。
 *
 * <h2>【响应体：3 个端点全走 {@link Result}】</h2>
 * 它们是**新**端点，没有历史调用方，故不落入 {@code Result} 类注释里「例外 5 项」中的任何一项
 * —— 直接用 {@code Result.of(...)} 包 Service 返回的 {@code Map}，形状与其余读端点一致。
 */
@RestController
@RequestMapping("/admin")
public class AdminAuditController {

    private final AuditService auditService;

    public AdminAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 审计列表（时间倒序 + 四个可选筛选）。
     *
     * <p>筛选参数**全部 {@code required = false}**（不传 = 不筛）。{@code from} / {@code to} 收的是
     * {@code 'YYYY-MM-DD HH:MM:SS'} 文本，直接与 datetime 列比较（MySQL 隐式转换）；
     * 前端用 {@code <input type="datetime-local">} 的 {@code 'YYYY-MM-DDTHH:MM'} 需自行补秒
     * —— 见 {@code AuditPage.vue} 的 {@code toSqlTime}。
     */
    @PostMapping("/audit/list")
    public Result<Map<String, Object>> list(
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(auditService.list(action, targetType, actorId, from, to, limit));
    }

    /** 审计详情（按主键）。不存在 ⇒ 200 + {@code {success:false,msg:"记录不存在"}}。 */
    @PostMapping("/audit/detail")
    public Result<Map<String, Object>> detail(@RequestParam("id") Long id) {
        return Result.of(auditService.detail(id));
    }

    /** 筛选面：库中出现过的 Action / TargetType 去重值 —— 前端下拉一次拉齐。 */
    @PostMapping("/audit/facets")
    public Result<Map<String, Object>> facets() {
        return Result.of(auditService.facets());
    }
}
