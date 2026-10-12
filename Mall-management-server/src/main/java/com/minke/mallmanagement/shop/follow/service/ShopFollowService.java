package com.minke.mallmanagement.shop.follow.service;

import com.minke.mallmanagement.shop.follow.dao.ShopFollowDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 店铺域「关注关系」的服务层（C3 块 5 · B 组）。**业务规则 + 响应组装**；
 * 路由与入参绑定在 {@code ShopFollowController}，数据访问全在 {@link ShopFollowDao}。
 *
 * <h2>【关注必须幂等 —— 撞唯一键不是异常，是「已经关过了」】</h2>
 * {@code shop_follow} 的唯一键是 {@code (CustomerId, MerchantId)} ⇒ 重复关注会抛
 * {@code DuplicateKeyException}。**这不是失败路径**：用户看到的「关注」按钮本来就是**幂等**语义
 * （双击 / 网络重发 / 两个标签页各点一次，都该得到同一个结果）。故本类 catch 它并回
 * {@code success:true} —— 与 {@code CartService.add} 撞键后改走「合并数量」**同一种取舍**，
 * 只是这里的「合并」就是空操作。
 *
 * <h2>【三个响应的形状】**逐字**如下，前端 {@code api/shop.js} 只读这些键：</h2>
 * <pre>
 *   follow      → { success, msg, following: true,  followers: &lt;该店粉丝数&gt; }
 *   unfollow    → { success, msg, following: false, followers: &lt;该店粉丝数&gt; }
 *   followState → { success, following: &lt;bool&gt;, followers: &lt;该店粉丝数&gt; }
 * </pre>
 * {@code followers} 三个端点**都回**：{@code ShopHome} 的头卡一次请求就要把「关注状态 + 粉丝数」
 * 一起画完，让前端为「粉丝数」多发一次请求是没必要的往返。⚠️ 它是**回读**（写后重查），
 * 不是「原值 ±1」—— 两个人同时关注时，算术增减会各自丢一笔。
 *
 * <h2>【边界 1：不能关注自己（用户 2026-10-12 裁定）】</h2>
 * {@code CustomerId} 与 {@code MerchantId} **字面相等** ⇒ {@code follow} 回
 * {@code success:false + "不能关注自己"}，防刷粉丝数。判据与理由见 {@link #isSelf}；
 * ⚠️ 这道闸**只在 {@code follow}** 上，{@code unfollow} / {@code followState} 刻意不加
 * （那两个的理由写在同一处）。前端另有一半：**自己的店不显示「关注」按钮**（commit C 的
 * {@code ShopHome.vue}），但**粉丝数照显** ⇒ 头卡仍会调 {@code followState}。
 *
 * <h2>【写路径挂钩：两个都不挂，理由写在明面上】</h2>
 * <ul>
 *   <li><b>不挂 {@code AuditService}</b> —— 关注 / 取关是 C 端轻量关系写，与
 *       {@code cart.*} / {@code message.friend*} 同族（那两个也没挂审计）。
 *       {@code admin_audit} 记的是**管理端对业务实体的介入**，不是用户对自己关系表的一次点击。</li>
 *   <li><b>不挂 {@code NotificationService}</b> —— 用户 2026-10-12 裁定 7：「关注后的通知**本块不做**」
 *       ⇒ 已登记待办（{@code tech-debt.md}），不是遗漏。</li>
 * </ul>
 */
@Service
public class ShopFollowService {

    private final ShopFollowDao shopFollowDao;

    public ShopFollowService(ShopFollowDao shopFollowDao) {
        this.shopFollowDao = shopFollowDao;
    }

    /**
     * 关注（**幂等**：已关注 ⇒ 仍是 {@code success:true}，表里仍 1 行）。
     *
     * <p>⚠️ <b>边界 1（用户 2026-10-12 裁定）：不能关注自己</b> —— 见 {@link #isSelf}。
     * 这是本方法**独有**的一道闸，{@link #unfollow} / {@link #followState} **刻意不加**（理由见 {@link #isSelf}）。
     */
    public Map<String, Object> follow(String customerId, String merchantId) {
        Map<String, Object> bad = paramRejection(customerId, merchantId);
        if (bad != null) {
            return bad;
        }
        if (isSelf(customerId, merchantId)) {
            return fail("不能关注自己");
        }
        try {
            shopFollowDao.insert(customerId, merchantId);
        } catch (DuplicateKeyException e) {
            // 已经关注过 —— 唯一键就是为此设的，**这不是异常路径**（见类注释）。空操作继续。
        } catch (Exception e) {
            e.printStackTrace();
            return fail("关注失败：" + e.getMessage());
        }
        return stateResult(customerId, merchantId, "已关注");
    }

    /** 取关（**物理 DELETE**，语义见 {@code ShopFollowDao} 类注释）。本来就没关注 ⇒ 也回成功。 */
    public Map<String, Object> unfollow(String customerId, String merchantId) {
        Map<String, Object> bad = paramRejection(customerId, merchantId);
        if (bad != null) {
            return bad;
        }
        try {
            shopFollowDao.delete(customerId, merchantId);
        } catch (Exception e) {
            e.printStackTrace();
            return fail("取关失败：" + e.getMessage());
        }
        return stateResult(customerId, merchantId, "已取消关注");
    }

    /** 关注状态 + 粉丝数（{@code ShopHome} 头卡的一次请求）。 */
    public Map<String, Object> followState(String customerId, String merchantId) {
        Map<String, Object> bad = paramRejection(customerId, merchantId);
        if (bad != null) {
            return bad;
        }
        try {
            return stateResult(customerId, merchantId, "ok");
        } catch (Exception e) {
            e.printStackTrace();
            return fail("查询关注状态失败：" + e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /**
     * 写 / 读之后**回读**真实状态组装响应 —— 见类注释「{@code followers} 是回读不是原值 ±1」。
     *
     * <p>⚠️ {@code following} 这里**再查一次库**（而不是直接把入参写死成 {@code true} / {@code false}）：
     * 取关一条本来就不存在的关注，写侧删了 0 行，但「现在没关注」依然是**真**的 ⇒ 回读给出的
     * 是同一个答案，却不会因为写侧走了哪条分支而与库里的真相脱节。
     */
    private Map<String, Object> stateResult(String customerId, String merchantId, String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", msg);
        result.put("following", shopFollowDao.exists(customerId, merchantId));
        result.put("followers", shopFollowDao.countFollowers(merchantId));
        return result;
    }

    /** 两个入参都必须非空 —— 缺一个就回 200 + {@code success:false}（不逃 400，理由见 Controller 类注释）。 */
    private static Map<String, Object> paramRejection(String customerId, String merchantId) {
        if (isBlank(customerId) || isBlank(merchantId)) {
            return fail("缺少参数：CustomerId / MerchantId");
        }
        return null;
    }

    /**
     * 边界 1（用户 2026-10-12 裁定）：**自己不能关注自己**，防刷粉丝数（业务规则）。
     *
     * <h2>【判据 = 两个业务号字面相等】</h2>
     * 商家入驻是 {@code UserGovernanceDao.promoteToMerchant} —— 它**刻意保留 {@code customerid}**
     * （老客户号不回收）⇒ 一个商家账号**同时**持有 {@code customerid} 与 {@code merchantid}。
     * 于是「商家逛自己的店」这一路上，前端拿哪一个当 {@code CustomerId} 决定了这里量的是什么：
     * 拿 {@code merchantid} ⇒ 两个入参**字面相等**，本闸命中（这正是本闸要挡的形态）。
     *
     * <h2>【为什么用 {@code equalsIgnoreCase}】</h2>
     * 表与库的排序规则是 {@code utf8mb4_0900_ai_ci}（**大小写不敏感**）⇒ 库里
     * {@code WHERE CustomerId = MerchantId} 这个判据本来就是 CI 的。本闸与库同判据，
     * 免得「Java 说不是自己、SQL 说是自己」两条口径分叉。
     *
     * <h2>【⚠️ 为什么只有 {@code follow} 加，另两个端点不加】</h2>
     * <ul>
     *   <li>{@code unfollow}：自己关注自己的行**本来就建不出来**（本闸挡在写侧）⇒ 给它传自己
     *       只会删 0 行，是个**无害空操作**；加一道闸只会让「取关」多一种失败形状。</li>
     *   <li>{@code followState}：**必须留着**。{@code ShopHome} 的头卡**无论是不是自己的店**
     *       都要显示**粉丝数**（别人关注自己的店，自己更要看得到）⇒ 这里判 403 会把
     *       「自己的店铺主页」整页的粉丝数弄没。前端那边是**隐藏关注按钮**，不是不请求。</li>
     * </ul>
     */
    private static boolean isSelf(String customerId, String merchantId) {
        return customerId.equalsIgnoreCase(merchantId);
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
