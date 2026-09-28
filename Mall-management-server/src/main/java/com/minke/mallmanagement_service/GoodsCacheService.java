package com.minke.mallmanagement_service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * goods 的**读**承载层 —— 缓存注解的落点（与 GoodsTypeCacheService 同一套路）。
 *
 * 【为什么必须独立成类，而不是把注解挂在 Controller 上】
 * Spring 缓存基于 AOP 代理，同类内自调用不经过代理，@Cacheable 会静默失效。
 * 故被缓存的读方法必须位于与本类调用方不同的 bean 中。
 *
 * 【为什么这里只有读方法，写路径却留在 Controller】
 * 与 GoodsTypeCacheService 不同：本类的写路径（goodsAdd / goodsUp / goodsDe）没有搬进来。
 * 依据 cache-layer.md §11「只抽被缓存的读路径所需的极薄 Service，不全面重构分层」——
 * 那三个写接口的方法体又长又杂（图片落盘、异常吞噬、多表更新），为挂一个 evict 而整体搬家
 * 属于无收益的大改。故它们留在 Controller，@CacheEvict 也挂在 Controller 方法上
 * （Spring 的缓存注解对任何 bean 生效，不限于 @Service）。
 *
 * 本类只搬运逻辑、不改语义：SQL 原样、per-row 兜底原样、返回结构原样、异常处理原样。
 */
