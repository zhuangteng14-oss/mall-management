package com.minke;

import com.minke.mallmanagement.goods.controller.GoodsController;
import com.minke.mallmanagement.message.controller.MessageController;
import com.minke.mallmanagement.order.controller.OrderController;
import com.minke.mallmanagement.user.controller.UserController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 全上下文启动冒烟 + 数据库可达性 + Controller 缓存代理状态。
 *
 * <p>两个测试都由环境变量开关控制：仅当 {@code A3_ENABLED=true} 时执行，其余情况
 * （含 {@code backend} job 的全量 {@code mvn test}）自动跳过 —— 跳过不是失败，故
 * backend job 仍保持零 MySQL。开关放在<b>类上</b>而非各方法上：将来新增方法不会漏。
 *
 * <p>为何不是 {@code @Disabled}：那会让「有 MySQL 时也不跑」成为永久状态 ——
 * 想启用必须改代码。换成环境开关后，CI 里那个<b>已有 MySQL service 的
 * concurrency job</b> 只需加一个 env 就能真跑起来。
 *
 * <p>之所以不改成 {@code @WebMvcTest} 切片：Web 层已有独立的 smoke 测试
 * （见 {@code smoke/} 包），改切片会与之重复，同时丢掉「完整上下文能否启动」
 * 这一独有覆盖。故保留 {@code @SpringBootTest}（默认 {@code webEnvironment=MOCK}，
 * 不真起 Tomcat，也就不与同 job 里跑着的后端抢 8088）。
 *
 * <p>连接目标由 {@code application.properties}（默认 profile）决定：
 * {@code localhost:3306/mall_management_sql}，root/123456 —— 与 concurrency job 的
 * MySQL service 及库名恰好吻合（库由 {@code mall_management_sql.sql} 自建）。
 * 刻意<b>不</b>在此加 {@code SPRING_PROFILES_ACTIVE=a3}：a3 profile 的默认值指向
 * VM 的 192.168.229.129，那是给「启动被测后端」用的，不是给本测试用的。
 *
 * <p>TD-10 至此关闭，理由见 docs/agent/tech-debt.md（同日更新）。
 */
