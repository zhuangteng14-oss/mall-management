package com.minke.mallmanagement.goods.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@code goodstype} 唯一索引与 {@code GoodsTypeCacheService.addType} 的 catch 拆分的<b>确定性</b>用例。
 *
 * <p>⚠️ <b>C3 块 3（2026-10-10）唯一键换代</b>：本类原钉的是 #11 的 {@code uk_goodstype_GoodType}
 * （{@code (GoodType)} 单列）。商品块要支持「不同父级下同名子类目」，单列唯一键会误拦 ⇒
 * 已由 Migration 块换成 {@code uk_goodstype_parent_name}（{@code (ParentId, GoodType)}）。
 * <b>本类随之改的是索引名与 {@code addType} 的三参签名，判据的形状不变。</b>
 * ⚠️ 换代带来一条新语义必须一并钉住：<b>同父级</b>重名才拦，<b>不同父级</b>同名放行（用例 4）。
 *
 * <h2>本类钉的是什么</h2>
 * #11 这个笔改了两件事，两者都得有可复核的证据，而不是「代码看起来对」：
 * <ol>
 *   <li><b>DB 对象真的在</b>：{@code uk_goodstype_parent_name} 确实建在库里、且真的会拦重复 ——
 *       用例 2 绕过应用层查重裸插两次，撞的就是它（并断到索引名本身）；</li>
 *   <li><b>catch 拆分真的生效</b>：加了索引之后，并发输的那一方会拿到
 *       {@link DuplicateKeyException}，它必须被渲染成「类型已存在」<b>而不是</b>「新增失败」——
 *       用例 3 钉这一条。</li>
 * </ol>
 *
 * <h2>为什么是确定性用例，而不是并发用例</h2>
 * 并发复现是 A3 的 B-2 场景的职责（同一 {@code GoodType} × 20 并发）。
 * 在单测里再跑一遍线程池，只会得到「又慢又不稳」的第二份证据，且失败时无法区分
 * 「代码错了」与「这轮没撞上」。故本类三条用例全部是**单线程、可复现**的：
 * 用「先把对手的行造出来 + 让应用层查重谎报 0」（用例 3）把并发里那一瞬间**摆出来**，
 * 而不是去赌它。
 *
 * <h2>为什么必须挂 {@code A3_ENABLED}</h2>
 * 三条用例都要真 MySQL（要的正是「库里的索引」这个 DB 对象，替身给不了）。
 * CI 的 {@code backend} job 没有 MySQL，不挂开关会让它变红 —— 与
 * {@code MallManagementApplicationTests} / {@code LockSceneRedisTest} 同一条做法。
 * 代价是 {@code backend} job 会跳过本类；覆盖不减，因为 {@code concurrency} job 设了
 * {@code A3_ENABLED}，本类在那里会跑。
 *
 * <h2>⚠️ 本类不能替代 A3 的验收判据</h2>
 * 「修好了」的<b>主判据</b>是 A3 报告里 {@code b2PerRound[].goodstypeDupRows} 由
 * 4/1/4/3/3 变成 5 轮恒 = 1。⚠️ <b>不要</b>拿聚合断言 {@code A-B2-1} 当门禁：它 CI 里
 * {@code gate:false}，且它的 signal 是「login5xx ‖ gtRows≥2 ‖ frRows≥2」的**或**关系，
 * login5xx 每轮 5–9 非零 ⇒ 本笔改完它<b>一动不动</b>。本类补的是「机制在不在」这一层的
 * 可复核证据，不是「并发下不再复现」那一层。
 */
@EnabledIfEnvironmentVariable(named = "A3_ENABLED", matches = "true")
@SpringBootTest
class GoodsTypeUniqueIndexTest {

    /** schema 限定名照抄被测代码（TD-13：项目 SQL 硬编码 {@code mall_management_sql.}，不能省）。 */
    private static final String TABLE = "mall_management_sql.goodstype";

    @Autowired
    private GoodsTypeCacheService service;

