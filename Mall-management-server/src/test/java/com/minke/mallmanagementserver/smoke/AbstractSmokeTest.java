package com.minke.mallmanagementserver.smoke;

import com.minke.mallmanagement_service.DistributedLockService;
import com.minke.mallmanagement_service.FriendCacheService;
import com.minke.mallmanagement_service.GoodsCacheService;
import com.minke.mallmanagement_service.GoodsTypeCacheService;
import com.minke.mallmanagement_service.UserCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
 * 3 个 smoke test 无需改动。
 */
@WebMvcTest
@ActiveProfiles("smoke")
public abstract class AbstractSmokeTest {

    @Autowired
    protected MockMvc mockMvc;

    /**
     * {@code @WebMvcTest} 无参时会加载全部 3 个 Controller，其中两个
     * {@code @Autowired JdbcTemplate}。切片不包含 DataSource 自动配置，
     * 故必须提供 JdbcTemplate 的替身，否则上下文启动失败。
     * 放在基类，使不使用数据库的用例（如静态资源测试）也能共享同一套上下文。
     */
    @MockitoBean
    protected JdbcTemplate jdbcTemplate;

    /**
     * 同上一条的同一类问题：{@code @WebMvcTest} 无参加载全部 3 个 Controller，
     * 其中 {@code MallManagementGoodsController} 还 {@code @Autowired} 了两个
     * 缓存 Service，而 {@code @Service} 不在 Web 切片内 ⇒ 切片里必须也给替身。
     *
     * <p>2026-09-28 补：goodstype 与 goodsSelectHome 上缓存时各引入一个 Service，
     * 漏掉它们会让 4 个 smoke 用例在**上下文加载期**就失败（报
     * {@code No qualifying bean of type ... GoodsTypeCacheService}），
     * 与 {@code JdbcTemplate} 那条是同一个成因、同一个修法。
     *
     * <p>默认替身返回 null —— 本基类的 3 个子类都不调用走缓存的两个端点
     * （goodstype / goodsSelectHome），故无需 stub。
     */
    @MockitoBean
    protected GoodsTypeCacheService goodsTypeCacheService;

    @MockitoBean
    protected GoodsCacheService goodsCacheService;

    /**
     * 同上一条的同一类问题，第三次遇到：{@code MallManagementMessage}
     * （userSelect）也 {@code @Autowired} 了一个 {@code @Service}（UserCacheService）。
     *
     * <p>2026-09-28 补：userSelect 上缓存（+ 负缓存）时引入。漏掉它同样会在
     * <b>上下文加载期</b>就失败（{@code No qualifying bean of type ...
     * UserCacheService}），且失败的是**全部 4 个 smoke 用例**，而不是只失败
     * 与 userSelect 相关的那一个 —— 因为 {@code @WebMvcTest} 无参会加载全部 3 个
     * Controller，任何一个的依赖缺失都会拖垮共享的上下文。
     *
     * <p>默认替身返回 null（=「查无此人」）。本基类的 3 个子类都不调用
     * /message/userSelect，故无需 stub。
     */
    @MockitoBean
    protected UserCacheService userCacheService;

    /**
     * 同上一条的同一类问题，第四次遇到：{@code MallManagementMessage}
     * （friendSelect / selectFriend）又 {@code @Autowired} 了一个 {@code @Service}
     * （FriendCacheService）。
     *
     * <p>2026-09-29 补：friendSelect / selectFriend 上缓存时引入。漏掉它同样会在
     * <b>上下文加载期</b>就失败（{@code No qualifying bean of type ...
     * FriendCacheService}），且失败的是**全部 smoke 用例**，而不是只失败与好友相关的
     * 那几个 —— 因为 {@code @WebMvcTest} 无参会加载全部 3 个 Controller，任何一个的
     * 依赖缺失都会拖垮共享的上下文。
     *
     * <p>默认替身返回 null（=「没有好友/没有申请」的替身，实际返回 null 而非空列表）。
     * 本基类的 3 个子类都不调用 /message/friendSelect 或 /message/selectFriend，故无需 stub。
     */
    @MockitoBean
    protected FriendCacheService friendCacheService;

    /**
     * 同上一条的同一类问题，第五次遇到：B2 引入 {@code DistributedLockService} 后，
     * {@code MallManagementGoodsController}（4 个端点）与 {@code MallManagementMessage}
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
     * 锁替身的行为：**直接执行 action**（不做任何互斥、不碰 Redis）。
     *
     * <p>为什么不逐用例 stub：smoke 测的是 Controller 的分支与响应文案，锁是**穿过它的一层**；
     * 放基类一处，既避免 4 个子类各写一遍，也让「锁在切片里恒为直通」成为一个显式事实
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
