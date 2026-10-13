package com.minke.mallmanagement.comment.service;

import com.minke.mallmanagement.comment.CommentKinds;
import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「一商品一用户只能评一条」的**第二道闸**（Migration ⑯ 的 {@code uk_comment_review_once}）
 * 在 Service 层的落地 —— 并发下撞到唯一键时必须翻成**业务话术**，且**不许外溢**到四类豁免对象。
 *
 * <h2>【为什么可以不起 Spring、不连库】</h2>
 * 本类验的是 {@code CommentService.add} 的两个**分支决策**：{@code DuplicateKeyException}
 * 翻不翻、四类豁免走不走得到 INSERT。DB 那道锁**真的在不在、锁的范围对不对**不在本文件射程
 * 内（本文件里根本没有 DB —— {@code insertComment} 是替身，想让它抛什么就抛什么）：
 * 那是 {@code scripts/probes/ProbeB6c7Lock.java} 的活（真库、裸 SQL、含两连接并发档），
 * 它的 §二 正是本文件 {@link #exemptShapesAreNotBlockedAtServiceLayer()} 的 DB 层对照。
 * 分层的理由同 {@code DistributedLockServiceTest}：把「状态机/分支对不对」与「外部设施真的
 * 按预期工作」分开，前者才跑得进每个 {@code mvn test}。
 *
 * <h2>【话术字面量为什么在测试里再写一遍】</h2>
 * {@code CommentService} 里那份 {@code MSG_DUPLICATE_REVIEW} 是 {@code private}
 * —— 本类**故意**不引它，就写 {@value #MSG_DUPLICATE} 这串字面量：它钉的是**用户可见契约**
 * （用户屏幕上那句话），不是内部常量名。改那句话必须让本文件一起红。
 * 同理 {@value #MSG_GENERIC} —— 未翻译时外层 {@code catch (Exception)} 会渲染成它。
 */
class CommentServiceReviewLockTest {

    /** 用户可见话术（两条闸共用同一句）。**故意写字面量**，理由见类注释。 */
    private static final String MSG_DUPLICATE = "您已评价过该商品，不能重复评价";

    /** {@code add} 外层兜底 catch 的前缀 —— 撞到唯一键而**没被翻译**时，用户会看到「评论发表失败：Duplicate entry …」。 */
    private static final String MSG_GENERIC = "评论发表失败";

    /** 测试靶：一个客户、一件商品 —— 全是字符串常量，不碰真库。 */
    private static final String AUTHOR = "1111";
    private static final String GOODS = "76";
    private static final String CUSTOMER = "CUST-1";

    /** 回复用的靶：父行 {@code Id}（替身里也只是个数字）。 */
    private static final long PARENT_REVIEW = 5L;
    private static final long PARENT_QUESTION = 7L;

    private final CommentDao dao = mock(CommentDao.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final SensitiveWordService sensitive = mock(SensitiveWordService.class);

    /**
     * ⚠️ 上传目录只用于**有图**的分支，本文件所有调用都不带图 ⇒ 这个值一次都不会被读。
     * 传的是 {@code java.io.tmpdir} 而不是写死的 Windows 路径，免得换个机器成为坑。
     */
    private final CommentService service = new CommentService(
            dao, notifications, sensitive, System.getProperty("java.io.tmpdir") + "/b6-review-lock-test");

    // ==================== 一、唯一键 ⇒ 业务话术 ====================

    @Test
    @DisplayName("并发下 DB 那道闸拦到 ⇒ 回「您已评价过该商品，不能重复评价」；**不是**「评论发表失败：Duplicate entry …」")
    void duplicateKeyFromTheDatabaseIsTranslated() {
        givenSensitivePasses();
        givenReviewEligibility(0);
        givenInsert().thenThrow(new DuplicateKeyException(
                "PreparedStatementCallback; SQL [INSERT INTO `comment` (`TargetType`, `TargetId`, …) SELECT …]; "
                        + "Duplicate entry 'goods:76:1111' for key 'comment.uk_comment_review_once'"));

        Map<String, Object> res = addReview();

        assertThat(res.get("success")).as("业务失败 = HTTP 200 + success:false（本仓口径）").isEqualTo(false);
        assertThat(String.valueOf(res.get("msg")))
                .as("两条闸（Service 预检 / DB 唯一键）共用同一句用户可见话术")
                .isEqualTo(MSG_DUPLICATE)
                // ⚠️ 下面四条是**反向判据**：翻译漏了的话，外层 catch 会把 DB 内部标识
                //    （键名、拼出来的键值、SQL）端到用户面前 —— 逐条钉死它们一个都不许出现。
                .doesNotContain(MSG_GENERIC)
                .doesNotContain("Duplicate entry")
                .doesNotContain("uk_comment_review_once")
                .doesNotContain("SQL");
    }

    @Test
    @DisplayName("预检先拦到（顺序流程）⇒ 同一句话术，且**一行都不插**")
    void precheckBlocksWithTheSameMessageAndNeverInserts() {
        givenSensitivePasses();
        givenReviewEligibility(1);

        Map<String, Object> res = addReview();

        assertThat(String.valueOf(res.get("msg")))
                .as("与 DB 撞键那条路**同一句话**（两处各写一遍字面量必然漂开）")
                .isEqualTo(MSG_DUPLICATE);
        verify(dao, never()).insertComment(anyString(), anyString(), any(), any(), anyString(), anyString(),
                anyInt(), anyBoolean(), anyInt(), anyString(), any(), anyBoolean(), any());
    }

    // ==================== 二、正例：证明上面两个用例不是「半路早退」蒙对的 ====================

    @Test
    @DisplayName("三道闸全过 ⇒ 真的走到 INSERT，Id 原样回（否则上面那条「没插」是空断言）")
    void happyPathReachesTheInsertAndReturnsTheNewId() {
        givenSensitivePasses();
        givenReviewEligibility(0);
        givenInsert().thenReturn(1);
        when(dao.lastInsertId()).thenReturn(7L);

        Map<String, Object> res = addReview();

        assertThat(res.get("success")).isEqualTo(true);
        assertThat(res.get("Id")).as("回体键名是 Id（不是 id）—— okAdded 的形状").isEqualTo(7L);
        assertThat(res.get("msg")).isEqualTo("评论已发表");
        verify(dao).insertComment(anyString(), anyString(), any(), any(), anyString(), anyString(),
                anyInt(), anyBoolean(), anyInt(), anyString(), any(), anyBoolean(), any());
    }

    // ==================== 三、不变项：四类豁免在 Service 层不能被任何第二道闸拦下 ====================

    /**
     * 回复 / 提问 / 回答 / 留言板 —— 同作者、同对象，**两条都得成功**。
     *
     * <p>⚠️ 本用例只证「Service 层没有对它们多一道闸」（{@code checkReviewEligible} 只认
     * 顶层商品评价）。**DB 那道锁也不锁它们**由 {@code ProbeB6c7Lock} §二 在真库上证
     * （同作者同对象直连插两条全成功）—— 两边合起来才是完整的「锁不外溢」。
     */
    @Test
    @DisplayName("不变项：回复 / 提问 / 回答 / 留言板 同作者同对象各插两条 —— 四条路都成功")
    void exemptShapesAreNotBlockedAtServiceLayer() {
        givenSensitivePasses();
        givenInsert().thenReturn(1);
        when(dao.lastInsertId()).thenReturn(101L);
        when(dao.selectComment(PARENT_REVIEW)).thenReturn(parentRow(PARENT_REVIEW, CommentKinds.REVIEW));
        when(dao.selectComment(PARENT_QUESTION)).thenReturn(parentRow(PARENT_QUESTION, CommentKinds.QUESTION));

        assertTwoBothSucceed("回复（ParentId ≠ NULL，Kind 由父亲推 = review）", () ->
                service.add(AUTHOR, CommentKinds.TARGET_GOODS, GOODS, String.valueOf(PARENT_REVIEW), null,
                        CommentKinds.REVIEW, "回复内容", "5", null, null, List.of(), null));

        assertTwoBothSucceed("提问（顶层 + Kind=question —— 不查「买过」、不限一条）", () ->
                service.add(AUTHOR, CommentKinds.TARGET_GOODS, GOODS, null, null,
                        CommentKinds.QUESTION, "提问内容", null, null, null, List.of(), null));

        assertTwoBothSucceed("回答（ParentId 指向一条提问，Kind 由父亲推 = answer）", () ->
                service.add(AUTHOR, CommentKinds.TARGET_GOODS, GOODS, String.valueOf(PARENT_QUESTION), null,
                        CommentKinds.ANSWER, "回答内容", null, null, null, List.of(), null));

        assertTwoBothSucceed("留言板（TargetType=user —— 没有「买过」可言，也没有唯一条数）", () ->
                service.add(AUTHOR, CommentKinds.TARGET_USER, "77", null, null,
                        CommentKinds.REVIEW, "留言内容", "5", null, null, List.of(), null));
    }

    // ==================== 桩与调用 ====================

    /** 敏感词闸放行：回「原样」，即 {@code masked == text}、{@code rejected == false}。 */
    private void givenSensitivePasses() {
        when(sensitive.filter(anyString())).thenAnswer(inv -> {
            Map<String, Object> m = new HashMap<>();
            m.put("rejected", false);
            m.put("masked", inv.getArgument(0));
            return m;
        });
    }

    /**
     * 「可评」的三道前置：身份认得出、买过、**已经评了几条**（{@code alreadyReviewed}）。
     * 只有**顶层商品评价**会走到这三道 —— 这与 {@code checkReviewEligible} 的作用域逐条对应。
     */
    private void givenReviewEligibility(int alreadyReviewed) {
        when(dao.selectRoleByUsername(AUTHOR)).thenReturn(CommentKinds.ROLE_CUSTOMER);
        when(dao.selectCustomerIdByUsername(AUTHOR)).thenReturn(CUSTOMER);
        when(dao.countPurchasedGoods(CUSTOMER, Long.parseLong(GOODS))).thenReturn(1);
        when(dao.countTopLevelByAuthor(CommentKinds.TARGET_GOODS, GOODS, CommentKinds.REVIEW, AUTHOR))
                .thenReturn(alreadyReviewed);
    }

    /** 顶层商品评价（本文件的主路）。 */
    private Map<String, Object> addReview() {
        return service.add(AUTHOR, CommentKinds.TARGET_GOODS, GOODS, null, null, CommentKinds.REVIEW,
                "这条是测试内容", "5", null, null, List.of(), null);
    }

    /**
     * {@code insertComment} 的桩口 —— 13 个形参**写一处**，免得每个用例各抄一遍。
     * ⚠️ 抄错一个 matcher 就配不到真调用，`thenThrow` 会成为空桩、用例静默走成功路径
     * ⇒ 本文件的正例（{@link #happyPathReachesTheInsertAndReturnsTheNewId()}）就是钉这一点的。
     * ⚠️ 末位 {@code Source}（C3 块 7 · commit B 加的第 13 个）用 {@code any()} 而**不是**
     * {@code anyString()}：本文件四个调用点都**不传**它 ⇒ 真值是 {@code null}，
     * {@code anyString()} **配不上 null**，桩会静默落空（那正是上面那句 ⚠️ 说的失效模式）。
     */
    private org.mockito.stubbing.OngoingStubbing<Integer> givenInsert() {
        return when(dao.insertComment(anyString(), anyString(), any(), any(), anyString(), anyString(),
                anyInt(), anyBoolean(), anyInt(), anyString(), any(), anyBoolean(), any()));
    }

    /** 直连的替身父行：{@code add} 的回复路径只读这四个键（TargetType / TargetId / Kind / AuthorId / ParentId / Status）。 */
    private static Map<String, Object> parentRow(long id, String kind) {
        Map<String, Object> row = new HashMap<>();
        row.put("Id", id);
        row.put("TargetType", CommentKinds.TARGET_GOODS);
        row.put("TargetId", GOODS);
        row.put("ParentId", null);
        row.put("Kind", kind);
        row.put("AuthorId", "someone");
        row.put("Status", CommentKinds.STATUS_NORMAL);
        return row;
    }

    private void assertTwoBothSucceed(String label, java.util.function.Supplier<Map<String, Object>> call) {
        assertThat(call.get()).as(label + " —— 第 1 条").containsEntry("success", true);
        assertThat(call.get()).as(label + " —— 第 2 条（同作者、同对象）").containsEntry("success", true);
    }
}
