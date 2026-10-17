package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3a.1 补笔（用户 2026-10-16 裁定「(a) 端到端哨兵 —— 现在补」）：
 * <b>共享开关配置在一轮演练之后必须逐字不变</b>。
 *
 * <h2>【它为什么必须存在 —— 这是删掉两条用例后留下的那个洞】</h2>
 * {@code DrillRecordServiceTest} 里 D 段那两条时序用例删掉后，「跑完不复位」这条口径在测试里
 * <b>只剩「配置面自己不自动复位」这一半</b>（{@code AttackSwitchConfigTest}）—— 那是<b>自我属性</b>。
 * 另一半是<b>流程属性</b>：<b>演练流程里没有任何一处去把它写回去</b>。那半句在替身引擎下
 * <b>根本观察不到</b>（谁调的 {@code resetToDefault} 不可见），所以它搬到了这里：
 * 走<b>真控制器 + 真引擎 + 真配置面</b>，跑一轮，再看配置面是不是原样。
 *
 * <h2>【钉什么】</h2>
 * <ol>
 *   <li>{@code POST /config} 写进一份<b>非默认</b>配置（防护只留 {@code rateLimit}、注入
 *       {@code redisDown}）⇒ {@code IsDefault == false}（不成立的先验会让本用例变成空转）；</li>
 *   <li>{@code GET /config} 取一份基线；</li>
 *   <li>打一发 {@code POST /run}；</li>
 *   <li>再 {@code GET /config} ⇒ <b>整份响应逐字相同</b>（{@code Protections} / {@code Injections} /
 *       {@code IsDefault} / {@code Version} 一个都不许动）。
 *       ⚠️ 比的是<b>整串</b>而不是逐键：漏掉的键（将来新增的）也一起被钉住。</li>
 * </ol>
 *
 * <h2>【⚠️ 不钉什么 —— 这条边界比断言本身重要】</h2>
 * <ul>
 *   <li><b>不钉「演练跑得起来」</b>：切片里 {@code JdbcTemplate} 是替身（见 {@link AbstractSmokeTest}），
 *       {@code DrillTargetDao} 的查询取不到真目标 ⇒ 这一轮多半<b>在引擎真正派发之前就失败</b>。
 *       本用例<b>故意不断 {@code /run} 的结果</b>（成败都放行）—— 它钉的是「跑不放回去」，
 *       不是「跑得出结果」。</li>
 *   <li><b>因此它盖不到引擎最深处</b>：能钉住的是「从 {@code /run} 入口到<b>本轮实际走完的那一段</b>
 *       路径上没有写回」。若将来有人在「<b>成功跑完那一刻</b>」去写回配置面，这里<b>仍然是绿的</b> ——
 *       那一段的哨兵只能是<b>阶段 4 的真演练</b>（两跑之间读 {@code GET /config} 的
 *       {@code Version} / {@code IsDefault}，留证据）。⚠️ 别把本用例读成「不复位已被测试证明」。</li>
 *   <li><b>不钉乙案的其它面</b>：{@code /config} 的写入语义（{@code null} vs {@code []}、未知键 400、
 *       拒写不留半份）归 {@code AttackSwitchConfigTest}（单测，9 条）；旧入参自拒归
 *       {@code AttackEngineTest}。</li>
 * </ul>
 *
 * <h2>【静态不变量（同一裁定的 (b) 条）】</h2>
 * 全仓 {@code resetToDefault()} / {@code update(...)} 的调用点只允许 {@code AttackController}
 * （现可核、0 例外）。本用例钉的是<b>行为</b>，那条钉的是<b>调用点数量</b> —— 两者互补：
 * 多一个调用点未必当场红，但一定会在本用例或阶段 4 里露出来。出处见 {@code api-contract.md} §I.3。
 */
class AttackConfigNoResetSmokeTest extends AbstractSmokeTest {

    /** 非默认配置：防护只留 {@code rateLimit}；注入开 {@code redisDown}。 */
    private static final String NON_DEFAULT_PROTECTIONS = "[\"rateLimit\"]";
    private static final String NON_DEFAULT_INJECTIONS = "[\"redisDown\"]";

    @Test
    void sharedSwitchConfigSurvivesARun() throws Exception {
        // ① 写成非默认 —— 先验不成立（写没生效）时本用例会当场红，而不是空转成绿。
        // ⚠️ 本域两条 `/config` 出口的响应体里**没有 `success` 键**：控制器用的是 `Result.of(...)`，
        //    而 `Result.of` 只逐键搬运、**不补** `success`（补的那个是 `Result.ok`，同域的 `/status`
        //    用的就是它）。⇒ 断言只认契约里写的那五个键。**别**顺手加 `$.success` 断言（会红），
        //    也**别**顺手把端点改成 `Result.ok`（改的是对外形状，得先议 —— 见 3a.1 报告 §九）。
        mockMvc.perform(post("/admin/observe/attack/config")
                        .param("protections", NON_DEFAULT_PROTECTIONS)
                        .param("injections", NON_DEFAULT_INJECTIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.IsDefault").value(false))
                .andExpect(jsonPath("$.Protections.length()").value(1))
                .andExpect(jsonPath("$.Protections[0]").value("rateLimit"))
                .andExpect(jsonPath("$.Injections[0]").value("redisDown"));

        // ② 基线：整份响应串（含 Version）。
        String before = mockMvc.perform(get("/admin/observe/attack/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.IsDefault").value(false))
                .andReturn().getResponse().getContentAsString();

        // ③ 跑一轮。⚠️ 结果不断 —— 切片里取不到真目标，这一轮多半在派发前就失败；
        //    本用例钉的是「跑不放回去」，不是「跑得起来」。
        mockMvc.perform(post("/admin/observe/attack/run")
                .param("name", "3a.1 哨兵")
                .param("concurrency", "1")
                .param("durationSeconds", "1"));

        // ④ 原样？
        String after = mockMvc.perform(get("/admin/observe/attack/config"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(before, after,
                "一轮演练把共享开关配置改了 —— 「跑完不复位」这条口径被谁写回破坏了："
                        + "全仓 resetToDefault()/update(...) 的调用点只允许 AttackController");
    }
}