    /**
     * ⚠️ 用 Spy 而非真 bean：<b>只有用例 3</b> 需要它 —— 该用例要「让应用层的 SELECT 查重谎报 0」，
     * 好把执行路径推进到 INSERT，即复现「并发下两个请求同时穿过查重」的那一刻。
     * 不做这件事，catch 分支就只能靠多线程去撞。
     *
     * <p>除这一处 stub 外，所有调用一律回落到真 JdbcTemplate（即真库）。
     */
    @MockitoSpyBean
    private JdbcTemplate jdbcTemplate;

    /**
     * 每例一个独立的 {@code GoodType}，使三例互不干扰、且清理只删自己那行。
     * ⚠️ 列声明是 {@code CHAR(20)}（{@code mall_management_sql.sql:77}）—— 名字必须 ≤ 20 字符。
     */
    private String gt;

    @BeforeEach
    void freshType() {
        gt = "A3UT" + (System.nanoTime() % 100_000_000L);   // 12 字符，稳落在 CHAR(20) 内
        cleanup();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE GoodType = ?", gt);
    }

    /**
     * 用例 1：顺序连插两次。
     *
     * <p>⚠️ 第二次走的是<b>上方 {@code SELECT COUNT(*) }查重分支</b>，不是 catch ——
     * catch 分支的确定性用例是 {@link #concurrencyLoserFallsIntoCatchBranchNotGenericFailure()}。
     * 本用例钉的是「响应文案」这条对外契约（同一业务事实、两条路径一个说法），以及
     * 「重复插入不会真的写进第二行」。
     */
    @Test
    @DisplayName("用例 1：同 ParentId 下同 GoodType 顺序连插两次 ⇒ 第二次 success:false /「类型已存在」，且只留 1 行")
    void sequentialDoubleInsertReturnsAlreadyExists() {
        Map<String, Object> first = service.addType(gt, 1, null);
        assertThat(first).containsEntry("success", true).containsEntry("msg", "新增成功");
        assertThat(countRows()).isEqualTo(1);

        Map<String, Object> second = service.addType(gt, 1, null);
        assertThat(second).containsEntry("success", false).containsEntry("msg", "类型已存在");
        assertThat(countRows()).isEqualTo(1);
    }

    /**
     * 用例 2：绕过应用层查重，裸 INSERT 两次 ⇒ 第二次抛 {@link DuplicateKeyException}。
     *
     * <p>这才是「<b>最后防线真的在</b>」的可复核证据：它一次也不经过
     * {@code GoodsTypeCacheService}，因此排除「应用层查重把重复拦下了」这种解释 ——
     * 拦下它的只可能是库里的唯一索引。
     *
     * <p>断言里带上<b>索引名</b>（而不是只断言「抛了 DuplicateKeyException」）：
     * 否则抛出的也可能是别的约束，证据链就断了。
     */
    @Test
    @DisplayName("用例 2：绕过应用层查重裸插两次 ⇒ 第二次撞 uk_goodstype_parent_name 抛 DuplicateKeyException")
    void rawSecondInsertHitsUniqueIndex() {
        insertRaw();

        assertThatThrownBy(this::insertRaw)
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_goodstype_parent_name");
        assertThat(countRows()).isEqualTo(1);
    }

    /**
     * 用例 4：**换代的核心语义** —— 不同父级下可以同名，同父级下同名才拦。
     *
     * <p>这正是块 3 换代要买到的东西：旧单列键 {@code (GoodType)} 会把「一级类目『手机』下挂
     * 子类目『旗舰』」与「一级类目『电视』下挂子类目『旗舰』」误判为重复。换代后两者都能建。
     *
     * <p>做法：先用一个确定的一级父 Id（0 = 一级）建一行，再用**另一个父 Id** 建同名行 ⇒
     * 必须 success:true；最后回到父 Id=0 再建一次同名 ⇒ 必须被拦。
     * ⚠️ 父 Id 用「本类自己刚建的那行的 Id」而不是写死常量，避免与库里的既有类目撞名/撞键。
     */
    @Test
    @DisplayName("用例 4：不同 ParentId 下同名可建；同 ParentId 下同名被拦（换代的核心语义）")
    void sameNameUnderDifferentParentsIsAllowed() {
        Map<String, Object> root = service.addType(gt, 1, null);
        assertThat(root).containsEntry("success", true);
        int rootId = jdbcTemplate.queryForObject(
                "SELECT Id FROM " + TABLE + " WHERE GoodType = ? AND ParentId = 0", Integer.class, gt);

        // 另一个父级下挂同名子类目 ⇒ 放行（旧单列键会在这里误拦）
        Map<String, Object> child = service.addType(gt, 1, rootId);
        assertThat(child).containsEntry("success", true).containsEntry("msg", "新增成功");

        // 回到 rootId 父级下再建同名 ⇒ 拦
        Map<String, Object> dup = service.addType(gt, 1, rootId);
        assertThat(dup).containsEntry("success", false).containsEntry("msg", "类型已存在");

        assertThat(countRows()).isEqualTo(2);   // 根 1 行 + 子 1 行
    }

