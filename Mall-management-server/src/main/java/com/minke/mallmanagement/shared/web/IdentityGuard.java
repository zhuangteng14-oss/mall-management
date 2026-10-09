package com.minke.mallmanagement.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

/**
 * 入参身份校验（多账户隔离笔，2026-10-06）—— <b>「请求里带的业务号必须是调用者自己的」</b>
 * 这条判据的唯一落点，13 个端点共用（照见 {@code progress.md §5-AF} 的清单）。
 *
 * <h2>【它治的是哪一半病】</h2>
 * token 机制治的是「<b>显示</b>串号」（页面按本标签页的身份取数）；本类治的是
 * 「<b>干活</b>串号」—— 服务端此前**一律信入参**：{@code /goods/goodsUp} 的 {@code MerchantId}
 * 填谁就改谁的商品（{@code GoodsController.java:207}；C3 批 A 重指 —— 原记的那个区间实测偏到 goodsDe / productCodePrice，不是 goodsUp），与调用者是谁毫无关系。
 * ⇒ 拿着自己的合法 token，改别人的商品是**一行 curl** 的事。缺了这一半，
 * 「多账户隔离」只是前端的观感，不是服务端的边界。
 *
 * <h2>【三档判据（本笔的验收口径）】</h2>
 * <ol>
 *   <li>入参 == 自己的业务号 ⇒ 放行（**所有角色**）；</li>
 *   <li>入参 == 别人的业务号，且调用者是管理员（{@link AuthSession#ROLE_ADMIN}）⇒ 放行
 *       —— 管理员改别人的东西是**职责**（驳回商家异常更新、处理异常订单、处理不合法描述），
 *       不是越权。⚠️ 细粒度管理员权限（能改哪些字段 / 审计 / 二次确认）**归 C3 笔**，本笔只开这扇门；</li>
 *   <li>入参 == 别人的业务号，且调用者**不是**管理员 ⇒ <b>403</b>（{@link #forbidden()}）。
 *       ⚠️ 是 403 而不是 401：调用者**有**身份、只是无权 —— 401 会让前端把它当成
 *       「登录态失效」进而清态跳登录（见 {@code api/http.js} 的 401 出口）。</li>
 * </ol>
 *
 * <h2>【为什么用**识别**口径（{@link AuthSession#identify}）而不是授权口径】</h2>
 * 两者在「无凭据」时都答 {@code null}，差别只在「cookie 是管理会话」这一种情况：识别口径会
 * 认出身份（于是走上面第 2 档豁免），授权口径答 {@code null}（于是当匿名处理）。
 * 本类取**识别**口径，好处是判据**自足** —— 不依赖「闸已经把 role=3 的 cookie 挡在外面」
 * 这个外部前提。（那一层确实挡着：{@link AuthInterceptor} 对业务端点答 401 ⇒ 本类在这种
 * 请求上根本不会被调用。）
 *
 * <h2>【⚠️ 为什么「解析不出身份」= 放行 —— 这条最容易被误读成漏洞】</h2>
 * {@code identify() == null} 只可能是两种情形，**都不是本类该拦的**：
 * <ol>
 *   <li><b>闸关着</b>（{@code app.auth.enabled=false}）—— smoke 切片（10 个测试类）与 A3
 *       （{@code application-a3.properties} 钉 false）都在这一档。它们打这些端点时**没有会话**，
 *       若这里判 403，本笔会当场弄红 27+ 条既有用例与整个 A3。本类对既有行为**零影响**
 *       这条属性，正是靠这一行守住的（与闸「关着就全放行」同一条纪律）。</li>
 *   <li><b>闸开着时的白名单面</b> —— 本类服务的 13 个端点**都不在白名单里**
 *       （{@code WebAuthConfig.AUTH_WHITELIST}），故这一支在闸开着时不可达。</li>
 * </ol>
 * ⚠️ 反过来说：本类**不是**认证。它是「认证通过之后，入参不许指向别人」。
 * 谁把这一行改成「null ⇒ 403」，请连带更新 smoke / A3 的全部断言与 {@code progress.md §5-AF}
 * 的验收口径 —— 那不是修 bug，是换了一套契约。
 */
public final class IdentityGuard {

    private IdentityGuard() {
    }

    /**
     * 放行判据。{@code true} = 放行（自己 / 管理员 / 解析不出身份）；{@code false} = 该答 403。
     *
     * <p>⚠️ <b>必须在方法体**第一行**调用</b>：它要挡在一切业务动作（写库、落盘、清缓存）之前。
     * 放在方法体中段等于先把活儿干了再拒 —— 那比不判更坏。
     *
     * @param paramId 该端点里**代表调用者自己**的那个入参（{@code MerchantId} / {@code customerId} …）。
     *                ⚠️ 不是每个业务号入参都属此列：对端方（订单的商家、好友的 {@code friendId}、
     *                商品浏览面的商户）**不能**传进来 —— 判它会误伤正常业务。
     */
    public static boolean requireSelfOrAdmin(HttpServletRequest request, String paramId) {
        AuthSession.Identity identity = AuthSession.identify(request);
        if (identity == null) {
            return true;   // 见类注释「为什么解析不出身份 = 放行」
        }
        if (paramId == null || paramId.equals(identity.userId())) {
            return true;   // 第 1 档
        }
        return identity.role() != null && identity.role() == AuthSession.ROLE_ADMIN;   // 第 2 / 3 档
    }

    /**
     * 403 响应（被上面的判据拒掉时返回它）。
     *
     * <p>⚠️ 响应体走 {@link Result#fail(String)}（**不承诺字节序**，前端按 {@code data.msg} 取）：
     * 403 是本笔新增的状态码，没有历史字节契约要守 —— 与 401 那条「两处逐字节相同」是**相反的**
     * 处境（那里是既有契约，故写了字面量常量）。
     *
     * <p>为什么不用异常通道（{@code GlobalExceptionHandler}）：它的 {@code assignableTypes} 只覆盖
     * {@code MessageController} / {@code GoodsController} 两个类，而本类服务的 13 个端点横跨
     * goods / order / message 三域 ⇒ 抛异常会在 order 域退化成默认错误页（形状不一致）。
     * 故走 ResponseEntity 显式构造 —— 与 {@code /user/me} 的 401 同一手法。
     */
    public static ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(HttpServletResponse.SC_FORBIDDEN).body(Result.fail("无权操作该账户"));
    }
}
