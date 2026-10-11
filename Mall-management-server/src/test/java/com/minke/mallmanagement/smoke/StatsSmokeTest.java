package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可视化段 commit 2 · 经营数据看板的 smoke test：
 * {@code POST /admin/stats} · {@code POST /admin/stats/rebuild} · {@code POST /merchant/stats} 的**路由与入参闸**。
 *
 * <h2>【本类钉的是什么 —— 以及**不**钉什么（这条边界比断言本身重要）】</h2>
 * <b>钉的三件事</b>：
 * <ol>
 *   <li>{@code /merchant/stats} 的空 {@code MerchantId} 拒绝 —— 这条是真的：它在
 *       {@code StatsService.merchantStats} 的**第一行**返回，**一次库都不碰**。</li>
 *   <li>两条 {@code /admin/stats*} 路由**真的挂上了 POST**（而不是 404）—— 见下条为什么只能这么断。</li>
 *   <li>端点内部抛出的异常**不会逃逸成 500**：{@code StatsService} 两个读方法整段 try/catch，
 *       故即便替身让库调用炸掉，响应仍是 <b>HTTP 200 + {@code success:false}</b> 的契约形状。</li>
 * </ol>
 *
 * <p>⚠️ <b>本类**不**覆盖任何真读写的路径</b>，而且这里有一个**必须写下来的坑**：
 * 切片里 {@code JdbcTemplate} 是 {@code @MockitoBean}（见 {@link AbstractSmokeTest}），
 * 它的每个方法默认返回 {@code null} ⇒ {@code StatsDao} 的查询会抛 NPE、落进 service 的 catch，
 * 返回值恒为 {@code {"success":false,"msg":"经营数据读取失败：…"}}。
 * 于是「断言 {@code success == false}」看起来在测「读失败时的降级」，**其实在测 Mockito 的默认返回值** ——
 * 它在我们把 service 改对/改错时**都**是绿的。故本类**只断 {@code success} 这个键存在**，
 * 不断它的值。真正的聚合正确性（日切 / GMV 的 float 转型 / 两口径不可加）归：
 * <ul>
 *   <li><b>默认 profile 手验</b>：{@code POST /admin/stats/rebuild} 造数后与
 *       {@code _work/vis-commit2-expected.md} 的逐日表逐格比；</li>
 *   <li><b>A3</b>：P3 表数门禁（{@code 11 + 10 = 21}）。⚠️ {@code concurrency} job 是
 *       {@code continue-on-error} ⇒ **按 artifact 的 {@code gates.count} 读，别读 job 颜色**。</li>
 * </ul>
 *
 * <p>⚠️ 每一类只钉**一条**用例（同 {@code CartSmokeTest} 的口径）：空 {@code MerchantId} 与
 * 全空白 {@code MerchantId} 走的是**同一个** {@code isBlank} 闸，多写一条只是在重复证同一件事。
 */
class StatsSmokeTest extends AbstractSmokeTest {

    /**
     * 空 {@code MerchantId} ⇒ 在**碰库之前**就拒。
     *
     * <p>⚠️ 断言串与 {@code StatsService.merchantStats} 里那句 {@code fail("缺少参数：MerchantId")}
     * 是**两个地方** —— 谁改了一处而没改另一处，本用例会红（这正是想要的：它是**对外文案**，
     * 前端按 {@code msg} 提示用户，不是一个能随手调的内部字符串）。
     */
    @Test
    void merchantStatsWithBlankMerchantIdIsRejected() throws Exception {
        mockMvc.perform(post("/merchant/stats").param("MerchantId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：MerchantId"));
    }

    /**
     * 管理端读端点**挂上了 POST**（不是 404）。
     *
     * <p>⚠️ 只断 {@code success} 键**存在**（值不断）—— 理由见类注释：切片里它恒为 {@code false}，
     * 断值等于断言 Mockito 的默认行为。这条用例的价值是「路由 + 序列化契约通了」，
     * 不是「聚合对了」。
     */
    @Test
    void adminStatsRouteIsWired() throws Exception {
        mockMvc.perform(post("/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());
    }

    /** 重建端点同样只断「路由通了 + 异常没逃逸成 500」（理由同上）。 */
    @Test
    void adminStatsRebuildRouteIsWired() throws Exception {
        mockMvc.perform(post("/admin/stats/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());
    }
}
