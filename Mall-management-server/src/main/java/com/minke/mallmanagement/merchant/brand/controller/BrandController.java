package com.minke.mallmanagement.merchant.brand.controller;

import com.minke.mallmanagement.merchant.brand.service.BrandService;
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

/**
 * 品牌加盟用户端 + 管理端（第三批㉗，2026-10-19）。
 *
 * <h2>【用户端两个端点】</h2>
 * {@code /overview} 返回四态与可否再申请；{@code /apply} 提交申请（24h 冷却 / 30 天 3 次
 * 在 Service 里判）。两者都按**登录名**判身份（同 {@code MerchantApplicationController}：
 * 登录名不是业务号，必须走 {@code requireSelfOrAdminByUsername}）。
 *
 * <h2>【管理端两个端点】</h2>
 * {@code /admin/revoke} 与 {@code /admin/renew} 只做档案状态维护；审核本身仍走既有的
 * {@code AdminAccountsController}（它已按 {@code IsBrand} 分流并调用
 * {@code BrandService.approve} 落档案）。本类的管理员判据：
 * 闸关（{@code identify == null}）⇒ 放行（与全仓 fail-open 一致）；闸开 ⇒ 只认
 * {@link AuthSession#ROLE_ADMIN}。
 */
@RestController
@RequestMapping("/merchant/brand")
public class BrandController {

    private final BrandService brandService;
    private final ObjectProvider<UserStateLookup> userStateLookupProvider;

    public BrandController(BrandService brandService,
                           ObjectProvider<UserStateLookup> userStateLookupProvider) {
        this.brandService = brandService;
        this.userStateLookupProvider = userStateLookupProvider;
    }

    @PostMapping("/overview")
    public ResponseEntity<Object> overview(
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, username,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(brandService.overview(username)));
    }

    @PostMapping("/apply")
    public ResponseEntity<Object> apply(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "shopName", required = false) String shopName,
            @RequestParam(value = "licenseNo", required = false) String licenseNo,
            @RequestParam(value = "contactName", required = false) String contactName,
            @RequestParam(value = "contactPhone", required = false) String contactPhone,
            @RequestParam(value = "shopAddress", required = false) String shopAddress,
            @RequestParam(value = "licenseImage", required = false) String licenseImage,
            @RequestParam(value = "brandName", required = false) String brandName,
            @RequestParam(value = "brandNameEn", required = false) String brandNameEn,
            @RequestParam(value = "trademarkNo", required = false) String trademarkNo,
            @RequestParam(value = "trademarkClass", required = false) String trademarkClass,
            @RequestParam(value = "brandLevel", required = false) String brandLevel,
            @RequestParam(value = "logoUrl", required = false) String logoUrl,
            @RequestParam(value = "licenseUrl", required = false) String licenseUrl,
            @RequestParam(value = "mainCategory", required = false) String mainCategory,
            @RequestParam(value = "remark", required = false) String remark,
            HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, username,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(brandService.apply(username, shopName, licenseNo,
                contactName, contactPhone, shopAddress, licenseImage, brandName, brandNameEn,
                trademarkNo, trademarkClass, brandLevel, logoUrl, licenseUrl, mainCategory, remark)));
    }

    @PostMapping("/admin/revoke")
    public ResponseEntity<Object> revoke(
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            @RequestParam(value = "Reason", required = false) String Reason,
            HttpServletRequest request) {
        if (!adminAllowed(request)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(brandService.revoke(MerchantId, Reason)));
    }

    @PostMapping("/admin/renew")
    public ResponseEntity<Object> renew(
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            @RequestParam(value = "ValidTo", required = false) String ValidTo,
            HttpServletRequest request) {
        if (!adminAllowed(request)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(brandService.renew(MerchantId, ValidTo)));
    }

    private static boolean adminAllowed(HttpServletRequest request) {
        AuthSession.Identity identity = AuthSession.identify(request);
        return identity == null
                || (identity.role() != null && identity.role() == AuthSession.ROLE_ADMIN);
    }
}
