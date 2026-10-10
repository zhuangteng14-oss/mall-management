package com.minke.mallmanagement.catalog.service;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.goods.service.GoodsCacheService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 违规处置（下架 / 删除）工作流（C3 块 3 商品块）—— 管理端 {@code /admin/catalog/violation/*}。
 *
 * <h2>【规则 v2：两种处置都是「逻辑废弃」，没有物理删】</h2>
 * <ul>
 *   <li><b>下架</b>（{@code delist}）：{@code ShelfState = 0} —— 行还在，买家侧读路径已过滤。</li>
 *   <li><b>删除</b>（{@code delete}）：{@code DeletedAt = now} —— 行还在，买家侧读路径已过滤。</li>
 * </ul>
 * 两者都**不**物理 DELETE（与既有 {@code /goods/goodsDe} 的真 DELETE 相反 —— 那是商家自主下架，
 * 语义是「我不卖了」，可以真删；违规处置要留行以便追责与恢复，见块 2 的 ShelfState 口径）。
 *
 * <h2>【谓词用 (MerchantId, GoodName, ProductCode) 三元组，不用 Id】</h2>
 * 与 {@code GoodsDao.deleteGoods} / {@code updateGoods} 的 WHERE 逐字相同；且审计 / 通知的
 * {@code TargetId} 用三元组（{@code MerchantId:ProductCode}）而非自增 {@code goods.Id}
 * —— 后者会随全量重导变（同块 1 的 BatchId 口径）。商品由管理端从买家可见列表里选出。
 */
@Service
public class GoodsViolationService {

    private final GoodsDao goodsDao;
    private final GoodsCacheService goodsCacheService;

    public GoodsViolationService(GoodsDao goodsDao, GoodsCacheService goodsCacheService) {
        this.goodsDao = goodsDao;
        this.goodsCacheService = goodsCacheService;
    }

    /**
     * 违规下架（{@code ShelfState=0}）。已下架 / 已删除的行不受影响（谓词含 {@code DeletedAt IS NULL}）。
     */
    @Transactional
    public Map<String, Object> delist(String merchantId, String goodName, String productCode) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(merchantId) || isBlank(goodName) || isBlank(productCode)) {
            result.put("success", false);
            result.put("msg", "缺少参数：MerchantId / GoodName / ProductCode");
            return result;
        }
        int affected = goodsDao.delistByTriple(merchantId, goodName, productCode);
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "未找到可下架的商品（可能已下架或已删除）");
            return result;
        }
        goodsCacheService.evictByMerchant(merchantId);
        result.put("success", true);
        result.put("msg", "已下架");
        result.put("affected", affected);
        return result;
    }

    /** 违规删除（{@code DeletedAt=now}，逻辑删除）。 */
    @Transactional
    public Map<String, Object> delete(String merchantId, String goodName, String productCode) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(merchantId) || isBlank(goodName) || isBlank(productCode)) {
            result.put("success", false);
            result.put("msg", "缺少参数：MerchantId / GoodName / ProductCode");
            return result;
        }
        int affected = goodsDao.softDeleteByTriple(merchantId, goodName, productCode, LocalDateTime.now());
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "未找到可删除的商品（可能已删除）");
            return result;
        }
        goodsCacheService.evictByMerchant(merchantId);
        result.put("success", true);
        result.put("msg", "已删除");
        result.put("affected", affected);
        return result;
    }

    /**
     * 处置前的快照（审计 {@code before} 用）—— 纯委托 {@code GoodsDao.selectGoodsForAudit}
     * （谓词 {@code (MerchantId, ProductCode)}，与处置的 WHERE 同源）。
     */
    public List<Map<String, Object>> snapshot(String merchantId, String productCode) {
        return goodsDao.selectGoodsForAudit(merchantId, productCode);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
