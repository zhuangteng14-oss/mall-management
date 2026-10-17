package com.minke.mallmanagement.protect;

import com.minke.mallmanagement.goods.service.GoodsService;
import com.minke.mallmanagement.order.service.OrderService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断**挂在哪**的逐条清单（D 段 · commit 2）—— 把裁定里那句「禁止只挂订单 / 支付」与
 * 「必须在能看见下游的那一层」变成可断言的事实。
 *
 * <h2>【为什么这条值得一个测试文件】</h2>
 * 熔断注解**加错了地方不会报错**：加在 Controller 上照样编译、照样跑、照样绿，
 * 只是那条闸从此守在一个看不见 MySQL 的位置（请求还没到业务层就被拦，错误率永远算不出来）。
 * 而「哪几个方法挂了熔断」这件事，是报告与答辩里会被**当场问**的东西
 * （「你的熔断包了哪些下游？」）—— 它必须能从代码里查，不是靠回忆。
 *
 * <h2>【它**不**验什么】</h2>
 * 不验熔断是否真的被织入（那是 AOP 自动装配的活；本仓已有的
 * {@code MallManagementApplicationTests#goodsControllerIsNotAopProxied} 与
 * {@code GoodsWriteTxAtomicityTest} 从两侧钉着代理这件事），也不验阈值（那在
 * {@code ProtectionThresholdsTest}）。本文件只回答**方法清单**这一个问题。
 *
 * <p>⚠️ 用反射枚举而不是逐个 {@code getMethod("goodsAdd", String.class, …)}：签名一变就红在
 * 「找不到方法」上，而那条红说明不了任何与熔断有关的事。枚举 {@code getDeclaredMethods()} 之后
 * 比**名字 → 实例名**的表，红了就是「挂载点变了」，指得准。
 */
class CircuitBreakerWiringTest {

    /** 本段允许出现的两个熔断实例名（**只有这两个** —— 见 TD-75 为什么 Redis / MQ 不叠 r4j）。 */
    private static final String READ = "mallMysqlRead";
    private static final String WRITE = "mallMysqlWrite";

    @Test
    @DisplayName("GoodsService：5 处，读 2 / 写 3 —— 逐条钉住（多了少了都红）")
    void goodsServiceLandingPoints() {
        // ⚠️ 顺序**不可断言**：`Class#getDeclaredMethods()` 的返回顺序是**未定义**的
        //    （JVM 自行决定，实测与源码顺序不一致）⇒ 用 containsExactlyInAnyOrder，
        //    按名字比集合。这**不是**妥协：本文件要钉的是「哪几个方法挂了、挂的是哪个实例」，
        //    声明序在这件事上没有意义。
        assertThat(landingPoints(GoodsService.class)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "goodsAdd", WRITE,          // 新增商品：多表写 + @Transactional
                "goodsSelectHome", READ,    // 首页商品列表：演练的主战场
                "goodsDe", WRITE,           // 删除：两条真 DELETE
                "goodsUp", WRITE,           // 改名：同样两表
                "productSearch", READ));    // 搜索：演练的另一主战场
    }

    /**
     * ⚠️ 这条钉的是裁定的边界：熔断**必须跨域**（不许只挂订单 / 支付）。
     * 本仓的形态是「商品 5 处 + 订单 1 处」，且**读 / 写都有** ——
     * 这条断言在「有人把商品那几处摘掉、只留订单」时会红。
     */
    @Test
    @DisplayName("跨域：商品 5 处 + 订单 1 处（裁定「禁止只挂订单 / 支付」的反面判据）")
    void circuitBreakersAreNotConfinedToOrders() {
        Map<String, String> goods = landingPoints(GoodsService.class);
        Map<String, String> order = landingPoints(OrderService.class);

        assertThat(goods).as("商品域必须挂着 —— 只挂订单是不允许的").hasSize(5);
        assertThat(order).as("订单域挂的是下单那一条（跨域的另一半）")
                .containsExactlyInAnyOrderEntriesOf(Map.of("orderAdd", WRITE));
        assertThat(goods.values()).as("读 / 写两个实例都要用到（合成一个就没有「读被打开时写仍能过」这个收益）")
                .contains(READ, WRITE);
    }

    @Test
    @DisplayName("全仓只用这两个实例名 —— 多出第三个（比如给 Redis / MQ 也套一层）就是重复造，见 TD-75")
    void onlyTheTwoInstanceNamesAreEverUsed() {
        TreeSet<String> names = new TreeSet<>();
        names.addAll(landingPoints(GoodsService.class).values());
        names.addAll(landingPoints(OrderService.class).values());

        assertThat(names).containsExactly(READ, WRITE);
    }

    // ================= 工具 =================

    /**
     * 类里所有挂了 {@code @CircuitBreaker} 的方法：**名字 → 实例名**。
     *
     * <p>⚠️ 顺序**没有意义**（{@code getDeclaredMethods()} 的返回顺序由 JVM 决定、与源码顺序无关，
     * 2026-10-15 实测确认）⇒ 这个 {@code LinkedHashMap} 的序只是「反射给什么就是什么」，
     * 断言一律用 {@code containsExactlyInAnyOrder}，别拿它当声明序读。
     */
    private static Map<String, String> landingPoints(Class<?> type) {
        Map<String, String> found = new LinkedHashMap<>();
        for (Method m : type.getDeclaredMethods()) {
            CircuitBreaker annotation = m.getAnnotation(CircuitBreaker.class);
            if (annotation != null) {
                found.put(m.getName(), annotation.name());
            }
        }
        return found;
    }

    /** ⚠️ 反射探针自己先要站得住：注解得真在 classpath 上（引错包会静默变成 0 处）。 */
    @Test
    @DisplayName("自校验：注解类型可加载，且两个类各自确实被反射枚举到了方法")
    void theProbeItselfIsSane() {
        assertThat(CircuitBreaker.class.getName())
                .as("注解来自 resilience4j，不是某个同名的手写注解").startsWith("io.github.resilience4j");
        assertThat(Arrays.stream(GoodsService.class.getDeclaredMethods()).count()).isPositive();
        assertThat(Arrays.stream(OrderService.class.getDeclaredMethods()).count()).isPositive();
    }
}
