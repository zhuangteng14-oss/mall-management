package com.minke.mallmanagement.shared.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不透明 token 内存表（多账户隔离笔，2026-10-06）的**纯单测** —— 不起 Spring 上下文
 * （{@code AuthTokenStore} 是 {@code final} + 静态方法的工具类，没有可注入的依赖）。
 *
 * <h2>【为什么这族用例必须是单测，而不是走 MockMvc】</h2>
 * 两件事只有单测能钉：
 * <ol>
 *   <li><b>30 分钟的过期与滑动续期</b>。走 HTTP 就只能真等 30 分钟，或者把 TTL 改成测试值
 *       —— 后者会让「生产用的那个数」没有可观测证据。本类用包级可见的
 *       {@link AuthTokenStore#resolveAt} 注入时钟，{@link AuthTokenStore#TTL_MILLIS} 一字不改。</li>
 *   <li><b>「每次 mint 都新铸」</b>。这是隔离的**全部诀窍**（见 {@link AuthTokenStore} 类注释）：
 *       同一身份连打两次必须得到两张都能用的票。HTTP 层看不出「是不是同一张」以外的区别。</li>
 * </ol>
 *
 * <h2>【为什么每条用例前清表】</h2>
 * {@code LIVE} 是 {@code static} ⇒ 整条 JVM 生命周期共享，用例之间会互相污染
 * （尤其 {@code liveCount()} 这类计数断言）。{@link AuthTokenStore#clearForTest()} 就是为它存在的。
 * ⚠️ 反过来：本类是**唯一**该调 {@code clearForTest} 的地方（生产代码调它 = 把所有人登出）。
 *
 * <h2>【时间取值的写法：先取 t0、再 mint】</h2>
 * {@code mint} 内部自己取 {@code System.currentTimeMillis()}，本类拿不到那个数 ⇒ 先取 {@code t0}、
 * 再 mint，于是 {@code mint 时刻 ∈ [t0, t0+ε]}（ε 是两次调用之间的真实耗时，微秒级）。
 * 断言据此留出余量：过期用例往后推**整整一分钟**，而不是贴着 TTL 那条线 ——
 * 贴线写会变成偶发红（机器卡顿时 ε 变大就翻转）。
 */
class AuthTokenStoreTest {

    /** 令牌里的业务号与角色（值本身无意义，只要 mint 与 resolve 一致）。 */
    private static final String USER = "C-1";

    @BeforeEach
    void clearTheSharedTable() {
        AuthTokenStore.clearForTest();
    }

    // ==================== 一、铸票与解析 ====================

    /**
     * {@code mint → resolve} 拿回**同一个** {@code userId} 与 {@code role} —— 这是本表唯一的功能。
     *
     * <p>⚠️ {@code role} 单独断言：它是多账户隔离笔新加进记录里的字段（在此之前身份只有
     * {@code userId}），而管理员豁免（{@link IdentityGuard} 第 2 档）**只**认它。
     */
    @Test
    @DisplayName("mint → resolve 拿回同一 userId 与 role")
    void mintThenResolve_returnsTheSameIdentity() {
        String token = AuthTokenStore.mint(USER, 2);

        assertThat(token).as("Base64URL 无填充的 32 字节 = 43 字符")
                .hasSize(43);
        assertThat(token).as("URL 安全字符集：不能被 '+' / '/' / '=' 污染（它会进 Authorization 头）")
                .matches("[A-Za-z0-9_-]+");

        AuthTokenStore.Grant grant = AuthTokenStore.resolve(token);
        assertThat(grant).as("刚铸的票必须认得出").isNotNull();
        assertThat(grant.userId()).isEqualTo(USER);
        assertThat(grant.role()).isEqualTo(2);
    }

    /**
     * ⚠️ <b>本类最重要的一条</b>：同一身份连铸两次得到**两张不同的、都能用的**票。
     *
     * <p>判据是「不同」而不是「相同」：本表刻意**不回发**既有 token —— 若有人把 {@code mint}
     * 改成「同一 userId 复用同一张票」（这在直觉上很像优化），两个标签页就会共享同一个身份，
     * 「一标签页一票」的隔离当场失效，而**别的用例全都照样绿**（它们只测单张票的行为）。
     */
    @Test
    @DisplayName("同一身份连铸两次 = 两张不同的、都能用的票（永不回发）")
    void mintTwice_producesTwoDistinctLiveTokens() {
        String first = AuthTokenStore.mint(USER, 1);
        String second = AuthTokenStore.mint(USER, 1);

        assertThat(second).as("回发既有 token = 隔离失效，见类注释").isNotEqualTo(first);
        assertThat(AuthTokenStore.resolve(first)).as("第一张仍必须有效（后铸的不销前面的）").isNotNull();
        assertThat(AuthTokenStore.resolve(second)).isNotNull();
        assertThat(AuthTokenStore.liveCount()).as("两条互不相干的记录").isEqualTo(2);
    }

    /** 伪造 / 手滑 / 空值一律答「不认识」—— 判据是 {@code null}，不是抛异常。 */
    @Test
    @DisplayName("不认识的 token ⇒ null（含 null 入参）")
    void unknownTokenResolvesToNull() {
        assertThat(AuthTokenStore.resolve(null)).isNull();
        assertThat(AuthTokenStore.resolve("")).as("空串也是不认识的票，不是「匿名」").isNull();
        assertThat(AuthTokenStore.resolve("not-a-real-token")).isNull();

        AuthTokenStore.mint(USER, 1);
        assertThat(AuthTokenStore.resolve("not-a-real-token")).as("表里有别的票不影响判定").isNull();
    }

    // ==================== 二、有效期（滑动 30 分钟）====================

    /**
     * 边界取 {@code ≥ TTL} 即过期：{@code TTL - 1} 毫秒仍有效，{@code TTL} 整毫秒起失效。
     *
     * <p>⚠️ 「{@code ≥}」而不是「{@code >}」是刻意钉的：真实过期时刻落在哪一边无所谓，
     * 但 {@code resolveAt} 与 {@code purgeExpiredAt} 两处**必须逐字取齐**
     * （{@link AuthTokenStore} 类注释写了这条），否则会出现「懒清除留下的项被判有效」这类缝。
     */
    @Test
    @DisplayName("过期边界：TTL-1 仍有效，TTL 起失效（判据 ≥）")
    void expiryBoundary_isInclusiveAtTtl() {
        long t0 = System.currentTimeMillis();
        // ⚠️ 必须**两张票**、各用一次：resolveAt 命中时会**滑动续期**，同一张票的第二次解析
        //    是从第一次那个时刻起算的 ⇒ 拿它去测过期永远测不出来（本笔第一次跑就是这样红的，
        //    第二次解析离上次只隔 60 秒，远没有 TTL）。「一张票一个时刻」是本文件的通用纪律。
        String justInsideTtl = AuthTokenStore.mint(USER, 1);
        String justOutsideTtl = AuthTokenStore.mint(USER, 1);

        assertThat(AuthTokenStore.resolveAt(justInsideTtl, t0 + AuthTokenStore.TTL_MILLIS - 1))
                .as("差 1 毫秒还没过期").isNotNull();
        assertThat(AuthTokenStore.resolveAt(justOutsideTtl, t0 + AuthTokenStore.TTL_MILLIS + 60_000))
                .as("往后推一分钟必然过期（不贴着 TTL 那条线写，免得偶发红）").isNull();
    }

    /**
     * **滑动**续期：每次命中都把 {@code lastAccessAt} 推到当下 ⇒ 持续使用的票不会中途失效。
     *
     * <p>⚠️ 断言取「{@code t0 + 2×TTL} 那次仍有效」——若没有续期，它在第一次命中时就该被判过期。
     * 反过来，本条**不**意味着票可以无限续（它只是被反复推后），停用满 TTL 后照旧失效
     * （那由上面那条边界用例守）。
     */
    @Test
    @DisplayName("滑动续期：每次命中把有效期推后 30 分钟")
    void resolveSlidesTheExpiry() {
        long t0 = System.currentTimeMillis();
        String token = AuthTokenStore.mint(USER, 1);

        long nearExpiry = t0 + AuthTokenStore.TTL_MILLIS - 1_000;
        assertThat(AuthTokenStore.resolveAt(token, nearExpiry)).as("第一次命中（此时已接近过期）").isNotNull();
        assertThat(AuthTokenStore.resolveAt(token, nearExpiry + AuthTokenStore.TTL_MILLIS - 1_000))
                .as("从**上次命中**起再算 30 分钟仍有效 —— 没有续期的话这里必是 null")
                .isNotNull();
    }

    /**
     * 过期项**懒清除**：{@code resolve} 命中过期项时就地删掉它（不是只答 {@code null} 留着占表）。
     *
     * <p>⚠️ 判据是 {@code liveCount()} —— 只看返回值的话，「删了」与「没删但答 null」两种实现
     * 完全无法区分，而后者会让表永不清空（本笔明确不做后台清理线程，见类注释）。
     */
    @Test
    @DisplayName("resolve 命中过期项时就地删掉它（懒清除）")
    void resolveOfAnExpiredTokenAlsoRemovesIt() {
        long t0 = System.currentTimeMillis();
        String token = AuthTokenStore.mint(USER, 1);
        assertThat(AuthTokenStore.liveCount()).isEqualTo(1);

        assertThat(AuthTokenStore.resolveAt(token, t0 + AuthTokenStore.TTL_MILLIS + 60_000)).isNull();
        assertThat(AuthTokenStore.liveCount()).as("过期项被就地删掉，不是留着占表").isZero();
    }

    /**
     * {@code mint} 顺带清扫过期项（本笔**没有**后台清理线程，见类注释）。
     *
     * <p>手法：{@code mintAt} 把第一张票铸在 {@code t0}、第二张铸在 {@code t0 + TTL + 1}
     * ⇒ 第二次 mint 的那次清扫应把第一张扫掉。判据取 {@code liveCount()} 而不是「resolve 到 null」：
     * 后者在「留着但判过期」的实现下同样成立，分不开「清了」与「没清」。
     *
     * <p>⚠️ 本用例是 {@code mintAt} 这个测试钩子存在的**唯一**理由 —— 真实时钟下这张票
     * 永远等不到过期（30 分钟）。别把它换回 {@code mint} + {@code sleep}。
     */
    @Test
    @DisplayName("mint 顺带清扫过期项（不清未过期的）")
    void mintPurgesExpiredEntries() {
        long t0 = 1_000_000L;
        String stale = AuthTokenStore.mintAt(USER, 1, t0);
        assertThat(AuthTokenStore.liveCount()).isEqualTo(1);

        AuthTokenStore.mintAt("C-2", 1, t0 + AuthTokenStore.TTL_MILLIS + 1);

        assertThat(AuthTokenStore.liveCount()).as("过期的那张被顺带清掉，新铸的留下").isEqualTo(1);
        assertThat(AuthTokenStore.resolve(stale)).as("被清掉的票不再认得（表里确实没有了）").isNull();
    }

    // ==================== 三、销票 ====================

    /**
     * {@code revoke} 之后票不认了，且**幂等**（销第二遍静默返回）—— 登出走它。
     *
     * <p>幂等这条不是修辞：{@code AuthSession.logout} 先销 token 再 {@code invalidate} 会话，
     * 而登出会被调用**任意多次**（前端 fail-open，见 {@code UserCenter.vue}），
     * 销一张已经没了的票不该有任何后果。
     */
    @Test
    @DisplayName("revoke 之后票失效，且重复 revoke / revoke(null) 幂等")
    void revokeIsIdempotent() {
        String token = AuthTokenStore.mint(USER, 1);
        assertThat(AuthTokenStore.resolve(token)).isNotNull();

        AuthTokenStore.revoke(token);
        assertThat(AuthTokenStore.resolve(token)).isNull();
        assertThat(AuthTokenStore.liveCount()).isZero();

        AuthTokenStore.revoke(token);   // 再销一遍：不抛异常
        AuthTokenStore.revoke(null);    // 没有票（未登录登出）：同样静默
        assertThat(AuthTokenStore.liveCount()).isZero();
    }

    /** 只销指定的那一张 —— 别的标签页手里的票**不受影响**（隔离的第二个落点）。 */
    @Test
    @DisplayName("revoke 只销指定那张，不误伤别的")
    void revokeOnlyAffectsTheGivenToken() {
        String mine = AuthTokenStore.mint("C-1", 1);
        String otherTab = AuthTokenStore.mint("C-2", 1);

        AuthTokenStore.revoke(mine);

        assertThat(AuthTokenStore.resolve(mine)).isNull();
        assertThat(AuthTokenStore.resolve(otherTab)).as("别的标签页的票不是本页的凭据，替它销 = 全家掉线").isNotNull();
    }

    // ==================== 四、按身份全销（TD-18，2026-10-20）====================

    /**
     * {@code revokeAll(userId)} 必须把所有标签页里该身份的票**一次销光** —— 封禁、改密
     * 走的就是这条口子（只销当前这一张等于「踢了本标签页、别的标签页照常工作」）。
     */
    @Test
    @DisplayName("revokeAll 按身份销光全部票，不误伤别的身份")
    void revokeAllRevokesEveryTokenOfThatUserOnly() {
        String tabA = AuthTokenStore.mint("C-1", 1);
        String tabB = AuthTokenStore.mint("C-1", 1);
        String other = AuthTokenStore.mint("C-2", 1);

        AuthTokenStore.revokeAll("C-1");

        assertThat(AuthTokenStore.resolve(tabA)).as("同身份的另一张票也必须失效").isNull();
        assertThat(AuthTokenStore.resolve(tabB)).isNull();
        assertThat(AuthTokenStore.resolve(other)).as("别的身份不受影响").isNotNull();
        assertThat(AuthTokenStore.liveCount()).isEqualTo(1);
    }

    /** 没有票的身份、{@code null} 入参：静默无副作用（与 {@code revoke} 同口径）。 */
    @Test
    @DisplayName("revokeAll 对无票身份 / null 幂等")
    void revokeAllIsIdempotent() {
        AuthTokenStore.revokeAll("NO-SUCH-USER");
        AuthTokenStore.revokeAll(null);
        assertThat(AuthTokenStore.liveCount()).isZero();
    }
}
