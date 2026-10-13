package com.minke.mallmanagement.smoke;

import com.minke.mallmanagement.shared.lock.DistributedLockService;
import com.minke.mallmanagement.admin.dao.AdminAuditDao;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.admin.service.NotificationBroadcastService;
import com.minke.mallmanagement.message.dao.MessageDao;
import com.minke.mallmanagement.notification.dao.NotificationDao;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.message.service.FriendCacheService;
import com.minke.mallmanagement.message.service.MessageService;
import com.minke.mallmanagement.catalog.dao.CategoryDao;
import com.minke.mallmanagement.catalog.service.CategoryService;
import com.minke.mallmanagement.catalog.service.GoodsViolationService;
import com.minke.mallmanagement.goods.dao.GoodsAuditDao;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.goods.service.GoodsAuditService;
import com.minke.mallmanagement.goods.service.GoodsCacheService;
import com.minke.mallmanagement.goods.service.GoodsService;
import com.minke.mallmanagement.goods.service.GoodsTypeCacheService;
import com.minke.mallmanagement.template.dao.TemplateDao;
import com.minke.mallmanagement.template.service.TemplateService;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.service.OrderService;
import com.minke.mallmanagement.order.model.OrderStateMachine;
import com.minke.mallmanagement.order.model.OrderTransitionRecorder;
import com.minke.mallmanagement.user.dao.UserDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserCacheService;
import com.minke.mallmanagement.user.service.UserService;
import com.minke.mallmanagement.admin.service.UserGovernanceService;
import com.minke.mallmanagement.merchant.dao.MerchantApplicationDao;
import com.minke.mallmanagement.merchant.service.MerchantApplicationService;
import com.minke.mallmanagement.merchant.service.MerchantDowngradeService;
import com.minke.mallmanagement.goods.dao.GoodsShelfDao;
import com.minke.mallmanagement.cart.dao.CartDao;
import com.minke.mallmanagement.cart.service.CartService;
import com.minke.mallmanagement.order.dao.ShippingTrackDao;
import com.minke.mallmanagement.admin.dao.AdminOrderDao;
import com.minke.mallmanagement.admin.service.AdminOrderService;
import com.minke.mallmanagement.stats.dao.StatsDao;
import com.minke.mallmanagement.stats.service.StatsService;
import com.minke.mallmanagement.marketing.coupon.dao.CouponDao;
import com.minke.mallmanagement.marketing.coupon.service.CouponService;
import com.minke.mallmanagement.shop.follow.dao.ShopFollowDao;
import com.minke.mallmanagement.shop.follow.service.ShopFollowService;
import com.minke.mallmanagement.shop.profile.dao.ShopProfileDao;
import com.minke.mallmanagement.shop.profile.service.ShopProfileService;
import com.minke.mallmanagement.marketing.discount.service.DiscountLimitService;
import com.minke.mallmanagement.marketing.subsidy.dao.SubsidyDao;
import com.minke.mallmanagement.marketing.subsidy.service.SubsidyService;
import com.minke.mallmanagement.marketing.seckill.dao.SeckillDao;
import com.minke.mallmanagement.marketing.seckill.service.SeckillService;
import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.comment.dao.ReportDao;
import com.minke.mallmanagement.comment.dao.SensitiveWordDao;
import com.minke.mallmanagement.comment.service.CommentService;
import com.minke.mallmanagement.comment.service.ReportService;
import com.minke.mallmanagement.comment.service.SensitiveWordService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * smoke test 基类。
 *
 * <p>采用 {@code @WebMvcTest} 切片：只加载 Web 层，不加载 DataSource / JPA，
 * 因此可在无 MySQL 的环境中运行（CI 要求）。JdbcTemplate 由各子类用
 * {@code @MockitoBean} 替换。
 *
 * <p><b>鉴权口子</b>：全项目当前零鉴权（见 {@code docs/agent/business-decisions.md} BD-03）。
 * 将来接入认证时，只需在此基类补全 {@link #authHeaders()} 的实现，
 * 5 个 smoke test 无需改动。
 */
@WebMvcTest
@ActiveProfiles("smoke")
// B3：把**真的** OrderStateMachine 拉进 Web 切片。它没有依赖、只是一张纯函数转移表，
// 且它生成的 UPDATE 语句正是 golden copy 钉住的那条 —— 用真 bean，smoke 用例就顺带验证了
// 「生成的 SQL 能穿过 JdbcTemplate 调用而不炸」；换成替身则 updateSql 返回 null，
// 两个用例会一起静默走错分支。
//
// 三层第一笔（订单域）补 OrderService / OrderDao：
// ⚠️ 这两个**必须用 @Import 的真 bean，不能用 @MockitoBean 替身** —— 替身会让
// OrderPayIfUpSmokeTest 的断言退化成「Controller 会转发」这个空命题（替身返回 null ⇒
// 响应体是空的 ⇒ 恰恰是那个测试要防的东西）。用真 bean 之后：
//   · OrderService 的依赖在切片里全部有着落 —— OrderDao（本行）、DistributedLockService
//     （替身，且被 stubLockAsDirectRun 改成「直接执行 action」）、OrderStateMachine（真 bean）、
//     OrderTransitionRecorder（替身，方法全 void ⇒ 默认即空转）；
//   · OrderDao 的依赖 JdbcTemplate 就是下面那个 @MockitoBean ⇒ **它落回既有的替身**，
//     SQL 仍不出切片。
// 但这带来一处**必须同时改的配套**：OrderDao 走 JdbcClient，而 JdbcClient 的 update 落到
// JdbcTemplate.update(PreparedStatementCreator) 这个**单参重载**上（不是
// update(String, Object...)）—— 所以 OrderPayIfUpSmokeTest 的 stub 匹配器要跟着换，
// 否则它会静默返回 0、走错分支而**仍然是绿的形状**。见该测试里的 ⚠️ 注。
//
// 三层第二笔（商品域）补 GoodsService / GoodsDao，理由与订单域完全相同：GoodsController
// 现在只认 GoodsService，而 @Service 不在 Web 切片内 —— 用替身会让
// GoodsAddUploadFailSmokeTest 的「写库之前就返回」退化成一个空命题（替身不落盘也不查库，
// 它当然不会被调用）。用真 bean 之后：GoodsService 的依赖全部有着落 —— GoodsDao（本行）、
// GoodsCacheService（替身，其 evict 在切片里是空转）、@Value app.upload-dir（由
// application-smoke.properties / 子类的 @TestPropertySource 提供）；
// GoodsDao 的 JdbcTemplate 就是下面那个 @MockitoBean ⇒ SQL 不出切片。
// ⚠️ 配套（与订单域那次同一处坑，但方向不同）：GoodsAddUploadFailSmokeTest 里那条
//    verify(jdbcTemplate, never()).update(String, Object...) 在本笔之后会**变成空话**
//    —— JdbcClient 不再走那个重载。见该测试里已按 PreparedStatementCreator 改写的那两行。
// 三层第三笔（user 域）补 UserService / UserDao，理由同上：UserController 现在只认 UserService。
// 用真 bean 之后：UserService 的依赖全部有着落 —— UserDao（本行）、UserCacheService（替身）、
// @Value app.upload-dir（有默认值）；UserDao 的 JdbcTemplate 就是下面那个 @MockitoBean ⇒ SQL 不出切片。
// ⚠️ 配套（本笔真正踩到的那个坑）：user 域走 JdbcClient 的是**读**侧 ——
//    JdbcClient 的 query(Class).single() 落到 JdbcTemplate.query(String, RowMapper, Object...)，
//    **不是** queryForObject ⇒ LoginSelectSmokeTest 的旧 stub 不再命中。后果是「静默但可见」的：
//    桩不中 ⇒ 返回 null ⇒ controller 的 role != null ? role : 0 返回 0 ⇒
//    content().string("2") 变红。见该测试里已按 RowMapper 改写的那行。
// ⚠️ 另记一条**切片与完整上下文的分歧**（不是缺陷，是切片的固有性质）：UserService.loginAdd 上的
//    @Transactional 在本切片里是 **no-op** —— @WebMvcTest 不加载 DataSource ⇒ 没有
//    TransactionManager ⇒ TransactionAutoConfiguration 的 @EnableTransactionManagement 不生效
//    ⇒ 既不建代理、也不开事务。所以「注册的三条 INSERT 是否真的同事务」**本切片测不了**，
//    那是 MallManagementApplicationTests（@SpringBootTest，A3_ENABLED=true）与 A3 的 A-B2-1 的职责。
// 三层第四笔（message 域）补 MessageService / MessageDao，理由同上：MessageController 现在只认
// MessageService。用真 bean 之后：MessageService 的依赖全部有着落 —— MessageDao（本行）、
// UserCacheService（替身，publicInfo 默认返回 null ⇒ userSelect 走「用户不存在」分支）、
// FriendCacheService（替身，默认返回 null ⇒ 好友读端点走空体）、DistributedLockService（替身，
// 且被 stubLockAsDirectRun 改成「直接执行 action」⇒ friendAdd 的临界区在切片里照跑）；
// MessageDao 的 JdbcTemplate 就是下面那个 @MockitoBean ⇒ SQL 不出切片。
// ⚠️ 本笔**没有**配套的 stub 改写要补：5 个 smoke 子类都不调用 /message/* 的任何端点
//    （它们只打 orders / goods / user / 静态资源），故上面三个替身的默认行为足够。
//    ⇒ **代价**：本域的 10 个端点在 smoke 里**零覆盖**，与 A3 只打 friendAdd 一处叠加后，
//    见 MessageService 类注释「谁保护本笔」一节。
// C3 块 1（批 A 一 + I2 二，2026-10-09）补三个新 Controller 的依赖。
// ⚠️ 这个配套**是必须的、不是可选**：@WebMvcTest 无参会加载**全部** @Controller，新控制器一进来，
//    切片上下文就会因为「找不到它要的 @Service」而**启动失败**（2026-10-09 实测：237 测试 / **77 errors**，
//    全部落在本包 —— 首个报错是 `AdminAuditController required a bean of type 'AuditService'`）。
//    三处依赖：`AdminAuditController` → `AuditService`（+ `AdminAuditDao`）；
//    `NotificationController` → `NotificationService`（+ `NotificationDao`）；
//    `AdminNotificationController` → `NotificationBroadcastService`。
//    ⇒ 照本文件既有范式一并 @Import 真 bean（不用 @MockitoBean：它们无状态、不参与任何断言，
//    真 bean 与替身等价，且真 bean 顺带证明「能构造起来」）。两个新 DAO 只吃 JdbcTemplate ⇒
//    落回下面那个 @MockitoBean，**SQL 仍不出切片**。
// ⚠️ 上文「全部 4 个 Controller」是三层四笔时的值，**现已不准确**（批 A 起 5 个、I2 起 7 个）——
//    本次只补 @Import 与这一节，未逐句回改旧注释。
// C3 块 2（用户块，2026-10-09）：新增两个 Controller（`MerchantApplicationController`、
// `AdminAccountsController`）与一条 `/user/updateProfile`，⇒ 第 8、9 个 Controller。
//    又一次「同一处地雷」：不 @Import 就是**整个切片上下文起不来**（同块 1 那次 237/77）。
//    六处依赖：两个 Controller → `UserGovernanceService` / `MerchantApplicationService` /
//    `MerchantDowngradeService`（`NotificationService` / `AuditService` 块 1 已在）；
//    两个新 Service → 三个新 DAO（`UserGovernanceDao` / `MerchantApplicationDao` / `GoodsShelfDao`）。
//    ⇒ 三个 DAO 只吃 JdbcTemplate ⇒ 落回下面那个 @MockitoBean，**SQL 仍不出切片**。
// ⚠️ **刻意不 @Import `UserStateLookupService`**：三处跨域探针（封禁闸 ×2、冻结闸 ×1）都用
//    `ObjectProvider<UserStateLookup>` 取它，切片里取不到 ⇒ 那三道闸在 smoke 里**静默不生效**。
//    这是设计（切片不该因为「多了一张表要查」而全红），**不是**漏配 —— 所以别为了「补齐」把它加进来。
// ⚠️ 另记一条同类的切片性质：`MerchantApplicationService` 上的 `@Transactional` 在本切片里
//    同样**是 no-op**（@WebMvcTest 无 DataSource ⇒ 无 TransactionManager），
//    「批准与改身份是否真的同事务」本切片测不了 —— 那是块 2 主链路验收与 A3 的职责。
// C3 块 3（商品块，2026-10-10）：新增两个 Controller（`CatalogController`、`AdminCatalogController`）
//    + `GoodsController` 新增依赖，⇒ 第 10、11 个 Controller。**第四次同一处地雷**。
//    六处依赖：`CatalogController` → `GoodsTemplateService`；`AdminCatalogController` →
//    `GoodsAuditService` / `CategoryService` / `GoodsTemplateService` / `GoodsViolationService`
//    （`NotificationService` / `AuditService` 块 1 已在）；`GoodsController` / `GoodsService`
//    （块 3 起 `goodsAdd` 改提审制）→ `GoodsAuditService`。
//    Service 再各吃一个 DAO：`GoodsAuditDao` / `CategoryDao` / `GoodsTemplateDao`；
//    `GoodsViolationService` 只吃 `GoodsDao`（块 1 已在）+ `GoodsCacheService`（下面替身）。
//    ⇒ 三个新 DAO 只吃 JdbcTemplate ⇒ 落回下面那个 @MockitoBean，**SQL 仍不出切片**。
// ⚠️ 与块 2 同接口同性质的一条：`GoodsAuditService.audit` 上的 `@Transactional` 在本切片里
//    也是 no-op ⇒「审核通过与写 goods 行是否真的同事务」本切片测不了 —— 归
//    `GoodsAuditTxAtomicityTest`（真库）与 A3 的职责。
@Import({OrderStateMachine.class, OrderService.class, OrderDao.class,
        GoodsService.class, GoodsDao.class,
        UserService.class, UserDao.class,
        MessageService.class, MessageDao.class,
        AuditService.class, AdminAuditDao.class,
        NotificationService.class, NotificationDao.class,
        NotificationBroadcastService.class,
        // C3 块 2（用户块，2026-10-09）：
        UserGovernanceDao.class, UserGovernanceService.class,
        MerchantApplicationDao.class, MerchantApplicationService.class,
        GoodsShelfDao.class, MerchantDowngradeService.class,
        // C3 块 3（商品块，2026-10-10）：
        GoodsAuditDao.class, GoodsAuditService.class,
        CategoryDao.class, CategoryService.class,
        GoodsViolationService.class,
        // C3 块 3 就地改造（模板通用化，2026-10-10）：新增 template 域的两个 bean；
        // 同批删掉了 GoodsTemplateDao / GoodsTemplateService（那两个类已不存在，留着会编译不过）。
        // ⚠️ 又是一个新 Controller（TemplateController / AdminTemplateController）——
        //    「新 @Service 不进 @Import ⇒ 整个切片上下文起不来」，同前几笔。
        TemplateDao.class, TemplateService.class,
        // C3 块 4（订单块，2026-10-10）：cart 新域（A1 甲）+ 管理端订单治理。
        // ⚠️ 三处新 @Service/@Dao，两个新 Controller（CartController / AdminOrderController）——
        //    「新 @Service 不进 @Import ⇒ 整个切片上下文起不来」，同前几笔。
        // ⚠️ CartService 还吃 OrderService（结算复用它建单）、AdminOrderService 还吃
        //    NotificationService（介入通知双方）—— 两个依赖都已在上面的清单里，无需重复登记。
        CartDao.class, CartService.class,
        AdminOrderDao.class, AdminOrderService.class,
        // C3 块 4 补丁（订单块，2026-10-10）：OrderService 新增依赖 ShippingTrackDao（付款/发货/收货
        // 三条边各写轨迹）⇒ 不 @Import 它，**整个切片上下文起不来**（第五次同一处地雷）。
        // ⚠️ **刻意不 @Import ShippingTrackAdvanceJob**：它是 @Component（切片不扫），且是
        //    @Scheduled —— 切片里没有调度器（@EnableScheduling 挂在 MerchantDowngradeSchedulingConfig、
        //    不属 web 组件），拉进来只会多一个永不执行的 bean。它落回下面那个 @MockitoBean 之外的
        //    真实缺口由「默认 profile 手验」兜（见该类的类注释）。
        ShippingTrackDao.class,
        // 可视化段 commit 2（经营数据看板，2026-10-11）：新增两个 Controller（`AdminStatsController` /
        // `MerchantStatsController`）+ 一个 @Service（`StatsService`）——
        // **第六次同一处地雷**：@WebMvcTest 无参会加载全部 @Controller，新控制器一进来，
        // 切片上下文就会因为「找不到它要的 StatsService」而**启动失败**（同前五笔，
        // 2026-10-09 那次是 237 测试 / 77 errors）。⇒ 照既有范式 @Import 真 bean。
        // ⚠️ **刻意不 @Import `StatsAggregateJob`**：它是 @Component（切片不扫）且是 @Scheduled ——
        //    理由与上一笔的 `ShippingTrackAdvanceJob` **逐字相同**（切片里没有调度器；
        //    @EnableScheduling 挂在 MerchantDowngradeSchedulingConfig，不属 web 组件）。
        //    ⇒ 「默认 profile 手验」兜它（job 是滚动窗口，演示数据落窗外 ⇒ 手验要看数据请用 rebuild）。
        StatsDao.class, StatsService.class,
        // C3 块 5（营销块 · commit 1 券，2026-10-11）：新增两个 Controller（`CouponController` /
        // `AdminCouponController`）+ 一个 @Service（`CouponService`）+ 一个 @Dao（`CouponDao`）——
        // **第七次同一处地雷**：@WebMvcTest 无参会加载全部 @Controller，新控制器一进来，
        // 切片上下文就会因为「找不到它要的 CouponService」而**启动失败**（同前六笔）。
        // ⚠️ `AdminCouponController` 还吃 `AuditService`、`CouponService` 只吃 `CouponDao`
        //    —— 两个依赖都在上面的清单里，无需重复登记。
        // ⚠️ **刻意不 @Import `CouponExpiryJob`**：它是 @Component（切片不扫）且是 @Scheduled ——
        //    理由与 `ShippingTrackAdvanceJob` / `StatsAggregateJob` **逐字相同**（切片里没有调度器；
        //    @EnableScheduling 挂在 MerchantDowngradeSchedulingConfig，不属 web 组件）。
        CouponDao.class, CouponService.class,
        // C3 块 5 · B 组（个人中心 / 店铺主页 / 关注关系，2026-10-12）：新增一个 Controller
        // （`ShopFollowController`）+ 一个 @Service（`ShopFollowService`）+ 一个 @Dao（`ShopFollowDao`）——
        // **第八次同一处地雷**：@WebMvcTest 无参会加载全部 @Controller，新控制器一进来，
        // 切片上下文就会因为「找不到它要的 ShopFollowService」而**启动失败**（同前七笔）。
        // ⚠️ 本域的依赖链只有三层（Controller → Service → Dao），Service 不另吃别的 bean。
        ShopFollowDao.class, ShopFollowService.class,
        // C3 块 5 · B 组续笔（店铺公开档案，2026-10-12）：新增一个 Controller（`ShopProfileController`）
        // + 一个 @Service（`ShopProfileService`）+ 一个 @Dao（`ShopProfileDao`）——
        // **第九次同一处地雷**：@WebMvcTest 无参会加载全部 @Controller，新控制器一进来，
        // 切片上下文就会因为「找不到它要的 ShopProfileService」而**启动失败**（同前八笔）。
        // ⚠️ 依赖链只有三层（Controller → Service → Dao），Service 不另吃别的 bean。
        // ⚠️ 这个 Controller 走的是**公开面**（不在身份面），但「公开」与「要不要进 @Import」无关 ——
        //    切片加载所有 @Controller，与它挂不挂 IdentityGuard 无关。
        ShopProfileDao.class, ShopProfileService.class,
        // C3 块 5〈折扣〉（2026-10-12）：新增一个 @Service（`DiscountLimitService`）——
        // **第十次同一处地雷**，但这次形状不同：**没有新 Controller**（管理端的折扣配置直接复用
        // 现成的 `/admin/template/*`，本笔零新端点）。进这个清单的原因是 `GoodsService`
        // 的构造器多吃了它一个 bean（`GoodsService` 本身已被 @Import，见上）——
        // 它一缺席，整个切片上下文同样**启动失败**。
        // ⚠️ 依赖链只有一层：`DiscountLimitService` → `TemplateService`（上面已 @Import）。没有 DAO。
        DiscountLimitService.class,
        // C3 块 5〈国补〉（2026-10-12，commit 3）：**两个**新 Controller（`AdminSubsidyController`
        // + `SubsidyController`）+ 一个 @Service（`SubsidyService`）+ 一个 @Dao（`SubsidyDao`）——
        // **第十一次同一处地雷**（`@WebMvcTest` 无参会加载全部 @Controller，新控制器一进来，
        // 切片上下文就会因为「找不到 SubsidyService」而**启动失败**，同前十笔）。
        // ⚠️ 依赖链三层：Service → Dao；两个 Controller 另各吃一个**已在清单里**的 bean
        //    （`NotificationService` 块 1、`AuditService` 块 1）⇒ 这两条不必重复登记。
        SubsidyDao.class, SubsidyService.class,
        // C3 块 5〈秒杀〉（2026-10-12，commit 4）：**两个**新 Controller（`AdminSeckillController`
        // + `SeckillController`）+ 一个 @Service（`SeckillService`）+ 一个 @Dao（`SeckillDao`）——
        // **第十二次同一处地雷**（`@WebMvcTest` 无参会加载全部 @Controller，新控制器一进来，
        // 切片上下文就会因为「找不到 SeckillService」而**启动失败**，同前十一笔）。
        // ⚠️ 依赖链三层：Service → Dao；两个 Controller 另各吃一个**已在清单里**的 bean
        //    （`NotificationService` 块 1、`AuditService` 块 1）⇒ 这两条不必重复登记。
        SeckillDao.class, SeckillService.class,
        // C3 块 6（内容+消息块，2026-10-12 · commit 1 评论）：新增一个 Controller
        // （`CommentController`）+ 一个 @Service（`CommentService`）+ 一个 @Dao（`CommentDao`）——
        // **第十三次同一处地雷**（@WebMvcTest 无参会加载全部 @Controller，新控制器一进来，
        // 切片上下文就会因为「找不到 CommentService」而**启动失败**，同前十二笔）。
        // ⚠️ 依赖链三层：Service → Dao；Service 另吃一个**不是 bean** 的 `app.upload-dir`
        //    （@Value 注入的 String，照 GoodsService / UserService 的既有做法）⇒ 无需额外登记。
        // ⚠️ `CommentController` 另收 `ObjectProvider<UserStateLookup>` —— **刻意不 @Import**
        //    `UserStateLookupService`（理由见本文件它处那条 ⚠️，它是@WebMvcTest 切片的已知缺口）。
        CommentDao.class, CommentService.class,
        // C3 块 6 整改 · commit 4〈举报 + 图片审核 + 3 次警告封号〉（2026-10-13）：新增一个 Controller
        // （`ReportController`）+ 一个 @Service（`ReportService`）+ 一个 @Dao（`ReportDao`）——
        // **第十四次同一处地雷**（@WebMvcTest 无参会加载全部 @Controller，新控制器一进来，
        // 切片上下文就会因为「找不到 ReportService」而**启动失败**，同前十三笔）。
        // ⚠️ 依赖链三层：Service → Dao；`ReportService` 另吃 `CommentService`（上面已登记）、
        //    `GoodsViolationService`（块 3 已登记）、`UserGovernanceService`（块 2 已登记）、
        //    `NotificationService`（块 1 已登记）⇒ 这四个**无需重复登记**。
        // ⚠️ `ReportController` 另收 `ObjectProvider<UserStateLookup>`（同 `CommentController`）。
        // ⚠️ 本笔还给 `CommentService` / `CommentDao` 加了「图片审核」那一支（`/comment/imageReview`），
        //    **不新增 bean**（两个类都在上）⇒ 这里没有第二条要登记的。
        ReportDao.class, ReportService.class,
        // C3 块 6 整改 · commit 5〈敏感词〉（2026-10-13）：新增一个 Controller
        // （`SensitiveWordController`）+ 一个 @Service（`SensitiveWordService`）+ 一个 @Dao
        // （`SensitiveWordDao`）—— **第十五次同一处地雷**（@WebMvcTest 无参会加载全部 @Controller，
        // 新控制器一进来，切片上下文就会因为「找不到 SensitiveWordService」而**启动失败**，同前十四笔）。
        // ⚠️ 依赖链三层：Service → `SensitiveWordDao`（本行）+ `CommentDao`（上面已登记）；
        //    `CommentService` 本笔**反向**多依赖了一个 `SensitiveWordService`（发表前过敏感词闸）
        //    ⇒ 这一条是**双向登记**：漏了 `SensitiveWordService` 不只是新 Controller 起不来，
        //    **`CommentService` 自己也起不来**，会把 `CommentSmokeTest` 一起拖红。
        // ⚠️ 本笔还给 `CommentDao` 加了「扫老评论」那两个方法（`selectContentsContaining` /
        //    `updateContent`），**不新增 bean** ⇒ 这里没有第二条要登记的。
        SensitiveWordDao.class, SensitiveWordService.class,
        // C3 块 7 · commit 2〈5 套新事件环〉（2026-10-13）：**本笔零新 Controller、零新 @Service**
        // —— 地雷形状与〈折扣〉那一笔相同（没有新端点），但成因是「**既有** bean 的构造器多了一个形参」：
        // `OrderService`（上面已 @Import 的真 bean）本笔新增库存环 `StockMetrics`
        // （`observe/metrics/StockMetrics.java`，写入点见该类注释）—— 它一缺席，整个切片上下文
        // 同样在加载期**启动失败**（`NoSuchBeanDefinitionException: StockMetrics`，同前十五笔同一处地雷）。
        // ⚠️ **刻意不 @Import 其余四环**（`AuditMetrics` / `NotifyMetrics` / `SensitiveMetrics` /
        //    `IdentityMetrics`）：本笔没有任何 bean 吃它们（写入点 = 下一笔的攻击引擎）⇒ 照
        //    `MallCacheMetrics` / `MallOrderStateMetrics` 的既有处置 —— 切片只登记**被吃到**的埋点 bean。
        // ⚠️ `StockMetrics` 只吃 `MeterRegistry`—— 但 `@WebMvcTest` 切片**不加载** micrometer 的
        //    自动配置（2026-10-13 实测：`No qualifying bean of type 'io.micrometer.core.instrument.MeterRegistry'`，
        //    报错链正是 `adminOrderController → AdminOrderService → OrderService → StockMetrics`）
        //    ⇒ 这里补一个 `SimpleMeterRegistry`（micrometer 自带的内存实现：无外部依赖、无生命周期）
        //    喂它。**不给 `StockMetrics` 开「切片里可为空」的口子** —— 那会把埋点语义改掉，
        //    与「不得降级」相悖；补一个内存实现则连「计数真的在跑」都顺带留住了。
        // ⚠️ 既有三环（缓存 / 锁 / 订单）**不需要**这一行：它们从未进过本切片（清单里没有任何
        //    bean 吃它们）⇒ **本笔是第一次有切片内的 bean 依赖埋点出口**。
        StockMetrics.class, SimpleMeterRegistry.class})
public abstract class AbstractSmokeTest {

    @Autowired
    protected MockMvc mockMvc;

    /**
     * JdbcTemplate 替身。切片不包含 DataSource 自动配置，故必须提供一个 JdbcTemplate bean，
     * 否则上下文启动失败。放在基类，使不使用数据库的用例（如静态资源测试）也能共享同一套上下文。
     *
     * <p>⚠️ <b>它存在的理由在本笔（三层第四笔，message 域）发生了转移，值得写清楚</b>：
     * <ul>
     *   <li><b>三层前</b>：{@code @WebMvcTest} 无参会加载全部 4 个 Controller，其中若干
     *       {@code @Autowired JdbcTemplate}（写这句话时是三个）—— 替身是为了喂它们。</li>
     *   <li><b>三层第一~四笔之后</b>：4 个 Controller 里**已经没有**任何一个再
     *       {@code @Autowired JdbcTemplate} 了（订单域第一笔、商品域第二笔、user 域第三笔、
     *       message 域本笔依次把它从各自 Controller 里挪走，本笔挪走的是最后一个
     *       {@code MessageController.MessagejdbcTemplate}）。</li>
     *   <li><b>现在它为什么还必须存在</b>：{@code AbstractSmokeTest} 用 {@code @Import} 拉进了
     *       <b>真 bean</b> 的四个 DAO（{@code OrderDao} / {@code GoodsDao} / {@code UserDao} /
     *       {@code MessageDao}），而它们的构造器都收 {@code JdbcTemplate} ⇒ 替身现在是**喂 DAO 的**。
     *       ⚠️ 删掉它会当场在**上下文加载期**拖垮全部 smoke 用例
     *       （{@code NoSuchBeanDefinitionException: JdbcTemplate}），与它最初的作用无关。</li>
     * </ul>
     * <p>⚠️ 顺带更正一处**陈旧计数**：本注释原写「其中三个 {@code @Autowired JdbcTemplate}」，
     * 那个「三个」在订单/商品两笔之后就已经只剩一个（{@code MessageController}），本笔再清零。
     * 本句按 2026-10-02 实测改写。
     */
    @MockitoBean
    protected JdbcTemplate jdbcTemplate;

    /**
     * 同上一条的同一类问题：{@code @WebMvcTest} 无参加载全部 4 个 Controller，
     * 其中 {@code GoodsController} 还 {@code @Autowired} 了一个
     * 缓存 Service，而 {@code @Service} 不在 Web 切片内 ⇒ 切片里必须也给替身。
     *
     * <p>2026-09-28 补：goodstype 与 goodsSelectHome 上缓存时各引入一个 Service，
     * 漏掉它们会让 5 个 smoke 用例在**上下文加载期**就失败（报
     * {@code No qualifying bean of type ... GoodsTypeCacheService}），
     * 与 {@code JdbcTemplate} 那条是同一个成因、同一个修法。
     *
     * <p>2026-10-02（三层第二笔，商品域）后本替身**只被 {@code GoodsController} 一处注入**
     * —— 除 goodstype 外的 8 个端点已改由 {@code GoodsService} 转发（GoodsService 是
     * 上面 @Import 的真 bean）。
     *
     * <p>默认替身返回 null —— 本基类的 5 个子类都不调用走缓存的两个端点
     * （goodstype / goodsSelectHome），故无需 stub。
     */
    @MockitoBean
    protected GoodsTypeCacheService goodsTypeCacheService;

    /**
     * 同上一条的同一类问题，**但注入方在本笔换了人**：{@code GoodsController} 不再直接引它
     * —— 现在引它的是被 {@code @Import} 拉进切片的**真 bean** {@code GoodsService}
     * （三个写方法的唯一出口前要调 {@code evictByMerchant}）。
     *
     * <p>切片里它是替身 ⇒ evict 是空转；这一点必须显式知道：**smoke 不验证失效面**
     * （那在 {@code cache-layer.md} 与 A3 的 S1/S2 读侧基线里守）。
     *
     * <p>默认替身返回 null。{@code PriceTripleFailSmokeTest} 会按用例 stub
     * {@code priceByTriple}；其余子类不调走缓存的端点，故无需 stub。
     */
    @MockitoBean
    protected GoodsCacheService goodsCacheService;

    /**
     * 同上一条的同一类问题，第三次遇到：{@code MessageController}
     * （userSelect）也 {@code @Autowired} 了一个 {@code @Service}（UserCacheService）。
     *
     * <p>2026-09-28 补：userSelect 上缓存（+ 负缓存）时引入。漏掉它同样会在
     * <b>上下文加载期</b>就失败（{@code No qualifying bean of type ...
     * UserCacheService}），且失败的是**全部 5 个 smoke 用例**，而不是只失败
     * 与 userSelect 相关的那一个 —— 因为 {@code @WebMvcTest} 无参会加载全部 4 个
     * Controller，任何一个的依赖缺失都会拖垮共享的上下文。
     *
     * <p>默认替身返回 null（=「查无此人」）。本基类的 5 个子类都不调用
     * /message/userSelect，故无需 stub。
     */
    @MockitoBean
    protected UserCacheService userCacheService;

    /**
     * 同上一条的同一类问题，第四次遇到：{@code MessageController}
     * （friendSelect / selectFriend）又 {@code @Autowired} 了一个 {@code @Service}
     * （FriendCacheService）。
     *
     * <p>2026-09-29 补：friendSelect / selectFriend 上缓存时引入。漏掉它同样会在
     * <b>上下文加载期</b>就失败（{@code No qualifying bean of type ...
     * FriendCacheService}），且失败的是**全部 smoke 用例**，而不是只失败与好友相关的
     * 那几个 —— 因为 {@code @WebMvcTest} 无参会加载全部 4 个 Controller，任何一个的
     * 依赖缺失都会拖垮共享的上下文。
     *
     * <p>默认替身返回 null（=「没有好友/没有申请」的替身，实际返回 null 而非空列表）。
     * 本基类的 5 个子类都不调用 /message/friendSelect 或 /message/selectFriend，故无需 stub。
     */
    @MockitoBean
    protected FriendCacheService friendCacheService;

    /**
     * 同上一条的同一类问题，第五次遇到：B2 引入 {@code DistributedLockService} 后，
     * {@code OrderService}（4 处 runLocked）与 {@code MessageController}
     * （friendAdd）都 {@code @Autowired} 了它。漏掉它同样在**上下文加载期**就失败
     * （{@code No qualifying bean of type ... DistributedLockService}），拖垮全部 smoke 用例。
     *
     * <p>⚠️ **但与上面四处有一处关键不同：本替身不能只靠「默认返回 null」**。
     * 那四处缺的 Service 只在子类**不调用**的端点上，默认 null 无害；而本笔的锁落在
     * {@code orderPayIfUp} 上，且 {@link OrderPayIfUpSmokeTest} **真调**该端点 ⇒ 不 stub 的话
     * 端点返回空体（{@code runLocked} 默认返 null），断言 {@code $.msg} 会全红。
     * 故基类必须把模板方法 stub 成「直接把 action 跑掉」（= 视为拿到了锁，等价于锁路径上的正常分支）。
     */
    @MockitoBean
    protected DistributedLockService distributedLockService;

    /**
     * 同一类问题，第六次遇到：B3 让 {@code OrderService} 多依赖一个
     * {@code @Service}（{@code OrderTransitionRecorder}，观测编排）。它不在 Web 切片里，
     * 漏掉同样在**上下文加载期**就红、拖垮全部 smoke 用例
     * （{@code OrderStateMachine} 由类上的 {@code @Import} 用真 bean 解决）。
     *
     * <p>替身的方法**全是 void**（{@code applied} / {@code rejected} / {@code errored} /
     * {@code created}），故默认替身就是「什么都不做」，无需在任何子类里 stub ——
     * 这与 {@code DistributedLockService} 那个必须 stub 的替身恰好相反。
     *
     * <p>推论：smoke 里**观测是空转的**（不代表它在生产里也空转）。观测编排的分支验证在
     * {@code OrderTransitionRecorderTest}，指标/线程池/时间线的语义分别在
     * {@code MallOrderStateMetricsTest} / {@code OrderStateExecutorTest} /
     * {@code OrderTimelineWriterTest}，端到端在 A3 的 C 组场景。
     */
    @MockitoBean
    protected OrderTransitionRecorder orderTransitionRecorder;

    /**
     * 锁替身的行为：**直接执行 action**（不做任何互斥、不碰 Redis）。
     *
     * <p>为什么不逐用例 stub：smoke 测的是 Controller 的分支与响应文案，锁是**穿过它的一层**；
     * 放基类一处，既避免 5 个子类各写一遍，也让「锁在切片里恒为直通」成为一个显式事实
     * （否则读用例的人会以为端点上的互斥在 smoke 里也被验证了 —— 它不在，锁的验证在
     * {@code DistributedLockServiceTest} 与 A3）。
     */
    @BeforeEach
    void stubLockAsDirectRun() {
        when(distributedLockService.runLocked(any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
    }

    /**
     * 预留的鉴权口子。将来接入认证后，在此返回需要附加的请求头
     * （例如 Token / Cookie）。当前零鉴权，返回空 Map。
     */
    protected Map<String, String> authHeaders() {
        return Collections.emptyMap();
    }

    /**
     * 所有请求的唯一出口：子类一律通过本方法或 {@link #get}/{@link #post} 构造请求，
     * 使将来附加鉴权头只需改动基类一处。
     */
    protected MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder builder) {
        authHeaders().forEach(builder::header);
        return builder;
    }

    protected MockHttpServletRequestBuilder get(String urlTemplate) {
        return request(MockMvcRequestBuilders.get(urlTemplate));
    }

    protected MockHttpServletRequestBuilder post(String urlTemplate) {
        return request(MockMvcRequestBuilders.post(urlTemplate));
    }
}
