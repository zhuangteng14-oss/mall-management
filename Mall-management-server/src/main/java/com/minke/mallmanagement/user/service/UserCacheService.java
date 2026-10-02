package com.minke.mallmanagement.user.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * user 公开信息（`userSelect`）的承载层 —— 读路径缓存（@Cacheable）+ 写路径的失效面
 * （@CacheEvict，见 {@link #evictPublic(String)}）。B1 第三个 Service。
 *
 * <p>与 {@link GoodsCacheService} 同一套路：只抽被缓存的读路径所需的极薄 Service，
 * 不全面重构分层（cache-layer.md §11「新增 Service 层」）；写方法体仍留在 Controller。
 *
 * <h2>【本类与其它三个缓存读路径的关键差异：0 行返回 null】</h2>
 *
 * 原 Controller 的 `userSelect` 在 0 行时返回一个**表示失败的非 null Map**
 * （`{success:false, msg:"用户不存在"}`）。若照搬，缓存下来的就是那个 marker —— 那属于
 * cache-layer.md §11 已登记的「**失败结果被缓存**」（`productCodePrice` 那族），
 * **不是** §9 意义上的穿透防护（§9 的防护手段是缓存 **null**）。
 *
 * 故本方法把「0 行」翻译成 **null**（业务事实：查无此人），由 {@code @Cacheable} 缓存；
 * 而**技术异常不 catch、原样上抛** —— 缓存注解遇异常不写入。两条路径各归其位：
 *
 * <ul>
 *   <li>业务 0 行 → **null**（是值）⇒ 被缓存 ⇒ 下一次同 key 的读**不再打 DB**（负缓存 / 防穿透）</li>
 *   <li>技术异常 → **异常**（不是值）⇒ 不进缓存 ⇒ 下一次仍打 DB（不会被当成「查无此人」固化 TTL）</li>
 * </ul>
 *
 * Controller 侧收到 null 才构造 marker、收到异常也构造**同一个** marker ⇒ HTTP 响应
 * 逐字段与本类引入前**完全一致**（零契约变更）。代价是「技术异常」与「查无此人」在**响应
 * 文案**上仍不可区分 —— 那是既有瑕疵，本笔不动，已登记 cache-layer.md §11。
 *
 * <h2>【null 为什么能被缓存：两级各自的机制（均经字节码核实）】</h2>
 *
 * <ul>
 *   <li>L2（Redis）：`RedisCache.serializeCacheValue` 对 null 写哨兵 `BINARY_NULL_VALUE`
 *       （**实测形状**：64 字节，内容是 `RedisSerializer.java().serialize(NullValue.INSTANCE)`
 *       的类加载期常量 —— `GET` 回来可以直接在字节里读到
 *       `org.springframework.cache.support.NullValue`；对照正条目是 782 字节的
 *       `LinkedCaseInsensitiveMap`。故「负条目 = 被缓存的空 marker」这种猜测可被字节直接否掉）；
 *       读回时 `deserializeCacheValue` 还原成 `NullValue.INSTANCE`，再由
 *       `AbstractValueAdaptingCache.toValueWrapper`（判的是 `storeValue != null`）包成
 *       `SimpleValueWrapper(null)` ⇒ **是命中，不是 miss**（`fromStoreValue` 才把
 *       `NullValue.INSTANCE` 映射回 null）。前提是 `spring.cache.redis.cache-null-values`
 *       未被关闭（本仓未设 ⇒ Boot 默认 true）。</li>
 *   <li>L1（Caffeine）：Caffeine 禁止 null **value**，但本仓 L1 里存的是
 *       {@code SimpleValueWrapper}（见 `TwoLevelCache.put`），wrapper 本身非 null ⇒ 可存。</li>
 * </ul>
 */
@Service
public class UserCacheService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * `userSelect` 的读路径。SQL 与取值逻辑逐字搬运自原 Controller（`M:161-165`），
     * **唯一的行为改动**是 0 行返回 null（见类注释）。
     *
     * <p>⚠️ `sync = true` 是单飞的**开关**（不加则 `TwoLevelCache.get(key, Callable)`
     * 是死代码，20 并发冷读会打 20 次 DB —— 实测见 `GoodsTypeCacheService.listTypes()`）。
     * 它在**负路径上同样生效**：20 并发冷读同一不存在 id 只发生 1 次 DB 查询。
     *
     * <p>⚠️ `sync = true` 与 `unless` **不可同用**（spring-context 报
     * `…sync=true operation does not support the unless attribute`）⇒ 「不缓存失败」
     * **不能**靠 `unless` 表达。本类的做法是把「失败」在**类型层面**分成 null 与异常，
     * 这不需要 `unless`。
     *
     * <p>⚠️ 本方法的返回值是**裸 `Map`**（`SELECT` 显式 5 列），不是 DTO —— 与 §10
     * 「不缓存裸 Map」的原则仍然是同一笔欠账（全仓 32 个接口都返回 Map，第一版无法满足）。
     * 它耦合的是**具体列名**：列改名/删除后 `map.get("列名")` 静默取不到（§10 边界第 4 条
     * 形态②）。
     */
    @Cacheable(cacheNames = "userPublic", key = "#userId", sync = true)
    public Map<String, Object> publicInfo(String userId) {
        String sql = "SELECT customerid, merchantid, username, usertype, gender FROM user WHERE customerid = ? OR merchantid = ?";
        List<Map<String, Object>> userList = jdbcTemplate.queryForList(sql, userId, userId);
        if (userList.isEmpty()) {
            // ← 负缓存的落点：0 行是**业务事实**，作为 null 缓存住，此后同 key 不再打 DB。
            return null;
        }
        return userList.get(0);
    }

    // ==================== 写路径：userPublic 的唯一失效入口 ====================

    /**
     * `userPublic` 的**唯一失效入口** —— 显式调用（模板见 cache-layer.md §10
     * 「写路径 evict 的单入口」，本类是它的第二个实例）。
     *
     * <p>【调用方】只有 `MallManagementUserController.loginAdd()` 一处：全仓对 `user` 表的写
     * 只有 3 处（两条 INSERT 都在 `loginAdd`、一条 UPDATE 只写 `email`/`address`/
     * `selfintroduction`），而与 `userPublic` 缓存的 5 列
     * （`customerid`/`merchantid`/`username`/`usertype`/`gender`）**有交集的只有 `loginAdd`**。
     *
     * <p>【为什么能精确清（§10 evict 规则第 3 条「闭合」）】`loginAdd` 插入的那一行，其
     * `customerid`（客户）或 `merchantid`（商家）**就是入参 `id`**；而 `userSelect` 的键
     * `userId` 是同一个值域 ⇒ 写入集合 ⊆ 该 cacheName 的键集合，且多清无害。
     * ⚠️ 这条曾有一个**看似成立的反例**：`loginAdd` 的入参默认值是 `暂无数据`
     * （`customerid`/`merchantid` 各一个），若那个值落进**列**里，则每次注册都会改写
     * 「键 = `暂无数据`」那一行的答案 ⇒ 精确清不完备。**实测推翻**：对侧列不在 INSERT 的
     * 列清单里 ⇒ 落 NULL；真实数据里**没有任何一行的列值是 `暂无数据`**（`COUNT = 0`），
     * 且 `NULL != '暂无数据'` ⇒ 无此漏洞。
     *
     * <p>【三条约束 —— 少一条即静默失效，与 `GoodsCacheService.evictByMerchant()` 同款】
     * <ol>
     *   <li>必须 **`public`**：Spring 代理只拦 public 方法，挂到非 public 上会**静默**不生效。</li>
     *   <li>形参名必须叫 **`userId`**：`key = "#userId"` 依赖它（`-parameters` 已开 —— 实证为
     *       编译产物里的 `MethodParameters` 属性）。改名会报 `SpelEvaluationException`
     *       或清错键。</li>
     *   <li>**不得在本类内部调用**：自调用不走代理 ⇒ 注解静默失效。</li>
     * </ol>
     *
     * <p>【调用点的位置】`loginAdd` 的**唯一出口**（`return "添加成功！"`）之前。
     * `loginAdd` 的另一条 return（「用户已存在」）只跑了 SELECT、未改 `user` ⇒ 不必清；
     * INSERT 抛异常时经**异常**退出（该方法无 try/catch），而 `@Transactional` 会回滚 ⇒
     * 异常路径也不改库 ⇒ 唯一出口覆盖 100% 的可能改库路径。
     *
     * <p>⚠️ **已知边界（本笔接受，已登记 §11）**：`loginAdd` 是 `@Transactional`，而本方法
     * 在**事务提交之前**被调用（显式调用的必然结果）⇒ 清完之后、提交之前有一个窗口，
     * 窗口内的并发读会把**负条目回填**且没有第二次清。本场景判定无害：该 `id` 是刚刚创建的，
     * 除注册者本人外无人知晓，而注册者拿到响应时事务已提交。
     */
    @CacheEvict(cacheNames = "userPublic", key = "#userId")
    public void evictPublic(String userId) {
        // 方法体刻意留空：失效面全部由注解声明，本方法只负责「被调到」。
        // ⚠️ 不要在这里写业务逻辑 —— 它会在**任何**调用路径上执行，且抛异常会打断调用方的写接口。
    }
}
