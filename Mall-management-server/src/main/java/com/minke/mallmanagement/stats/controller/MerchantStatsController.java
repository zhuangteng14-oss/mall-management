package com.minke.mallmanagement.stats.controller;

import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.stats.service.StatsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商家端「经营数据看板」Web 层（可视化段 commit 2）—— 1 个端点：读本店经营数据。
 *
 * <h2>【⚠️ 路径是 {@code /merchant/stats}，不是 {@code /admin/merchant-stats}】</h2>
 * 方案 §一 A2 曾担心 {@code AuthInterceptor} 的放行清单按 {@code /merchant/*} 前缀匹配、
 * 会把这个端点**越权放行**。实测**推翻**（见漂移台账 §E-1，三条反证）：
 * <ol>
 *   <li>{@code WebAuthConfig.AUTH_WHITELIST} 只有 **13 条精确路径**，**没有任何 {@code /merchant/**}
 *       —— 唯一的通配是 {@code /img/**}；</li>
 *   <li>{@code AuthInterceptor.isWhitelisted} 是逐条 {@code MATCHER.match(pattern, uri)}，
 *       **没有前缀短路**；</li>
 *   <li>{@code addInterceptors} 是 {@code addPathPatterns("/**")} + **零 exclude**。
 * </ol>
 * ⇒ 本端点与其他业务端点**同档**（无会话 ⇒ 401）。**不需要**改路径、也**不需要**加窄规则。
 *
 * <h2>【⚠️ {@code MerchantId} 由调用方传，不做归属校验 —— 与既有商家端点一致】</h2>
 * 同 {@code OrderController} 的商家侧读端点（{@code /order/orderDeliveryIfSelect} 等由前端传
 * {@code merchantId}）。本笔**不新增**一道「这个 merchantId 是不是你的」的闸：那需要先有一张
 * 「会话 ↔ 商家」绑定，而全仓**没有**（{@code MerchantApplicationController} 也是收 {@code Mid} 入参）。
 * ⚠️ 单开一道闸会让本端点成为**全仓唯一**做这件事的地方 —— 那是新的不一致，不是加固。
 * 这条属**已知缺口**，与既有商家端点**同档**，登记而非就地发明。
 *
 * <h2>【动词选 POST —— 同 {@code AdminStatsController} 的理由（方案未指定动词）】</h2>
 */
@RestController
@RequestMapping("/merchant/stats")
public class MerchantStatsController {

    private final StatsService statsService;

    public MerchantStatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 本店看板：逐日 + 区间合计 + 环比 + 商品榜。
     *
     * <p>{@code MerchantId} 必填（缺 ⇒ {@code success:false}，同 {@code AdminOrderController}
     * 「参数全可空 + Service 层手判」的既有做法）。
     */
    @PostMapping
    public Result<Map<String, Object>> stats(
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "goodsLimit", required = false) Integer goodsLimit
    ) {
        return Result.of(statsService.merchantStats(MerchantId, from, to, days, goodsLimit));
    }
}
