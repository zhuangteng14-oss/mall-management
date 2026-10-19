package com.minke.mallmanagement.marketing.discount.controller;

import com.minke.mallmanagement.marketing.discount.service.DiscountService;
import com.minke.mallmanagement.shared.web.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 折扣模板用户端（第三批㉖，2026-10-19）—— 两个**公开**端点：
 * 模板列表 / 划线价预览。
 *
 * <p>两者都不判身份：模板是平台公开配置，预览是纯计算（不写库）。
 * 商品编辑页在保存前用它做实时提示；**权威判据仍在提交时的服务端重算**
 * （{@link DiscountService#snapshotForSubmit}）—— 前端预览被绕过也不影响落库值。
 */
@RestController
@RequestMapping("/goods/discount")
public class DiscountController {

    private final DiscountService discountService;

    public DiscountController(DiscountService discountService) {
        this.discountService = discountService;
    }

    @PostMapping("/templates")
    public ResponseEntity<Object> templates() {
        return ResponseEntity.ok(Result.of(discountService.templates()));
    }

    @PostMapping("/preview")
    public ResponseEntity<Object> preview(
            @RequestParam("DiscountTemplateId") Long DiscountTemplateId,
            @RequestParam("Price") Double Price,
            @RequestParam(value = "GoodType", required = false) String GoodType) {
        return ResponseEntity.ok(Result.of(discountService.preview(DiscountTemplateId, Price, GoodType)));
    }
}