@Service
public class GoodsCacheService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 原 Controller 的 goodsSelectHome()（G:157-196），逐字搬运。
     * 无参数、无用户态 → 全局单键缓存（cacheName = goodsHome、key = 'all'，
     * 与既有 goodstype::all 同一套读法：键的可读性由 cacheName 段保证，不靠 key 段重复）。
     *
     * ⚠️ `sync = true` 不是可选优化，是单飞的**开关**，必须留着：
     *    不加它时 Spring 走 `Cache#get(key)`（并发未命中各自回落 DB），
     *    `TwoLevelCache` 里用于合并并发未命中的 `get(key, Callable)` 根本不会被调用
     *    —— 即击穿防护是一段死代码。goodstype 上已实测（2026-09-28：不加 sync → 20 次 DB；
     *    加 → 1 次），本接口为新键，用同一判据复测。
     * 另：sync=true 要求本注解只声明一个 cacheNames 且不能用 unless（此处均满足）。
     *
     * ⚠️ 本方法**不会返回 null**（异常也返回 success:false 的 map）⇒ 不产生空值缓存，
     *    cache-layer.md §9.1「穿透：机制未验证」的结论不因本接口改变。
     *
     * ⚠️ SQL 逐字不动：本接口是 1 次 goods + 每行 1 次 login（N+1）。缓存把它的代价摊薄，
     *    但**不修**它 —— 修正需要改 SQL（IN 一次查或 JOIN），超出本笔边界（§11 已记一条边界）。
     *
     * ⚠️ `SELECT *` ⇒ 缓存体是「列全集的快照」：goods 表加列后，旧条目缺该列、响应字段
     *    缺失直到 TTL。处置沿用 §10 约定（响应形状变更 ⇒ bump 键版本），本笔不加版本前缀
     *    （与既有 goodstype::all 保持一致）。
     */
    @Cacheable(cacheNames = "goodsHome", key = "'all'", sync = true)
    public Map<String, Object> listHome() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 1. 先查所有商品
            String sql = "SELECT * FROM mall_management_sql.goods";
            List<Map<String, Object>> goodsList = jdbcTemplate.queryForList(sql);

            // 2. 循环根据商品里的 MerchantId 查 login 表的 UserName
            for (Map<String, Object> goods : goodsList) {
                // 拿到商品里的商家ID
                Object merchantIdObj = goods.get("MerchantId");

                try {
                    // 重点：login 表用 id = ? 匹配！！！
                    String userSql = "SELECT UserName FROM mall_management_sql.login WHERE id = ?";

                    String userName = jdbcTemplate.queryForObject(
                            userSql,
                            new Object[]{merchantIdObj},
                            String.class
                    );

                    // 把用户名放进商品数据
                    goods.put("UserName", userName);
                } catch (Exception e) {
                    // 查不到就为空
                    goods.put("UserName", "");
                }
            }

            result.put("success", true);
            result.put("list", goodsList);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败");
        }
        return result;
    }

    /**
     * 原 Controller 的 goodsSelect()（G:152-168），逐字搬运：SQL 原样、try/catch 原样、
     * 返回结构原样。Controller 只留路由与委托。
     *
     * 【为什么与 listHome() 分开成两个 cacheName，而不是合成一个】
     * 两者键空间与失效精度都不同：本方法是**按商家**的键（键数 = 有缓存的商家数），
     * 可**精确清**；listHome() 是全局单键，只能整片清（allEntries 在那里是零精度损失，
     * 在这里不是）。合并后一片键空间无法只对其中一个投影精确清 ⇒ 二者不可同片。
     * 合并的收益（新增读接口不必改写接口的 evict 清单）与触发条件见 cache-layer.md §11。
     *
     * 【key = #MerchantId】
     * SpEL 取的是**形参名**，依赖编译期 `-parameters`（本模块已开：javap -v 该类的
     * MethodParameters 属性可见形参名 MerchantId，与 @RequestParam 未显式写名却能解析
     * 走的是同一个 ParameterNameDiscoverer）。cacheName 段已承担「哪个投影」，
     * 键保持裸业务标识，与 goodstype::all / goodsHome::all 同一读法。
     *
     * ⚠️ `sync = true` 不是可选优化，是单飞的**开关**，必须留着：
     *    不加它时 Spring 走 `Cache#get(key)`（并发未命中各自回落 DB），
     *    `TwoLevelCache` 里用于合并并发未命中的 `get(key, Callable)` 根本不会被调用。
     * 另：sync=true 要求本注解只声明一个 cacheNames 且不能用 unless（此处均满足）。
     *
     * ⚠️ 本方法**不会返回 null**：0 行结果是 `success:true, list: []`，异常是 `success:false`
     *    的 map。注意区分两件事 —— 空结果是**被正常缓存的值**，不是「空值缓存（负缓存）机制」
     *    ⇒ cache-layer.md §9.1「穿透：机制未验证」的结论不因本接口改变。
     *
     * ⚠️ 键空间随入参无界：本接口零鉴权（BD-03）、MerchantId 是任意入参 ⇒ 任何人可造键。
     *    量级有界：L1 有 app.cache.caffeine.max-size=1000 硬上界、L2 条目随 TTL 过期，
     *    每条目为一个小列表或空列表。与 productSearch 的无界（拼 SQL + 键空间无界）不同量级。
     *
     * ⚠️ `SELECT *` ⇒ 与 listHome() 同一类形状耦合（同表同语句，列集合相同）：goods 表加列后，
     *    旧条目缺该列直到 TTL。处置沿用 §10 约定（响应形状变更 ⇒ bump 键版本）。
     *
     * ⚠️ `goods.MerchantId` 无索引（db-schema.md §索引缺失 G:160）⇒ 本方法在库里是**全表扫**。
     *    缓存把这笔代价摊薄为「每 TTL 一次」，是**掩盖而非修复**（与 listHome() 的 N+1 同性质）。
     */
    @Cacheable(cacheNames = "goodsByMerchant", key = "#MerchantId", sync = true)
    public Map<String, Object> listByMerchant(String MerchantId) {
        Map<String, Object> result = new HashMap<>();
        try {
            String sql = "SELECT * FROM mall_management_sql.goods WHERE MerchantId = ?";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, MerchantId);
            result.put("success", true);
            result.put("list", list);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "查询失败");
        }
        return result;
    }

    /**
     * 原 Controller 的 productCodePrice()（G:231-252），逐字搬运：SQL 原样、queryForMap 原样、
     * try/catch 原样、返回结构原样（含 goodImg 这个与列名 GoodImg **不同名**的 JSON 键）。
     *
     * 【为什么与 goodsCodes 分成两个 cacheName】
     * 同一张表的**不同投影**：本方法返回单点 {price, goodImg}，listCodes() 返回 List&lt;String&gt;。
     * 合成一片键空间会让同一 cacheName 里混住两种值形状 —— @Cacheable 按谁先命中返回谁，触发
     * 类型混淆（Cache#get(key, type) 路径直接 IllegalStateException）。“cacheName = 表名 + 投影名”
     * 这条命名规则的「投影名」段即为此设（本方法是该规则的第 2 个实例）。
     *
     * 【key = #MerchantId + '::' + #GoodName + '::' + #ProductCode】
     * 顺序取**由粗到细**，与 SQL 的 WHERE 顺序一致；落到 Redis 是
     * `goodsPrice::&lt;MerchantId&gt;::&lt;GoodName&gt;::&lt;ProductCode&gt;`。
     * 用显式拼接而非 SpEL 内联 list（{#a,#b,#c}）：L2 的键是 key 对象 toString() 后与 cacheName
     * 拼成（RedisCache + StringRedisSerializer）⇒ list 形态既不可读**也不更安全**。
     * ⚠️ 三个分量都由客户端可控（MerchantId 来自注册入参 userid，见 UserController:58）⇒
     *    `X::Y::Z::W` 存在多种拆法。但拆法数量受「内嵌 `::` 总数」约束：两个不同三元组要给出同一
     *    字符串，双方的内嵌分隔符数量必须相等 ⇒ 真实分量不含 `::` 时解析唯一。实测当前 5 个三元组
     *    的三个分量**均不含 `::`** ⇒ 对真实数据**不可达**；即便构造出来（需双方都内嵌 `::`），读到
     *    的也是零鉴权（BD-03）下本就能直接读的东西 ⇒ 非新增能力。边界见 §11。
     *
     * 【失效面 = goodsAdd / goodsUp / goodsDe，三个**全部 allEntries**】
     * 本 cacheName 的键含 GoodName，而 goodsUp 会改 GoodName，它的 WHERE 是
     * (MerchantId, ProductCode) —— **旧 GoodName 既不在入参、也无处可查** ⇒ 旧键构造不出。
     * 更要紧的是失效的**形态**：精确清在「未改名」时正确、改名时**静默**漏清（条件性正确）——
     * 测试里全绿、只在改名时错，比整片清更危险。故一律 allEntries。
     * 判据（可机械执行）：取键的每个分量，问写接口的 WHERE/入参**是否含它的旧值**；缺一个即不可精确。
     * ⚠️ goodsDe 单看**能**精确清（三元组全在入参），但同一 cacheName 上不能既精确又全清
     *    （goodsUp 那条做不到）⇒ 三个写接口取一致的 allEntries。见 §11「精确 evict 的边界」。
     * 代价（明说）：本 cacheName 的键数 = 商家 × 商品名 × 型号，是三个新投影里增长最快的一个；
     * 每次写要整片清（L2 侧是按前缀 SCAN 删），不像 goodsHome 的 allEntries 是零精度损失。
     *
     * ⚠️ 本方法是**首个会把失败当值缓存**的接口：queryForMap 在 0 行时抛
     *    EmptyResultDataAccessException（&gt;1 行抛 IncorrectResultSizeDataAccessException），
     *    两者都被 catch 吞成 {success:false} —— 一个**正常返回的非 null Map** ⇒ 会被缓存到 TTL
     *    （a3 为 5m）。即失败的生命周期从「瞬时」变成「TTL」。
     *    常规解法 unless 被 sync=true 禁止（spring-context 6.2.6 实证报文：`…sync=true operation
     *    does not support the unless attribute on '<method>'`），而放弃 sync 是**已实测的回归**
     *    （20 并发冷读 20 次 DB → 1 次，§9.1 击穿行）⇒ 接受失败被缓存，靠写接口的 allEntries 自愈
     *    （现网唯一来源是前端用**未保存**的表单名查询，保存时即被清掉）。
     *    「把异常上移到 Controller 以绕过缓存」这条方案**已被考虑并否决** —— 理由见 §11（语义错位）。
     *
     * ⚠️ 重复三元组：goods 表只有 PRIMARY(Id)、**无唯一约束**，而 goodsAdd 不做查重 ⇒ 可造出两行同
     *    三元组 ⇒ 本接口从「价格」变成「失败」，且该失败被缓存到 TTL。实测当前 0 个重复三元组
     *    （5 三元组 = 5 行）⇒ 该分支不可达。**不修**（建唯一索引属另一主题）。
     *
     * ⚠️ 形状耦合与两个 SELECT * 的投影**不同**：本投影是**显式两列** ⇒ goods 表加列不影响；
     *    但列**改名/删除**会让 map.get("Price"/"GoodImg") 静默取不到 → 响应里 null，编译期无感。
     *    即「耦合于具体列名」而非「耦合于列集合」。见 §11。
     *
     * ⚠️ goods.MerchantId 无索引（只有 PRIMARY(Id)）⇒ 库侧是**全表扫**（与另两个投影同）。
     *    缓存把这笔代价摊薄为「每 TTL 一次」，是**掩盖而非修复**。
     */
    @Cacheable(cacheNames = "goodsPrice",
            key = "#MerchantId + '::' + #GoodName + '::' + #ProductCode", sync = true)
    public Map<String, Object> priceByTriple(String MerchantId, String GoodName, String ProductCode) {
        Map<String, Object> result = new HashMap<>();
        String sql = "SELECT Price, GoodImg FROM mall_management_sql.goods "
                + "WHERE MerchantId=? AND GoodName=? AND ProductCode=?";

        try {
            Map<String, Object> map = jdbcTemplate.queryForMap(sql,
                    MerchantId, GoodName, ProductCode);
            result.put("success", true);
            result.put("price", map.get("Price"));
            result.put("goodImg", map.get("GoodImg")); // 响应键与列名 GoodImg 不同名
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
        }
        return result;
    }

    /**
     * 原 Controller 的 GoodsProductCodeSelect()（G:347-366），逐字搬运：SQL 原样、queryForList 原样、
     * try/catch 原样、返回结构原样。
     *
     * 【为什么与 goodsPrice 分成两个 cacheName】
     * 同一张表的两个投影，**值形状**不同：本方法返回 List&lt;String&gt;（型号列表），
     * priceByTriple() 返回单点 {price, goodImg}。合成一片键空间会让同一 cacheName 里混住两种值形状
     * —— @Cacheable 按谁先命中返回谁，触发类型混淆（Cache#get(key, type) 路径直接
     * IllegalStateException）。本方法与 priceByTriple() 是「cacheName = 表名 + 投影名」
     * 里「同族同表、按投影名分裂」的一对实例（另一个同族分裂是 goodsHome / goodsByMerchant，
     * 分裂理由不同：那对是**失效精度**不同，见 §11 待办 B）。
     *
     * 【key = #MerchantId + '::' + #GoodName】
     * 顺序取**由粗到细**，与 SQL 的 WHERE 顺序一致；落到 Redis 是
     * `goodsCodes::&lt;MerchantId&gt;::&lt;GoodName&gt;`。与 priceByTriple() 同一套拼接规则
     * （显式 `+ '::' +`，不用 SpEL 内联 list）—— L2 的键是 key 对象 toString() 后与 cacheName 拼成
     * （RedisCache + StringRedisSerializer）⇒ list 形态既不可读也不更安全。
     * ⚠️ 二维：两个分量都由客户端可控（MerchantId 来自注册入参 userid，见 UserController:58）⇒
     *    `A::B::C` 存在两种拆法。与三维键同一判据：拆法数量受内嵌 `::` 总数约束，两个分量不含 `::`
     *    时解析唯一；实测当前 4 组 (MerchantId, GoodName) 的两分量均不含 `::` ⇒ 对真实数据不可达，
     *    构造得出也只是零鉴权（BD-03）下本就能直接读的东西 ⇒ 非新增能力。边界见 §11。
     *
     * 【失效面 = goodsAdd / goodsUp / goodsDe，三个**全部 allEntries**】
     * 与 priceByTriple() 同因：键含 GoodName，而 goodsUp 会改 GoodName，它的 WHERE 是
     * (MerchantId, ProductCode) —— **旧 GoodName 既不在入参、也无处可查** ⇒ 旧键构造不出；
     * 且精确清会呈现「**条件性正确**」（未改名时对、改名时**静默**漏）—— 测试全绿、只在改名时错。
     * 判据（可机械执行）：取键的每个分量，问写接口的 WHERE/入参**是否含它的旧值**；缺一个即不可精确。
     * ⚠️ 本 cacheName 的 evict **先于读接口落地**（2026-09-28 随 priceByTriple 一并挂上，
     *    彼时本方法还不存在）⇒ 落地前每次写都在清一个**空片**（L2 侧建出空命名空间 + 一次 no-op 清理）。
     *    本仓的顺序是「先加 evict 再加读」，不常见，登记见 §11。
     *
     * ⚠️ `sync = true` 不是可选优化，是单飞的**开关**，必须留着：
     *    不加它时 Spring 走 `Cache#get(key)`（并发未命中各自回落 DB），
     *    `TwoLevelCache` 里用于合并并发未命中的 `get(key, Callable)` 根本不会被调用。
     * 另：sync=true 要求本注解只声明一个 cacheNames 且不能用 unless（此处均满足）。
     *
     * ⚠️ 本方法**不会返回 null**：无型号时是 `success:true, list: []`，异常是 `success:false`
     *    的 map。同 goodsSelect —— 空列表是**被正常缓存的值**，不是「空值缓存（负缓存）机制」
     *    ⇒ cache-layer.md §9.1「穿透：机制未验证」的结论不因本接口改变。
     *
     * ⚠️ 值序**未定义**：SQL 是 `SELECT DISTINCT ProductCode`，**无 ORDER BY**，EXPLAIN 为
     *    `type=ALL … Using where; Using temporary` ⇒ 返回序由临时表决定，MySQL 不保证。
     *    实测连跑 6 次序稳定（唯一的 k≥2 组是 [IJ-555, IF-55]），但那是**实测稳定**、
     *    不是**保证相同** ⇒ 「缓存体与冷查逐字节相同」这条判据在本接口上强度弱于另两个投影。
     *    **不修**（加 ORDER BY 属于改 SQL，超出本笔边界；且读侧按下拉框列表用，序无业务含义）。
     *
     * ⚠️ 形状耦合的形态与另两个投影都不同：本投影是**单列**，且经
     *    `queryForList(sql, String.class)` 取值 —— `SingleColumnRowMapper` 走的是**列序号 1**，
     *    **不是列名** ⇒ 若有人把 SQL 改选成另一列（如 `SELECT DISTINCT GoodImg`），**不报错**、
     *    仍然返回 List&lt;String&gt;，只是语义悄悄变了。列**改名/删除**则 SQL 自身报错（响亮，非静默）。
     *    即「耦合于**被选中的那一列**」，与 priceByTriple 的「耦合于**具体列名**」不同形态。见 §11。
     *
     * ⚠️ `goods.MerchantId` 无索引（只有 PRIMARY(Id)）⇒ 库侧是**全表扫 + 临时表**
     *    （EXPLAIN：type=ALL、rows=5、Using temporary）。缓存把这笔代价摊薄为「每 TTL 一次」，
     *    是**掩盖而非修复**（与另两个投影同性质）。
     */
    @Cacheable(cacheNames = "goodsCodes",
            key = "#MerchantId + '::' + #GoodName", sync = true)
    public Map<String, Object> listCodes(String MerchantId, String GoodName) {
        Map<String, Object> result = new HashMap<>();
        String sql = "SELECT DISTINCT ProductCode FROM mall_management_sql.goods "
                + "WHERE MerchantId=? AND GoodName=?";

        try {
            List<String> productCodeList = jdbcTemplate.queryForList(sql,
                    String.class, MerchantId, GoodName);
            result.put("success", true);
            result.put("list", productCodeList);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
        }
        return result;
    }
}