@EnabledIfEnvironmentVariable(named = "A3_ENABLED", matches = "true")
@SpringBootTest
class MallManagementApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GoodsController goodsController;

    @Autowired
    private MessageController messageController;

    @Autowired
    private OrderController orderController;

    @Autowired
    private UserController userController;

    /**
     * 全上下文启动冒烟。
     *
     * <p>⚠️ 它<b>只</b>证明「完整 Spring 上下文能启动」，<b>不</b>证明能连数据库。
     * 依据是 2026-09-20 的三个探针：库名不存在（{@code Unknown database}）、乃至端口
     * 无监听（{@code Connection refused}）时，本方法<b>依然 PASS</b>。成因有二：
     * {@code spring.jpa.hibernate.ddl-auto} 的 schema 管理是「尽力而为」（库不可达时失败只记
     * ERROR 日志、不终止启动），且本项目无 {@code @Entity}（TD-01：JPA 空转），本就无 schema
     * 可校验——两条叠加，启动路径上无一处「连不上就必须失败」。⚠️ 该值 2026-10-04 由 {@code update} 改为 {@code validate}，两条成因实测均不变。
     *
     * <p>它仍有独立价值：smoke 测试是 {@code @WebMvcTest} 切片，从不启动完整上下文。
     * 数据库可达性由 {@link #databaseIsReachable()} 承担，别把两者混为一谈。
     */
    @Test
    void contextLoads() {
    }

    /**
     * 数据库可达性：真发一条查询，而不是「上下文起得来就算数」。
     *
     * <p>本方法存在的唯一理由就是上一条的 ⚠️：若无它，库不存在 / 连不上时整个类
     * 依然 PASS，于是「需真实 MySQL」这句话是虚的 —— 属「断言落空」那一族
     * （参见 tech-debt TD-15、context-pack「元教训」）。
     *
     * <p>刻意<b>不</b>断言 {@code VERSION()} 前缀：本地开发库是 8.0.32，断言 8.4 会让
     * 本测试在本地变红，而环境契约不是它的职责 —— 版本门禁由 A3 脚本的 P1
     * （{@code VERSION()} 必须以 {@code 8.4.} 开头，硬门禁）承担。
     */
    @Test
    void databaseIsReachable() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertNotNull(one, "SELECT 1 返回 null");
        assertEquals(1, one.intValue());
    }

    /**
     * CGLIB 防回归：goods 的三个写接口撤掉 {@code @Caching} 后，Controller <b>不应</b>再被代理。
     *
     * <p>背景（2026-09-28，D 笔）：此前四个 cacheName 的 evict 挂在 Controller 的三个写方法上
     * ⇒ 本 bean 被 CGLIB 代理；evict 收敛到 {@code GoodsCacheService.evictByMerchant()} 后
     * Controller 恢复无代理。这条断言把这个状态<b>钉住</b>：将来有人再往 Controller 上挂缓存
     * 注解，本测试会红。
     *
     * <p>判据用两条，而不是「类名含不含 {@code $$SpringCGLIB$$}」：{@code isAopProxy} 只回答
     * 「被代理了」；真出事时失败信息里把 {@code getAdvisors()} 打出来，<b>CacheInterceptor 在
     * 不在</b>才是「就是缓存注解把它代理了」的直接证据。
     *
     * <p>⚠️ 必须在<b>完整上下文</b>里查：{@code @WebMvcTest} 切片不会导入 {@code @EnableCaching}
     * 带的 {@code ProxyCachingConfiguration} ⇒ 切片里<b>有没有注解都是 false</b>（假阴性）。
     * 这是本断言留在 {@code @SpringBootTest} 里的原因。
     *
     * <p>⚠️ 本方法<b>不依赖数据库</b>（上下文能起即可，同 {@link #contextLoads()}），却因类级
     * 开关而只在 {@code A3_ENABLED=true} 时执行 —— 这是<b>刻意</b>的：类级开关的一条设计目的
     * 就是「将来新增方法不会漏」（见类注释），为它把开关下沉到方法级会破坏那条保证。
     * CI 的实际覆盖不减：concurrency job 设了 {@code A3_ENABLED}，本断言在那里会跑。
     */
    @Test
    void goodsControllerIsNotAopProxied() {
        assertFalse(AopUtils.isAopProxy(goodsController),
                "GoodsController 被代理了 —— 检查是否有人把缓存注解挂回了 Controller。"
                        + " advisors = " + describeAdvisors(goodsController));
        assertFalse(goodsController instanceof Advised,
                "GoodsController 实现了 Advised —— 它是个代理。advisors = " + describeAdvisors(goodsController));
    }

    /**
     * CGLIB 防回归（{@link #goodsControllerIsNotAopProxied()} 的孪生）：{@code MessageController}
     * 也不应被代理。
     *
     * <p>背景（2026-09-29）：friendSelect / selectFriend 上缓存时，两个 {@code @Cacheable} 与
     * {@code evictPair} 的 {@code @Caching} **全部落在 {@code FriendCacheService}**，Controller 侧只加
     * 三行「调 evictPair()」的显式调用 ⇒ 本 bean 保持无代理。这条断言把这个状态钉住。
     *
     * <p>⚠️ 与 goods 那条同一成因，也同样**必须**在完整上下文里查：{@code @WebMvcTest} 切片不导入
     * {@code ProxyCachingConfiguration} ⇒ 切片里有没有注解都是 false（假阴性）。
     */
    @Test
    void messageControllerIsNotAopProxied() {
        assertFalse(AopUtils.isAopProxy(messageController),
                "MessageController 被代理了 —— 检查是否有人把缓存注解挂回了 Controller。"
                        + " advisors = " + describeAdvisors(messageController));
        assertFalse(messageController instanceof Advised,
                "MessageController 实现了 Advised —— 它是个代理。advisors = " + describeAdvisors(messageController));
    }

    /**
     * CGLIB 防回归（第三条）：{@code OrderController} 不应被代理。
     *
     * <p>背景（2026-10-02，笔 2）：订单 8 个端点从 {@code GoodsController} 拆出时，
     * <b>只搬了路由与入参绑定</b> —— 取锁、状态机编排、观测投递早在 PR #25 就下沉到
     * {@code OrderService}，故本类身上没有任何 {@code @Cacheable} / {@code @Caching} /
     * {@code @Transactional}。这条断言把这个「拆分后仍然无代理」的状态钉住：将来有人
     * 图省事把缓存注解挂到订单 Controller 上，本测试会红。
     *
     * <p>⚠️ 同前两条：切片里恒为 false（假阴性），必须在完整上下文里查。
     */
    @Test
    void orderControllerIsNotAopProxied() {
        assertFalse(AopUtils.isAopProxy(orderController),
                "OrderController 被代理了 —— 检查是否有人把缓存注解挂回了 Controller。"
                        + " advisors = " + describeAdvisors(orderController));
        assertFalse(orderController instanceof Advised,
                "OrderController 实现了 Advised —— 它是个代理。advisors = " + describeAdvisors(orderController));
    }

    /**
     * CGLIB 防回归（第四条，也是**唯一一条钉住状态翻转**的）：{@code UserController} 不应被代理。
     *
     * <p>⚠️ <b>它与前三条的成因不同</b>：goods / message / order 三条钉的是「**从来没有**被代理」，
     * 本条钉的是一次**真的翻转** —— 三层前（2026-10-02 及以前）本 bean **是被代理的**，
     * 因为 {@code loginAdd} 上挂着全仓唯一那处真 {@code @Transactional}
     * （{@code UserController:57}），Boot 的 {@code TransactionAutoConfiguration} 会为它建事务代理。
     *
     * <p>依据（两种测法，独立）：① 2026-10-02 user 域三层重构前，把本断言原样跑一遍
     * —— {@code A3_ENABLED=true mvn test -Dtest=MallManagementApplicationTests} ——
     * <b>本条为红</b>，失败信息里的 advisor 链含 {@code TransactionInterceptor}；
     * ② 重构后同一命令为绿。所以这不是「照抄前面三条的惯例」，
     * 它记录的是「{@code @Transactional} 随方法体下沉到 {@code UserService.loginAdd} 之后，
     * Controller 恢复无代理」这个**可观测的状态变化**。
     *
     * <p>⚠️ 判据仍与前三条一致（{@code isAopProxy} + {@code instanceof Advised}），刻意
     * <b>不</b>断言「类名里没有 {@code $$SpringCGLIB$$}」：真出事时失败信息里的 advisor 链
     * 才是「是什么把它代理了」的直接证据 —— 本次是 {@code TransactionInterceptor}，
     * 前三条例子里若出现则会是 {@code CacheInterceptor}。
     *
     * <p>⚠️ 同前三条：切片里恒为 {@code false}（{@code @WebMvcTest} 不导入
     * {@code ProxyCachingConfiguration} / 事务后置处理器）⇒ <b>假阴性</b>，必须在完整上下文里查。
     */
    @Test
    void userControllerIsNotAopProxied() {
        assertFalse(AopUtils.isAopProxy(userController),
                "UserController 被代理了 —— 三层前这是 @Transactional 造成的，"
                        + "重构后若再出现，检查是否有人把 @Transactional / 缓存注解挂回了 Controller。"
                        + " advisors = " + describeAdvisors(userController));
        assertFalse(userController instanceof Advised,
                "UserController 实现了 Advised —— 它是个代理。advisors = " + describeAdvisors(userController));
    }

    /** 失败信息用：把 advisor 链打出来，便于一眼看出是不是 CacheInterceptor。 */
    private String describeAdvisors(Object controller) {
        if (controller instanceof Advised advised) {
            return Arrays.toString(advised.getAdvisors());
        }
        return "（非 Advised）";
    }

}
