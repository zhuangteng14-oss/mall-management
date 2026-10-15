package com.minke.mallmanagement.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProtectionGate} 的语义实测 —— 五个 key 的**声明序**、写入 / 复位、以及本段最要紧的那条
 * 口径：**3 键接闸 + 1 键登记 TD + 1 键记录位**（`WIRED_KEYS` / `TD_ONLY_KEY` / `RECORD_ONLY_KEY`）。
 *
 * <p>⚠️ 本文件**不起 Spring、不起任何闸**：门就是一个进程内的五个布尔位（那份实现在类注释里
 * 逐条解释了为什么不兜底、为什么读点在热路径上还敢抛）。
 *
 * <p>⚠️ 与后端演练入参白名单（`AttackEngine.PROTECTION_KEYS`）的**逐字同序**由另一处钉：
 * {@code observe/service/ProtectionKeysAlignmentTest} —— 那边能读到那个包级常量，本文件读不到
 * （它是 {@code package-private}）。两份断言分工：**本文件管门自己的语义**，那份管**三方一致**。
 */
class ProtectionGateTest {

    private final ProtectionGate gate = new ProtectionGate();

    // ================= 声明序与「哪几个真有闸」=================

    @Test
    @DisplayName("KEYS 就是那五个、且是这个序（顺序被人读：面板第 N 枚与第 N 个 key 必须指同一件事）")
    void keysAreTheFiveInTheDeclaredOrder() {
        assertThat(ProtectionGate.KEYS)
                .containsExactly("rateLimit", "circuitBreak", "degrade", "retry", "poolIsolation");
    }

    /**
     * ⚠️ 这条是本笔**口径**的代码化：「本段实际是 3 键接闸 + 1 键 TD + 1 键记录位」。
     * 用户 2026-10-15 逐字纠正过两种错写法（「4 键已接闸」/「5 键里 4 键已接闸」）——
     * 把口径写成断言，是为了让**后来改代码的人**在把某个键从 `WIRED_KEYS` 挪走时，
     * 必须同时改一个会红的测试，而不是只改一行没人读的注释。
     */
    @Test
    @DisplayName("口径：接闸的正好三个、TD-only 一个、record-only 一个，两两不交且并集 = KEYS")
    void wiringIsThreePlusOnePlusOne() {
        assertThat(ProtectionGate.WIRED_KEYS).containsExactly("rateLimit", "circuitBreak", "poolIsolation");
        assertThat(ProtectionGate.TD_ONLY_KEY).isEqualTo("retry");
        assertThat(ProtectionGate.RECORD_ONLY_KEY).isEqualTo("degrade");

        assertThat(ProtectionGate.WIRED_KEYS).as("接闸的三个里不能混进另外两个")
                .doesNotContain(ProtectionGate.TD_ONLY_KEY, ProtectionGate.RECORD_ONLY_KEY);
        assertThat(ProtectionGate.KEYS).as("三个 + 两个 = 全部五个，一个不漏一个不多")
                .containsExactlyInAnyOrderElementsOf(
                        concat(ProtectionGate.WIRED_KEYS,
                                List.of(ProtectionGate.TD_ONLY_KEY, ProtectionGate.RECORD_ONLY_KEY)));
    }

    @Test
    @DisplayName("wired(key)：只有三个真返回 true —— 前端据此标灰（死控件的判据来源）")
    void wiredIsTrueOnlyForTheThree() {
        assertThat(ProtectionGate.wired("rateLimit")).isTrue();
        assertThat(ProtectionGate.wired("circuitBreak")).isTrue();
        assertThat(ProtectionGate.wired("poolIsolation")).isTrue();
        assertThat(ProtectionGate.wired("retry")).as("本段不落码（TD-74）").isFalse();
        assertThat(ProtectionGate.wired("degrade")).as("记录位，系统固有 fail-open").isFalse();
    }

    // ================= 写入 / 读 / 复位 =================

    @Test
    @DisplayName("初值全开（与演练入参缺省、与面板缺省三处一致）")
    void defaultsToAllOn() {
        assertThat(gate.snapshot().values()).containsExactly(true, true, true, true, true);
        assertThat(gate.generation()).as("从没被演练写过 ⇒ 0").isZero();
    }

    @Test
    @DisplayName("apply：勾了的置开、**没勾的一律置关**（不是「只打开勾了的」）")
    void applyTurnsOffEverythingNotListed() {
        // 先造一个「上一轮把限流关了」的残留 —— 这正是 apply 的语义要能消掉的东西
        gate.apply(List.of("circuitBreak"));
        assertThat(gate.isOn("rateLimit")).isFalse();

        gate.apply(List.of("rateLimit", "poolIsolation"));
        assertThat(gate.snapshot()).containsExactly(
                Map.entry("rateLimit", true),
                Map.entry("circuitBreak", false),
                Map.entry("degrade", false),
                Map.entry("retry", false),
                Map.entry("poolIsolation", true));
    }

    @Test
    @DisplayName("apply(null) = 全关（与「缺省 = 全开」**含义相反**，换算点只有一个：DrillRecordService）")
    void applyNullMeansAllOff() {
        gate.apply(null);

        assertThat(gate.snapshot().values()).as("一个都没勾 ⇒ 全关").containsOnly(false);
    }

    @Test
    @DisplayName("resetToDefault：全开（演练收尾的落点 —— 不复位就等于开关越过那一轮留在进程里）")
    void resetTurnsEverythingBackOn() {
        gate.apply(List.of());

        gate.resetToDefault();

        assertThat(gate.snapshot().values()).containsOnly(true);
        assertThat(gate.generation()).as("apply 一次 + reset 一次 = 2（代数只增）").isEqualTo(2L);
    }

    @Test
    @DisplayName("snapshot 按声明序 —— 页面 / 日志 / 测试读到的是同一个序")
    void snapshotFollowsTheDeclaredOrder() {
        assertThat(gate.snapshot().keySet()).containsExactlyElementsOf(ProtectionGate.KEYS);
    }

    // ================= 认不出就炸（刻意不兜底）=================

    @Test
    @DisplayName("isOn(未知 key) 抛，而不是返回一个兜底布尔")
    void unknownKeyThrows() {
        assertThatThrownBy(() -> gate.isOn("rateLimiter"))
                .as("键写错了必须当场炸：兜底 false 会表现成「防护静默失效」，兜底 true 会表现成「关不掉」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rateLimiter");
        assertThatThrownBy(() -> gate.isOn(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("apply 里出现未知 key 同样抛 —— 而且**整次写入不生效**（五个位一个都不落）")
    void applyWithUnknownKeyThrowsAndWritesNothing() {
        assertThatThrownBy(() -> gate.apply(List.of("rateLimit", "poolIso")))
                .isInstanceOf(IllegalArgumentException.class);

        // ⚠️ 这条钉的是**顺序**：require(key) 在 set(...) 之前 ⇒ 表外的 key 一出现，
        //    那一次写入是**全有或全无**的。反过来写（先 set 后 require）会留下一个「半套开关」，
        //    而那种状态在页面上看不出来（五个位全都有一个值）。
        assertThat(gate.snapshot().values()).as("整次写入不生效 ⇒ 仍全是初值").containsOnly(true);
        assertThat(gate.generation()).as("没写成 ⇒ 代数不动").isZero();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
