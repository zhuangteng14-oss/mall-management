package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.protect.ProtectionGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AttackSwitchConfig}（乙案，3a.1）：**一份共享配置**的读写语义。
 *
 * <p>⚠️ 为什么给它单开一个测试类（引擎与服务的用例都没法替它测）：本类的两个要害**只有在它自己
 * 身上才观察得到**：
 * <ol>
 *   <li>「防护的位**不复制**、真值只有 {@link ProtectionGate} 一处」—— 判据是「写配置面 ⇒ 直接
 *       在门上读得到」（反向也一样），这一条在引擎的用例里被 Config 快照隔着，看不出来；</li>
 *   <li>「校验全过**再**写」—— 判据是「写一半失败 ⇒ 一个位都没动」。这条最值钱：半套配置的表现是
 *       「页面显示关了、闸却还在拦」那种**两边都自认为对**的分歧，没有任何地方会报错。</li>
 * </ol>
 *
 * <p>⚠️ 本类**测不到**的（如实登记，别把这里的绿当成那些结论）：不落库、不重启、不涉及 HTTP。
 * 「跑完不复位」的端到端证据（真演练跑完配置还在）只能来自阶段 4 手验 —— 在替身引擎下
 * 「谁调了 {@code resetToDefault}」根本观察不到（见 {@code DrillRecordServiceTest} 那段说明）。
 */
class AttackSwitchConfigTest {

    /** 真件：它没有依赖、没有 I/O，就是进程内的五个布尔位（同 D 段那两条用例的取舍）。 */
    private final ProtectionGate gate = new ProtectionGate();
    private final AttackSwitchConfig config = new AttackSwitchConfig(gate, new ObjectMapper());

    // ==================== 一、缺省 ====================

    /** 缺省 = 防护**全开**（5 个）+ 注入**全关**（0 个），且 {@code isDefault} 认得出这就是默认。 */
    @Test
    void defaultsToAllProtectionsOnAndAllInjectionsOff() {
        AttackSwitchConfig.State st = config.state();
        assertEquals(ProtectionGate.KEYS, st.protections(), "声明序 = ProtectionGate.KEYS（不另写一份）");
        assertEquals(5, st.protections().size());
        assertTrue(st.injections().isEmpty());
        assertTrue(st.isDefault(), "全开 + 注入全关 = 默认");
        assertEquals(0L, st.version(), "还没写过 ⇒ 版本 0");
        // 读出来的清单是**快照**：就地改它不该改到配置里（`protections()` 每次现攒一份）
        List<String> snapshot = config.protections();
        snapshot.clear();
        assertEquals(5, config.protections().size(), "改读出来的那份，不影响配置本身");
    }

    // ==================== 二、防护的位不复制（代理 ProtectionGate）====================

    /**
     * ⚠️ 本类的核心断言：写配置面**就是**写那把门。
     *
     * <p>反过来说也成立 —— 门被别处拨动（真实流量侧的运维动作），配置面立刻读得到。若本类私存了
     * 第二份 protections 副本，这两条会**同时**失败，而失败的样子正是那个最难查的分歧：
     * 「配置面显示已关、闸却还在拦」。
     */
    @Test
    void protectionsAreAProxyOfTheGateNotASecondCopy() {
        config.update(List.of("rateLimit"), null);

        assertTrue(gate.isOn("rateLimit"), "写配置面 ⇒ 门上直接读得到（没有第二份副本）");
        for (String key : ProtectionGate.KEYS) {
            if (!"rateLimit".equals(key)) {
                assertFalse(gate.isOn(key), "没列的键被写成 false（apply 是「按勾了哪些写」）：" + key);
            }
        }

        // 反向：门被拨动 ⇒ 配置面的快照跟着变（读走 isOn，不走缓存）
        gate.apply(List.of());
        assertTrue(config.protections().isEmpty(), "门全关 ⇒ 配置面读到的就是空清单");
        assertFalse(config.state().isDefault());
    }

    // ==================== 三、写入语义：null = 不动 / [] = 关光 ====================

