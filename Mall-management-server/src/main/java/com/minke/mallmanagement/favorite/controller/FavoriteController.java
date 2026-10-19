package com.minke.mallmanagement.favorite.controller;

import com.minke.mallmanagement.favorite.service.FavoriteService;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品收藏用户端（2026-10-19 用户令 · 终局清道夫第 7 条）—— 4 个端点：
 * 加 / 删 / 列表 / 状态查询。
 *
 * <p>身份键 {@code UserId}：客户 = {@code user.customerid}，商家 = {@code user.merchantid}，
 * 登录名兜底（与会员中心同口径，见 {@code PointsDao} 顶部的说明）。
 * 每个端点第一行过 {@link IdentityGuard#requireSelfOrAdmin}：
 * 入参必须指向调用者自己，管理员例外。
 */
@RestController
@RequestMapping("/favorite")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @PostMapping("/add")
    public ResponseEntity<Object> add(@RequestParam("UserId") String UserId,
                                      @RequestParam(value = "GoodsId", required = false) Integer GoodsId,
                                      HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, UserId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(favoriteService.add(UserId, GoodsId)));
    }

    @PostMapping("/remove")
    public ResponseEntity<Object> remove(@RequestParam("UserId") String UserId,
                                         @RequestParam(value = "GoodsId", required = false) Integer GoodsId,
                                         HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, UserId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(favoriteService.remove(UserId, GoodsId)));
    }

    @PostMapping("/list")
    public ResponseEntity<Object> list(@RequestParam("UserId") String UserId,
                                       @RequestParam(value = "limit", required = false) Integer limit,
                                       HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, UserId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(favoriteService.list(UserId, limit)));
    }

    @PostMapping("/check")
    public ResponseEntity<Object> check(@RequestParam("UserId") String UserId,
                                        @RequestParam(value = "GoodsId", required = false) Integer GoodsId,
                                        HttpServletRequest request) {
        if (!IdentityGuard.requireSelfOrAdmin(request, UserId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(favoriteService.check(UserId, GoodsId)));
    }
}