    /**
     * 用例 3：把「并发里输的那一方」摆成确定性形态 ⇒ 必须落到 catch 分支、文案仍是「类型已存在」。
     *
     * <p>做法分两步，两步都对应并发里的一个真实状态：
     * <ol>
     *   <li>先把「对手已经插成功」的那一行造出来（并发里赢的一方留下的状态）；</li>
     *   <li>再把应用层查重的返回值改成 0 —— 等价于「本请求执行 SELECT 时，对手还没提交」。</li>
     * </ol>
     * 于是服务必然走 INSERT → 撞真索引 → {@link DuplicateKeyException} → 本用例要钉的那个 catch。
     *
     * <p>⚠️ 最后那条 {@code verify(times(2))} 不是装饰，是<b>防「静默假绿」的闸</b>：
     * 上面那条 stub 若因任何原因没匹配上（走了 SELECT 分支），这个用例会<b>照样返回
     * 「类型已存在」而变绿</b> —— 与本用例要证的路径不同、结论却一样。而两条路径的
     * 副作用不同：SELECT 分支<b>不会</b>调 INSERT。故数 INSERT 次数即可把两者分开
     * （2 = 本用例自己那 1 条 + 被测代码在 catch 前那 1 条；只有 1 = stub 没生效）。
     */
    @Test
    @DisplayName("用例 3：让查重谎报 0（复现并发穿过查重的时刻）⇒ 落 catch 分支，文案仍「类型已存在」")
    void concurrencyLoserFallsIntoCatchBranchNotGenericFailure() {
        insertRaw();
        // ⚠️ 换代后查重 SQL 是 (GoodType = ? AND ParentId = ?) ⇒ stub 必须补上第二个实参，
        //    否则 varargs 长度不等、匹配不上，用例会悄悄退回 SELECT 分支而「假绿」。
        doReturn(0).when(jdbcTemplate)
                .queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), eq(gt), eq(0));

        Map<String, Object> r = service.addType(gt, 1, null);

        assertThat(r).containsEntry("success", false)
                .containsEntry("msg", "类型已存在");   // ⚠️ 不是「新增失败」——那正是本笔修掉的旧行为
        assertThat(countRows()).isEqualTo(1);

        // 同理：INSERT 现在是 (GoodType, GoodTypeNew, ParentId) 三参。
        verify(jdbcTemplate, times(2))
                .update(startsWith("INSERT INTO"), eq(gt), eq(1), eq(0));
    }

    /**
     * 绕过应用层的裸插入 —— 用例 2 / 用例 3 用它来造「库里已经有这一行」的状态。
     *
     * <p>⚠️ 显式带 {@code ParentId = 0}（与 {@code addType} 的默认口径一致）：这样本方法与被测
     * 代码的 INSERT 是<b>同一形状</b>，用例 3 的 {@code verify(times(2))} 才能把它们数到一起。
     */
    private void insertRaw() {
        jdbcTemplate.update("INSERT INTO " + TABLE
                + " (GoodType, GoodTypeNew, ParentId) VALUES (?, ?, ?)", gt, 1, 0);
    }

    /**
     * 数行数用 {@code queryForList} 而不是 {@code queryForObject}：后者的 SQL 与被测代码里那条
     * 查重语句<b>逐字相同</b>，在用例 3 里会被同一个 stub 匹配上，于是「数出来的是被 stub 覆盖的
     * 假值」—— 一个会把断言悄悄变成空话的坑。
     */
    private int countRows() {
        return jdbcTemplate.queryForList("SELECT Id FROM " + TABLE + " WHERE GoodType = ?", gt).size();
    }
}
