package com.minke.mallmanagement.template.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.template.service.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理端「模板库」Web 层（C3 块 3 就地改造，2026-10-10）—— 4 个端点：列表 / 新增 / 改 / 删。
 *
 * <h2>【合并了原先的两套管理端点】</h2>
 * <ul>
 *   <li>{@code AdminCatalogController} 的 {@code /admin/catalog/template/{list,create,update,delete}}
 *       （块 3，**已删**）—— 商品模板；</li>
 *   <li>{@code AdminNotificationController} 的 {@code /admin/notify/template/list} + {@code /template/save}
 *       （块 1，**已删**）—— 通知模板。</li>
 * </ul>
 * 两者读写的本就该是同一张表（合并后就是），故收成一个端点组。
 * ⚠️ **不保留旧路径别名**：保留只会是「指向已删表的 500 / 空集」，是说谎的接口。
 *
 * <h2>【⚠️ 审计留痕：对通知侧是**新增**】</h2>
 * catalog 侧原先挂 {@code goods_template.*} 三个动作；notify 侧**从未挂审计**（块 1 类注释里
 * 「模板 CRUD 是配置而非平台动作」那条推断）。合并后统一挂 {@code template.*} ⇒
 * 对商品模板等价，对通知模板是**新增留痕**。见 {@link AuditActions#TEMPLATE_CREATE}。
 *
 * <h2>【⚠️ 与其它 admin Controller 同一姿态：**不给 {@code /admin/**} 设角色闸】</h2>
 * TD-18（管理员 cookie 铸不出 token）⇒ 加「须 role=3」的判据会让**默认 profile 上管理员自己也 401**，
 * 而放进白名单是放宽。⚠️ 别「顺手」补一个 {@code requireAdmin}。
 *
 * <h2>【参数全 {@code required = false} + 手判，不加 Bean Validation 注解】</h2>
 * 同 {@code AdminCatalogController}（约束异常逃逸成 400 空体；类级 {@code @Validated} 撞
 * {@code xxxControllerIsNotAopProxied} 断言族）。参数名沿用 PascalCase（{@code Type} / {@code Name} …）
 * 与库列名同源 —— 同块 3 的既有口径。
 */
@RestController
@RequestMapping("/admin/template")
public class AdminTemplateController {

    private final TemplateService templateService;
    private final AuditService auditService;

    public AdminTemplateController(TemplateService templateService, AuditService auditService) {
        this.templateService = templateService;
        this.auditService = auditService;
    }

    /**
     * 模板列表（管理端：{@code includeDisabled} 可含停用）。
     *
     * <p>三条筛选同 {@link TemplateService#list}：{@code Type} 精确 / {@code TypePrefix} 前缀
     * （{@code NotifyPage} 传 {@code 'notification.'}、{@code CatalogPage} 传 {@code 'goods.'}）/
     * 都不传 ⇒ 全部。
     */
    @PostMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(value = "Type", required = false) String Type,
            @RequestParam(value = "TypePrefix", required = false) String TypePrefix,
            @RequestParam(value = "includeDisabled", required = false) Boolean includeDisabled
    ) {
        return Result.of(templateService.list(Type, TypePrefix, Boolean.TRUE.equals(includeDisabled)));
    }

    /** 新增模板。**新增类**动作 ⇒ 审计 {@code BeforeJson} 恒 NULL；{@code TargetId} 用 {@code Type:Name}（新增行 Id 没回传，见 §19-9-8）。 */
    @PostMapping("/create")
    public Result<Map<String, Object>> create(
            @RequestParam(value = "Type", required = false) String Type,
            @RequestParam(value = "Kind", required = false) String Kind,
            @RequestParam(value = "Name", required = false) String Name,
            @RequestParam(value = "Title", required = false) String Title,
            @RequestParam(value = "Body", required = false) String Body,
            @RequestParam(value = "Sort", required = false) Integer Sort,
            HttpServletRequest request
    ) {
        Map<String, Object> result = templateService.create(Type, Kind, Name, Title, Body, Sort);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("Type", Type);
            after.put("Kind", Kind);
            after.put("Name", Name);
            after.put("Title", Title);
            after.put("Sort", Sort);
            auditService.record(AuthSession.identify(request), AuditActions.TEMPLATE_CREATE,
                    "template", Type.trim() + ":" + Name.trim(), null, after);
        }
        return Result.of(result);
    }

    /**
     * 改模板（改名 / 改标题 / 改正文 / 启停 / 改排序）。
     * ⚠️ {@code Type} **不是**本端点的参数 —— 模板类型不可改（见 {@code TemplateDao.update}）。
     */
    @PostMapping("/update")
    public Result<Map<String, Object>> update(
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "Name", required = false) String Name,
            @RequestParam(value = "Title", required = false) String Title,
            @RequestParam(value = "Body", required = false) String Body,
            @RequestParam(value = "Enabled", required = false) Integer Enabled,
            @RequestParam(value = "Sort", required = false) Integer Sort,
            @RequestParam(value = "Kind", required = false) String Kind,
            HttpServletRequest request
    ) {
        if (id == null) {
            return Result.of(missingId());
        }
        Map<String, Object> result = templateService.update(id, Name, Title, Body, Enabled, Sort, Kind);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("Name", Name);
            after.put("Enabled", Enabled);
            after.put("Sort", Sort);
            after.put("Kind", Kind);
            auditService.record(AuthSession.identify(request), AuditActions.TEMPLATE_UPDATE,
                    "template", id.toString(), result.get("before"), after);
        }
        return Result.of(result);
    }

    /** 删模板（逻辑废弃：{@code Enabled=0}，规则 v2）。 */
    @PostMapping("/delete")
    public Result<Map<String, Object>> delete(
            @RequestParam(value = "id", required = false) Long id,
            HttpServletRequest request
    ) {
        if (id == null) {
            return Result.of(missingId());
        }
        Map<String, Object> result = templateService.delete(id);
        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.TEMPLATE_DELETE,
                    "template", id.toString(), result.get("before"), null);
        }
        return Result.of(result);
    }

    // ==================== 内部 ====================

    /** 缺 {@code id} 的统一回体（三处写端点的同一形状，照 {@code AdminCatalogController}）。 */
    private static Map<String, Object> missingId() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("success", false);
        bad.put("msg", "缺少参数：id");
        return bad;
    }
}
