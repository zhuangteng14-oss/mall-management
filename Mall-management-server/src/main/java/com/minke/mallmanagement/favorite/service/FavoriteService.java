package com.minke.mallmanagement.favorite.service;

import com.minke.mallmanagement.favorite.dao.FavoriteDao;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品收藏服务（2026-10-19 用户令 · 终局清道夫第 7 条）。
 *
 * <p>四个动作：加 / 删 / 列表 / 查状态。加与删都是幂等的 —— 重复点「收藏」不会产生
 * 第二行，重复点「取消」也不会报错（{@code INSERT IGNORE} / 条件 {@code DELETE}）。
 * 身份是否属于调用者由 Controller 的 {@code IdentityGuard} 判，本类不重复判。
 */
@Service
public class FavoriteService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final FavoriteDao favoriteDao;

    public FavoriteService(FavoriteDao favoriteDao) {
        this.favoriteDao = favoriteDao;
    }

    public Map<String, Object> add(String userId, Integer goodsId) {
        if (isBlank(userId)) {
            return fail("缺少参数：UserId");
        }
        if (goodsId == null || goodsId <= 0) {
            return fail("缺少参数：GoodsId");
        }
        if (!favoriteDao.goodsExists(goodsId)) {
            return fail("商品不存在或已下架");
        }
        int inserted = favoriteDao.add(userId, goodsId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("favorited", true);
        result.put("added", inserted > 0);
        result.put("msg", inserted > 0 ? "已收藏" : "此前已收藏");
        return result;
    }

    public Map<String, Object> remove(String userId, Integer goodsId) {
        if (isBlank(userId)) {
            return fail("缺少参数：UserId");
        }
        if (goodsId == null || goodsId <= 0) {
            return fail("缺少参数：GoodsId");
        }
        int removed = favoriteDao.remove(userId, goodsId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("favorited", false);
        result.put("removed", removed > 0);
        result.put("msg", removed > 0 ? "已取消收藏" : "此前未收藏");
        return result;
    }

    public Map<String, Object> list(String userId, Integer limit) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(userId)) {
            return fail("缺少参数：UserId");
        }
        int effective = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<Map<String, Object>> rows = favoriteDao.list(userId, effective);
        result.put("success", true);
        result.put("list", rows);
        result.put("total", favoriteDao.count(userId));
        result.put("limit", effective);
        return result;
    }

    public Map<String, Object> check(String userId, Integer goodsId) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(userId) || goodsId == null || goodsId <= 0) {
            result.put("success", true);
            result.put("favorited", false);
            return result;
        }
        result.put("success", true);
        result.put("favorited", favoriteDao.exists(userId, goodsId));
        return result;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
