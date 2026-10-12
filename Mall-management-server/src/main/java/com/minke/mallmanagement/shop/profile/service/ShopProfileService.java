package com.minke.mallmanagement.shop.profile.service;

import com.minke.mallmanagement.shop.profile.dao.ShopProfileDao;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 店铺域「店铺档案」的服务层（C3 块 5 · B 组续笔）。**响应组装 + 参数手判**；
 * 路由与入参绑定在 {@code ShopProfileController}，数据访问全在 {@link ShopProfileDao}。
 *
 * <h2>【响应形状】**逐字**如下，前端 {@code api/shop.js} 的 {@code profile()} 只读这些键：</h2>
 * <pre>
 *   { success: true,
 *     MerchantId: "&lt;原样回显&gt;",
 *     UserName:   "&lt;登录名；该商家不在 login 表则空串&gt;",
 *     RegisteredAt: "2026-09-05 18:12:13" | null,
 *     CertStatus:   "approved" | "pending" | "rejected" | "" }
 * </pre>
 *
 * <h2>【⚠️ 三个「不编」】</h2>
 * <ul>
 *   <li>{@code UserName} 查不到 ⇒ **空串**（不是「未知商家」这种话 —— 那句话是前端画的）。</li>
 *   <li>{@code RegisteredAt}：{@code login.CreateTime} 可为 NULL（补列不改存量）⇒ **回 {@code null}**，
 *       前端据此**不画**「开店时长」。⚠️ 回一个「今天」会让每家老店都显示「今天开店」。</li>
 *   <li>{@code CertStatus}：**一条入驻申请都没有 ⇒ 空串**，不是 {@code 'pending'}。
 *       「没提交过」与「在审核中」是两句不同的话，后者会让页面上挂出一枚假的「审核中」。</li>
 * </ul>
 * 同一条口径贯穿本页：**数据不足的模块不显，不用 0 / 占位话冒充**（与 {@code ShopHome.vue}
 * 头卡「粉丝数拿不到就不渲染」是同一条）。
 *
 * <h2>【为什么这里没有身份闸】</h2>
 * 本端点**就是公开面**（店铺主页未登录也要能看）⇒ Controller 刻意**不**挂
 * {@code IdentityGuard}，且路径进了 {@code WebAuthConfig.AUTH_WHITELIST}。
 * 它是 {@code /shop/**} 下**唯一**一条公开路径，另三条（{@code follow} / {@code unfollow} /
 * {@code followState}）仍是身份面。
 *
 * <h2>【读路径，不挂任何写侧挂钩】</h2>
 * 没有 {@code AuditService}、没有 {@code NotificationService}、没有 {@code @Transactional}
 * —— 两条 SELECT，无补偿腿可写（同 {@code ShopProfileDao} 类注释）。
 */
@Service
public class ShopProfileService {

    private final ShopProfileDao shopProfileDao;

    public ShopProfileService(ShopProfileDao shopProfileDao) {
        this.shopProfileDao = shopProfileDao;
    }

    /** 取一家店的公开档案。缺参 ⇒ 200 + {@code success:false}（不逃 400，理由见 Controller 类注释）。 */
    public Map<String, Object> profile(String merchantId) {
        if (isBlank(merchantId)) {
            return fail("缺少参数：MerchantId");
        }
        try {
            Map<String, Object> row = shopProfileDao.selectMerchant(merchantId);

            // ⚠️ 用 HashMap 逐个 put，**不用 putAll**（本仓禁令：putAll 会把 DAO 的列名当契约泄露出去）。
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("MerchantId", merchantId);

            // ⚠️ 不能写 `String.valueOf(row.get("UserName"))` —— 值为 null 时会得到字符串 "null"（四个字母）。
            Object rawName = row == null ? null : row.get("UserName");
            String userName = rawName == null ? "" : String.valueOf(rawName);
            result.put("UserName", userName);
            // row 为 null（该商家不在 login 表）⇒ 两个「不知道」都是 null / 空串，见类注释「三个不编」。
            result.put("RegisteredAt", row == null ? null : row.get("RegisteredAt"));
            result.put("CertStatus", isBlank(userName) ? "" : nvl(shopProfileDao.selectCertStatus(userName)));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("查询店铺信息失败：" + e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /**
     * ⚠️ {@code queryForList} 的单元格在 SQL 里是 NULL 时值是 {@code null}，而 {@code ArrayList} / 包装
     * 本身都允许 null —— 但 {@code String.valueOf(null)} 会给出字符串 {@code "null"}（四个字母）。
     * 这里显式收成空串：下游（前端）判的是 {@code ''}。
     */
    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
