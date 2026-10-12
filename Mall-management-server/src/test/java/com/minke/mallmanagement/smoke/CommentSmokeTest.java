package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C3 块 6 · 内容域（commit 1「评论」）的 smoke test：{@code POST /comment/*} 五个端点的**入参闸**。
 *
 * <h2>【本类钉的是什么 —— 以及**不**钉什么】</h2>
 * 钉的是**碰库之前**就能判定的拒绝路径：缺 {@code TargetId} / 未知 {@code TargetType} /
 * 未知 {@code Kind} / 未知 {@code Sort} / 空正文 / 正文超长 / 缺 {@code Id} /
 * 未知 {@code Type} / 缺 {@code Account}。它们全部在 {@code CommentService} 的**第一段**
 * （或 {@code CommentController} 的入参检查）返回，**一次库都不碰** ⇒ 用
 * {@code @MockitoBean JdbcTemplate} 的切片就能真跑（同 {@code CartSmokeTest} 的理由）。
 *
 * <p>⚠️ <b>本类**不**覆盖任何真读写路径</b>，且这条边界对本域尤其重要 —— 本域的**核心规则
 * 全在库里**：买过没有（{@code order_item} 回联 {@code goods}）、是不是重复评价
 * （{@code countTopLevelByAuthor}）、匿名行会不会漏 {@code AuthorId}、商家首次回复置不置顶、
 * 一人一条的赞踩。这些一律要真库，{@code JdbcClient} 打到被 mock 的 {@code JdbcTemplate} 上
 * 只会抛 NPE，落进 catch 变成一句「…失败：…」文案 ⇒ 断言它们等于在断言 Mockito 的默认行为。
 * 那几条的验收归 A3（{@code concurrency}，**continue-on-error ⇒ 按 artifact 的
 * {@code gates.count} 读，别读 job 颜色**）与 VM 上的手工验证。
 *
 * <p>⚠️ 另一条本域特有的边界：{@code CommentService.add} 上的 {@code @Transactional}
 * 在切片里是 **no-op**（无 DataSource ⇒ 无 {@code TransactionManager}）⇒
 * 「{@code INSERT} 与 {@code LAST_INSERT_ID()} 是否同一条连接」本类测不了，归真库侧。
 */
class CommentSmokeTest extends AbstractSmokeTest {

    // ==================== /comment/list（公开端点，无身份闸）====================

    /** 缺 {@code TargetId} ⇒ 在碰库之前拒（否则会变成一次全表扫描）。 */
    @Test
    void listWithoutTargetIdIsRejected() throws Exception {
        mockMvc.perform(post("/comment/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：TargetId"));
    }

    /** 未知 {@code TargetType} ⇒ 拒。⚠️ 读侧**不**静默当成 {@code goods}（那会把一次拼错变成空列表）。 */
    @Test
    void listWithUnknownTargetTypeIsRejected() throws Exception {
        mockMvc.perform(post("/comment/list")
                        .param("TargetType", "good")
                        .param("TargetId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("未知的评论对象类型：good（只能是 goods / user）"));
    }

    /** 未知 {@code Kind} ⇒ 拒（同 {@code CouponService} 对 {@code Status} 的处置：不静默当「不筛」）。 */
    @Test
    void listWithUnknownKindIsRejected() throws Exception {
        mockMvc.perform(post("/comment/list")
                        .param("TargetId", "1")
                        .param("Kind", "complaint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("未知的评论类型：complaint（只能是 review / question / answer）"));
    }

    /** 未知 {@code Sort} ⇒ 拒。⚠️ 这条同时是一道**注入面**的闸：{@code Sort} 会参与 {@code ORDER BY} 拼串。 */
    @Test
    void listWithUnknownSortIsRejected() throws Exception {
        mockMvc.perform(post("/comment/list")
                        .param("TargetId", "1")
                        .param("Sort", "LikeCount DESC; DROP TABLE comment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("未知的排序方式：LikeCount DESC; DROP TABLE comment（只能是 hot / new）"));
    }

    // ==================== /comment/add ====================

    /** 空正文 ⇒ 拒（第一步就拒，免得白跑一趟买过判定）。 */
    @Test
    void addWithBlankContentIsRejected() throws Exception {
        mockMvc.perform(post("/comment/add")
                        .param("Account", "smoke-user")
                        .param("TargetId", "1")
                        .param("Content", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("评论内容不能为空"));
    }

    /**
     * 正文超长 ⇒ 拒。
     *
     * <p>⚠️ 断言串里的 {@code 1000} / {@code 1001} 与 {@code CommentService.MAX_CONTENT}
     * **是两个地方** —— 谁改了常量而没改这里，本用例会红。那是想要的：{@code Content} 是
     * {@code varchar(1000)}，这个数是**库里那一列的长度**，不是能随手调的内部参数。
     */
    @Test
    void addWithOverlongContentIsRejected() throws Exception {
        mockMvc.perform(post("/comment/add")
                        .param("Account", "smoke-user")
                        .param("TargetId", "1")
                        .param("Content", "x".repeat(1001)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("评论内容最多 1000 字（当前 1001 字）"));
    }

    /** 缺 {@code Account} ⇒ 拒（身份判据的入参是它；没它就说不清是谁发的）。 */
    @Test
    void addWithoutAccountIsRejected() throws Exception {
        mockMvc.perform(post("/comment/add")
                        .param("TargetId", "1")
                        .param("Content", "很好"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：Account"));
    }

    /** 非数字的 {@code ParentId} ⇒ 拒。⚠️ 收成 {@code String} 再由 Service 解析是**刻意**的：
     *  {@code @RequestParam Integer} 遇到 {@code "abc"} 会抛类型转换异常 → 400 空体，
     *  而本仓的口径是「业务失败 = 200 + {@code success:false} + 说得清的话」。 */
    @Test
    void addWithNonNumericParentIdIsRejected() throws Exception {
        mockMvc.perform(post("/comment/add")
                        .param("Account", "smoke-user")
                        .param("TargetId", "1")
                        .param("ParentId", "abc")
                        .param("Content", "回复"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("ParentId 必须是数字：abc"));
    }

    // ==================== /comment/delete ====================

    /** 缺 {@code Id} ⇒ 在 Controller 一层就拒（不劳 Service）。 */
    @Test
    void deleteWithoutIdIsRejected() throws Exception {
        mockMvc.perform(post("/comment/delete")
                        .param("Account", "smoke-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：Id"));
    }

    // ==================== /comment/like ====================

    /** 缺 {@code Id} ⇒ 拒。 */
    @Test
    void likeWithoutIdIsRejected() throws Exception {
        mockMvc.perform(post("/comment/like")
                        .param("Account", "smoke-user")
                        .param("Type", "like"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：Id"));
    }

    /**
     * 未知 {@code Type} ⇒ 拒。
     *
     * <p>⚠️ {@code Type} **必填**（不接受「省略 = 切换」这种隐式语义）：隐式语义在前端
     * 重试/重复点击时会翻成反效果 —— 用户以为在点赞，实际把上一次的赞取消了。
     * 「再点同一种 = 取消」也走**显式**传同一个值这条路。
     */
    @Test
    void likeWithUnknownTypeIsRejected() throws Exception {
        mockMvc.perform(post("/comment/like")
                        .param("Account", "smoke-user")
                        .param("Id", "1")
                        .param("Type", "love"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：Type（只能是 like / dislike）"));
    }

    // ==================== /comment/myOrders ====================

    /** 缺 {@code Account} ⇒ 拒（查的是「谁的」历史订单，没它就无从查起）。 */
    @Test
    void myOrdersWithoutAccountIsRejected() throws Exception {
        mockMvc.perform(post("/comment/myOrders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("缺少参数：Account"));
    }
}
