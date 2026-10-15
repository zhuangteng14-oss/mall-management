package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.protect.ProtectionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「五个防护 key 的表**只有一处不一致就会出事**」这件事的守护测试（D 段 · commit 2）。
 *
 * <h2>【为什么必须是本包的文件】</h2>
 * 判据要读 {@code AttackEngine.PROTECTION_KEYS}（演练侧的白名单），而它是
 * {@code package-private}（{@code static final List<String>}，没有修饰符）⇒ 只有
 * 本包的测试读得到。放到 {@code protect} 包去测就只能对着一个字面量比 —— 那证明的是
 * 「我的字面量和我的一致」，不是「两份表一致」，等于没测。
 *
 * <h2>【三份表，两两的关系】</h2>
 * <pre>
 *  后端演练入参白名单   AttackEngine.PROTECTION_KEYS      （表外的 key 一律 400）
 *  后端门的声明序       ProtectionGate.KEYS               （面板第 N 枚 = 第 N 个 key）
 *  前端面板的声明序     config/labels/observe-labels.js   ← 三份里的第三份，**Java 侧测不到**
 * </pre>
 * ⚠️ 前两份由本文件钉死（同序 + 同值）。第三份（前端那份）**跨语言**，本文件读不到 ——
 * 它的守护走 {@code scripts/probes/} 下的探针（一次 grep 三处比对），如实记在这里，
 * 免得后来的人以为「有测试守着三份」。
 *
 * <p>⚠️ 【为什么这个顺序值得一条测试】它不是审美：后端 `Kind` 之外，这五个 key 还会
 * 出现在**结果页的勾选清单**与**记录里存下的 `Result.Engine`** 上；两处顺序一岔，
 * 看数字的人会把「第 2 个开关」指到另一个防护上，而且**不会报错**（两处都是合法的字符串列表）。
 */
class ProtectionKeysAlignmentTest {

    @Test
    @DisplayName("演练入参白名单 = 防护门的声明序（逐字同序）—— 两处一岔不会报错，只会指错开关")
    void drillWhitelistMatchesTheGateDeclarationOrder() {
        assertThat(AttackEngine.PROTECTION_KEYS)
                .as("后端两处表必须逐字同序")
                .containsExactlyElementsOf(ProtectionGate.KEYS);
    }

    @Test
    @DisplayName("接闸的三个 / TD 一个 / 记录位一个 —— 三个 key 都**在**演练白名单里（不在的话拨不到）")
    void everyWiredKeyIsAlsoAcceptedByTheDrillWhitelist() {
        assertThat(AttackEngine.PROTECTION_KEYS)
                .as("真有闸的三个必须拨得到；拨不到 = 演示现场按不动")
                .containsAll(ProtectionGate.WIRED_KEYS)
                .as("两枚没有闸的也必须在白名单里：它们要能「如实回答自己没生效」，"
                        + "从表里删掉会让前端现有的两枚开关变成 400")
                .contains(ProtectionGate.TD_ONLY_KEY, ProtectionGate.RECORD_ONLY_KEY);
    }
}
