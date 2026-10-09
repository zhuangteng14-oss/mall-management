package com.minke.mallmanagement.shared.web;

/**
 * 用户**状态**只读探针（C3 块 2 用户块新增）—— 把「这个人被封禁了吗 / 这个商家被冻结了吗」
 * 从 {@code user} 域借给**两个不属该域**的调用方，且**不带任何写能力**。
 *
 * <h2>【为什么是一个接口，而不是让调用方直接吃 {@code UserDao}】</h2>
 * 两个调用方分居两处、各自有各自的切片约束：
 * <ul>
 *   <li>{@link AuthInterceptor}（封禁第二道闸）—— 它是**不是** {@code @Component}（由
 *       {@link WebAuthConfig} 构造），且 {@code WebAuthConfig} 是 {@code @Configuration
 *       implements WebMvcConfigurer} ⇒ <b>{@code @WebMvcTest} 切片会扫到它</b>。
 *       若闸直接依赖一个 DAO bean，切片里没有那个 bean ⇒ <b>所有切片启动失败</b>
 *       （C3 块 1 实测过同款：237 测试 / 77 errors）。故闸侧只收
 *       {@code ObjectProvider<UserStateLookup>} —— 切片里取不到 ⇒ 空转。</li>
 *   <li>{@code OrderService}（商家冻结 ⇒ 拒单）—— 它是被 {@code AbstractSmokeTest} 的
 *       {@code @Import} 拉进切片的**真 bean**。同一个理由：依赖一个不存在的 bean 会把切片打红；
 *       {@code ObjectProvider} 则天然容忍缺席。</li>
 * </ul>
 * ⇒ 收成一个接口后，「谁提供实现」只剩一处（{@code user} 域的 {@code @Service}），
 * 「切片里没有它」也只剩一处现象（{@code ObjectProvider.getIfAvailable() == null}）。
 *
 * <h2>【⚠️ 三条「查不到」的语义必须一致：一律 fail-open（当作「没被封 / 没被冻」）】</h2>
 * 封禁与冻结都**不是安全边界**（真正的边界是认证与授权，见 {@link IdentityGuard} 类注释里
 * 「解析不出身份 = 放行」的同类论证）。故实现侧在**任何读失败**（表/列不存在、连接抖动、
 * 值为 NULL）时都返回 {@code false} 并记一条 WARN —— 让一个查不通的库把**所有**登录与下单
 * 打成失败，是把小病治成大病。代价（**必须写下来**）：封禁/冻结在库不可达时**暂时失效**。
 */
public interface UserStateLookup {

    /**
     * 这个**业务号**对应的账号是否被封禁。
     *
     * @param userId 业务号（{@code user.customerid} / {@code user.merchantid}），
     *               与会话里存的那个值是同一个（见 {@link AuthSession}）
     * @param role   用来选列：{@code 2}（商家）⇒ 查 {@code merchantid}；其余（含 {@code null}）
     *               ⇒ 查 {@code customerid}。⚠️ 这一跳与 {@code UserService.businessId} 的选列
     *               判据**同源**，别在这里另立一套
     */
    boolean isBanned(String userId, Integer role);

    /** 按**登录名**（{@code user} 表主键）判是否被封禁 —— {@code loginSelect} 那一跳的入参就是它。 */
    boolean isBannedByUsername(String username);

    /**
     * 这个商家的降级是不是已进入**冻结态**（{@code DowngradeState='frozen'}）——
     * 冻结期的「不接新单」判据。
     *
     * <p>⚠️ 只认 {@code frozen}。{@code scheduled}（通知期，还没到执行点）**不拦单** ——
     * 那一阶段商家仍可正常经营、只是「即将被降级」（见 {@code MerchantDowngradeService} 五阶段）。
     */
    boolean isMerchantFrozen(String merchantId);
}