    /** {@code null} = 「这次不改这一项」；{@code []} = 「这一项关光」—— 两个含义相反，别混。 */
    @Test
    void nullMeansLeaveAloneAndEmptyListMeansTurnEverythingOff() {
        config.update(List.of("retry"), List.of("redisDown"));

        // null 注入 ⇒ 注入那一项原样不动（日志页只拨一枚防护开关时不必带注入那一行）
        config.update(List.of("circuitBreak"), null);
        assertEquals(List.of("circuitBreak"), config.protections());
        assertEquals(List.of("redisDown"), config.injections(), "null 没把注入清掉");

        // 空清单 ⇒ 真的关光（「这一轮一个防护都不开」是演示要用的对照轮，是个有意义的状态）
        config.update(List.of(), List.of());
        assertTrue(config.protections().isEmpty());
        assertTrue(config.injections().isEmpty());

        // 两个都是 null ⇒ 什么也不改（也不意外回默认）
        config.update(null, List.of("redisDown"));
        config.update(null, null);
        assertTrue(config.protections().isEmpty(), "update(null,null) 不会把配置拨回默认");
        assertEquals(List.of("redisDown"), config.injections());
        assertFalse(config.state().isDefault());
    }

    /**
     * 重复项去重；未知 key 抛（控制器转 400），且**一个位都不写**。
     *
     * <p>⚠️ 两组的「顺序」不是一回事：防护的读数走 {@link ProtectionGate#KEYS} 的**声明序**
     * （门只有位、没有顺序），注入的读数走**写入序**（去重时保首次出现）—— 那是本类自己存的那一半。
     */
    @Test
    void dedupsAndRejectsUnknownKeysBeforeWritingAnything() {
        config.update(List.of("retry", "rateLimit", "retry"), List.of("dbSlow", "redisDown", "dbSlow"));
        assertEquals(List.of("rateLimit", "retry"), config.protections(),
                "按 KEYS 声明序（与写入时的先后无关），重复项只算一个");
        assertEquals(List.of("dbSlow", "redisDown"), config.injections(), "去重且保首次序");

        // ⚠️ 防护那一项**看着合法**，坏的是注入那一项 —— 一次都不许落
        assertThrows(IllegalArgumentException.class,
                () -> config.update(List.of("circuitBreak"), List.of("nope")));
        assertEquals(List.of("rateLimit", "retry"), config.protections(),
                "整笔写入被拒 ⇒ 合法的防护那一项也不许落（否则留下半套配置）");
        assertEquals(List.of("dbSlow", "redisDown"), config.injections());

        // 反向同理：防护那一项里有不认识的键 ⇒ 注入那一项也不写
        assertThrows(IllegalArgumentException.class,
                () -> config.update(List.of("rateLimit", "限流"), List.of("mqDown")));
        assertEquals(List.of("dbSlow", "redisDown"), config.injections(), "注入那一项没被这半笔写进去");

        // 未知键不得把校验挡在门外（错误消息里要能看出是哪个键）
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config.update(List.of("限流"), null));
        assertTrue(e.getMessage().contains("限流"), "消息要指出是哪个键：" + e.getMessage());
    }

    // ==================== 四、版本与恢复默认 ====================

    /** 版本单调递增（页面靠它决定「要不要重绘」，不是审计线索）：每次 update / reset 都 +1。 */
    @Test
    void versionIncrementsOnEveryWrite() {
        assertEquals(0L, config.state().version());
        config.update(List.of("rateLimit"), null);
        assertEquals(1L, config.state().version());
        config.update(null, List.of("redisDown"));
        assertEquals(2L, config.state().version());
        // 被拒的那一笔**不算**一次写入（版本是「改成了什么」，不是「试过几次」）
        assertThrows(IllegalArgumentException.class, () -> config.update(List.of("nope"), null));
        assertEquals(2L, config.state().version());
        config.resetToDefault();
        assertEquals(3L, config.state().version());
    }

    /**
     * [恢复默认] = 防护全开 + 注入全关 —— 它是「拨完忘了拨回来」的**唯一手工**恢复点
     * （重启是另一个，且它的证据在阶段 4 手验，不在这里）。
     */
    @Test
    void resetToDefaultTurnsAllProtectionsBackOnAndClearsInjections() {
        config.update(List.of("rateLimit"), List.of("redisDown"));
        assertFalse(config.state().isDefault());

        AttackSwitchConfig.State back = config.resetToDefault();

        assertEquals(ProtectionGate.KEYS, back.protections());
        assertTrue(back.injections().isEmpty());
        assertTrue(back.isDefault());
        // ⚠️ 复位的是**真门**（那五个位），不只是本类的读数
        for (String key : ProtectionGate.KEYS) {
            assertTrue(gate.isOn(key), "门上的位没被复位：" + key);
        }
    }

    // ==================== 五、catalog：哪些键是死控件 ====================

    /**
     * ⚠️ {@code Wired=false} 的键在面板上**必须画成不可开关**（用户 2026-10-15 裁定）——
     * 画成一个能拨却什么也不发生的控件，比不画它坏得多。本用例是那份「不可拨清单」的哨兵：
     * 防护里的 {@code degrade}（记录位）；四个注入自 D3（2026-10-18）起**全部真接通**
     * （{@code mqDown} 拦发布侧 / {@code dbSlow} 延迟 SELECT / {@code jobDown} 停调度）。
     */
    @Test
    void catalogMarksTheNotWiredKeysSoTheUiCannotOfferDeadToggles() {
        Map<String, Object> cat = config.catalog();

        assertEquals(ProtectionGate.KEYS, keys(cat.get("Protections")), "目录按声明序、键名 Key");
        assertEquals(AttackSwitchConfig.INJECTION_KEYS, keys(cat.get("Injections")),
                "注入键 = 引擎那一份（见下一条的同一份断言）");
        assertEquals(List.of(true, true, false, true, true), wired(cat.get("Protections")),
                "只有 degrade 是记录位（名单见 ProtectionGate.RECORD_ONLY_KEY）");
        assertEquals(List.of(true, true, true, true), wired(cat.get("Injections")),
                "D3 起四个注入全部接通（AttackEngine.INJECTIONS_NOT_WIRED 为空）");
    }

    /**
     * ⚠️ 注入键清单**必须与引擎那一份是同一个对象**，不是「内容相等」。
     *
     * <p>两份清单一旦分叉，表现是「拨了没反应」—— 配置面认得的键与引擎跑的键不是一套，
     * 而页面上、日志里都看不出来。故本用例引用的判据是 {@link org.junit.jupiter.api.Assertions#assertSame}
     * （引用同一份），内容相等在这里**不成立**（改字面量就等于改契约）。
     */
    @Test
    void injectionKeysAreTheVerySameListTheEngineRuns() {
        assertSame(AttackEngine.INJECTION_KEYS, AttackSwitchConfig.INJECTION_KEYS);
    }

    // ==================== 六、传输层：JSON 数组串 ====================

    /**
     * {@code updateFromJson}：空白串 = **没给**（= 不动这一项，表单控件不发空值）；
     * 形状错 ⇒ 抛（400），**不**回落到「不动它」—— 回落等于替调用者做了另一个决定。
     */
    @Test
    void updateFromJsonTreatsBlankAsAbsentAndRejectsBadJson() {
        config.updateFromJson("[\"rateLimit\",\"retry\"]", "[\"redisDown\"]");
        assertEquals(List.of("rateLimit", "retry"), config.protections());
        assertEquals(List.of("redisDown"), config.injections());

        // 空白 / null = 没给 ⇒ 那一项不动（这里 `[]` 才是「关光」）
        config.updateFromJson("  ", null);
        assertEquals(List.of("rateLimit", "retry"), config.protections());
        assertEquals(List.of("redisDown"), config.injections());

        // 形状错（不是 JSON 数组）一律抛，且**一个位都不写**
        assertThrows(IllegalArgumentException.class,
                () -> config.updateFromJson("rateLimit,retry", null));
        assertThrows(IllegalArgumentException.class, () -> config.updateFromJson("\"rateLimit\"", null));
        assertEquals(List.of("rateLimit", "retry"), config.protections(), "解析失败之后配置面没变");
        // ⚠️ 数组里是不认识的键**也**抛（两层都算 400：形状在传输层、白名单在 update）
        assertThrows(IllegalArgumentException.class, () -> config.updateFromJson("[\"限流\"]", null));
        // 消息里要写对**是哪一个参数**错了（它是能被 curl 直接打的端点，报错要能照着改）
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config.updateFromJson(null, "[\"redisDown\""));
        assertTrue(e.getMessage().contains("injections"), "消息要指出是哪个参数：" + e.getMessage());
    }

    // ==================== 小工具 ====================

    /** 从 catalog 的一段里取键名（保序）。 */
    @SuppressWarnings("unchecked")
    private static List<String> keys(Object section) {
        return ((List<Map<String, Object>>) section).stream()
                .map(m -> (String) m.get("Key")).toList();
    }

    /** 从 catalog 的一段里取「有没有真闸」的位（保序）。 */
    @SuppressWarnings("unchecked")
    private static List<Boolean> wired(Object section) {
        return ((List<Map<String, Object>>) section).stream()
                .map(m -> (Boolean) m.get("Wired")).toList();
    }
}
