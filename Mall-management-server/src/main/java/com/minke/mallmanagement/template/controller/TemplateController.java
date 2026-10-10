package com.minke.mallmanagement.template.controller;

import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.template.service.TemplateService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模板库**公共读**端点（C3 块 3 就地改造，2026-10-10）—— 2 个端点：{@code /list} + {@code /get}。
 * 各域调同一个。
 *
 * <h2>【前身与替换关系】</h2>
 * 前身是 {@code catalog.controller.CatalogController} 的 {@code POST /catalog/templates}
 * （**已删**）。改成 {@code /template/*} 的理由：这张表已经跨域（商品 + 理由 + 通知 + 将来评论），
 * 再挂 {@code /catalog} 是错的前缀。⚠️ **不保留别名** —— 保留只会是「指向已删语义的接口」，
 * 前后端在同一次未提交改动里一起改，无外部消费者。
 *
 * <h2>【为什么单开这个 Controller，而不是并进各域】</h2>
 * 与「一个域一个 Controller」不同：模板是**平台级**资源，读它的人横跨三端（商家上架选模板、
 * 管理端填理由、将来的评论回复）⇒ 收成一个公共读口，各域的前端都打它。
 *
 * <h2>【两段都只回启用中的】</h2>
 * {@code includeDisabled} 不是本类的参数 —— 公共读**恒** {@code Enabled=1}。管理端要看停用的
 * 走 {@code /admin/template/list}（另一个端点，带 {@code includeDisabled}）。
 *
 * <p>⚠️ 参数全 {@code required = false} + Service 手判，**不加** Bean Validation 注解，类级
 * **不得**有 {@code @Validated}（约束异常在本仓会逃逸成 400 空体，且类级注解会撞
 * {@code xxxControllerIsNotAopProxied} 断言族）。
 */
@RestController
@RequestMapping("/template")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * 按 {@code Type} 列模板。「选模板」弹窗打这个。
     *
     * <p>⚠️ {@code Type} 空 ⇒ 回全部启用中的 —— 是**故意**的宽读（弹窗一般都会传 Type）。
     * ⚠️ **未知 Type 不报错**，回空列表（读侧放宽，见 {@code TemplateService} 类注释）。
     */
    @PostMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(value = "Type", required = false) String Type
    ) {
        return Result.of(templateService.list(Type, null, false));
    }

    /**
     * 取一条 —— 某 {@code Type} 下第一条启用中的模板（按 {@code Sort, Id}）。
     *
     * <p>缺 {@code Type} ⇒ {@code success:false}；有 {@code Type} 但一条都没有 ⇒
     * {@code {success:true, row:null}}（「还没建模板」是正常态，不是错误）。
     */
    @PostMapping("/get")
    public Result<Map<String, Object>> get(
            @RequestParam(value = "Type", required = false) String Type
    ) {
        return Result.of(templateService.get(Type));
    }
}
