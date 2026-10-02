package com.minke.mallmanagement.goods.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * goods 的**承载层** —— 缓存注解的落点：四个读方法（@Cacheable）+ 写路径的失效面
 * （@CacheEvict，见 evictByMerchant()）。与 GoodsTypeCacheService 同一套路。
 *
 * 【为什么必须独立成类，而不是把注解挂在 Controller 上】
 * Spring 缓存基于 AOP 代理，同类内自调用不经过代理，@Cacheable 会静默失效。
 * 故被缓存的读方法必须位于与本类调用方不同的 bean 中。
 *
 * 【为什么只有**失效面**搬进来，三个写方法体却留在 Controller】
 * goodsAdd / goodsUp / goodsDe 的方法体又长又杂（图片落盘、异常吞噬、多表更新），为挂一个
 * evict 而整体搬家属于无收益的大改 —— 依据 cache-layer.md §11「只抽被缓存的读路径所需的
 * 极薄 Service，不全面重构分层」。故 2026-09-28（D 笔）的收敛方式是：**方法体留在
 * Controller，四个 cacheName 的 evict 收敛到本类的 evictByMerchant()**，Controller 侧只加
 * 一行显式调用。
 *   收益：加 cacheName 只改一处；Controller 不再持有缓存注解 ⇒ 恢复无代理（有防回归测试）。
 *   代价：失效从「注解自动清」变「显式调用清」——**漏写调用是静默的**（编译/运行都不报）。
 *         见 evictByMerchant() 的注释与 §11「显式 evict ≠ 注解 evict」。
 *
 * 本类只搬运逻辑、不改语义：SQL 原样、per-row 兜底原样、返回结构原样、异常处理原样。
 * ⚠️ **唯一例外（2026-09-30，P0 #2）**：priceByTriple() —— 它原样照搬的 `queryForMap` + 全 catch
 * 恰好构成「把失败当值缓存」。该方法的「返回结构原样 + 异常处理原样」已**有意**放弃，
 * 理由与边界全部写在它自己的 javadoc 里（搜索「曾把失败当值缓存」）。其余五个方法未动。
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
     * 原 Controller 的 productCodePrice()（G:231-252）的**读路径**承载层：SQL 原样、cacheName 与 key 见下。
     * ⚠️ 【2026-09-30 起不再是「逐字搬运」】`queryForMap` → `queryForList`，且 try/catch 与响应组装
     * （含 `goodImg` 这个与列名 `GoodImg` **不同名**的 JSON 键）**搬回 Controller** —— 理由见下「曾把失败当值缓存」段。
     *
     * 【为什么与 goodsCodes 分成两个 cacheName】
     * 同一张表的**不同投影**：本投影缓存单行 `Map`（键 = 列名 Price / GoodImg，**或 null**），listCodes() 缓存 List&lt;String&gt;。
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
     * ⚠️ 【2026-09-30 改：本方法**曾**把失败当值缓存 —— 现在不会了】
     * 原实现用 `queryForMap`：0 行抛 EmptyResultDataAccessException、**&gt;1 行抛
     * IncorrectResultSizeDataAccessException**，两者都被 catch 吞成 `{success:false}` ——
     * 一个**正常返回的非 null Map** ⇒ 被 @Cacheable 缓存到 TTL（a3 为 5m）：失败的生命周期
     * 从「瞬时」变成「TTL」。其中 **&gt;1 行那条最毒** —— 它在 TTL 内**不会因数据被修好而
     * 自愈**；0 行那条至少还会被写接口的 allEntries 清掉（见下方理由 ③）。
     *
     * 【曾接受它的三条理由（照实留档）】
     * ① `unless` 被 `sync=true` 禁止（spring-context 6.2.6 实证报文：`…sync=true operation
     *    does not support the unless attribute on '<method>'`）；
     * ② 放弃 `sync` 是**已实测的回归**（20 并发冷读 20 次 DB → 1 次，§9.1 击穿行）；
     * ③ 0 行在现网只有一个来源 —— 前端用**未保存**的表单名查询（`GoodsUp.vue` 的 `switchPrice`），
     *    保存时即被 `goodsUp` 的 allEntries 清掉 ⇒ 自愈。
     * 三条**至今成立**；变的是**有了不牺牲 ①②③ 的第四种解法**。
     *
     * 【为何改：把「0 行」与「技术异常」在**类型层面**分开】
     * 解法不是新发明，是仓内**已有**的模式（`UserCacheService.publicInfo()`，2026-09-28 落地；
     * cache-layer.md §11 ㊹⑤ 已把它登记为「新模式」，并写明它与「方案 X 不采纳」**方向相反、
     * 不冲突**：方案 X 是把**业务失败**包装成技术异常去骗过缓存；本解是把**技术异常如实上抛**）：
     * <ol>
     *   <li>**0 行 → `null`**（业务事实：查无此三元组）⇒ 是**值** ⇒ 被缓存（负缓存 / 防穿透）。
     *       防穿透**与改前持平**：改前 0 行同样被缓存，只是缓存的是 marker 而不是 null。</li>
     *   <li>**&gt;1 行 → 异常上抛**（不 catch）⇒ @Cacheable 遇异常不写入 ⇒ 该失败**不固化到
     *       TTL**，数据一修好，下一次读取立刻正确。</li>
     * </ol>
     * 实现上只把 `queryForMap` 换成 `queryForList`（0 行不再抛，成为一个 `isEmpty()` 分支），
     * 其余（cacheName、key 拼接、sync=true、失效面）**一字未动**。
     *
     * 【返回契约随之改变（调用方只有一处）】本方法不再返回 `{success, price, goodImg}`，而是返回
     * **裸行**（键 = 列名 `Price` / `GoodImg`）或 `null`；成型（catch + 组装响应）移到
     * `MallManagementGoodsController.productCodePrice()` —— 与 `UserCacheService.publicInfo()` /
     * `MallManagementUserController.userSelect` 同款。
     * ⚠️ 成型**不能**改放本类内的另一个方法再由本方法调用：自调用不走代理 ⇒ @Cacheable 静默失效
     * （本类头注释与 §11 ㊸③ 各记过一次）。
     *
     * 【本次没动的两件事（边界）】
     * ① 第 1/2 环：`goods` 表**无三元组唯一约束**、`goodsAdd` 不查重，且 collation 是
     *    `utf8mb4_0900_ai_ci`（大小写/重音不敏感）⇒ 仍可造出「只差大小写」的两行使本接口失败。
     *    改后该失败**不再被缓存**（这正是本次要关的那一环），但**失败本身依旧存在** ——
     *    实测当前 0 个重复三元组 ⇒ 该分支不可达；建唯一索引属另一主题（schema 变更）。
     * ② 响应文案：0 行与技术异常在 Controller 侧落**同一个** `{success:false}`，调用方仍分不出
     *    「真没有」还是「后端出错」—— 既有瑕疵（§11 ㊹④），改它是可观测的契约变更，须单列一笔。
     *
     * ⚠️ 形状耦合与两个 SELECT * 的投影**不同**：本投影是**显式两列** ⇒ goods 表加列不影响；
     *    但列**改名/删除**会让 `row.get("Price"/"GoodImg")` 静默取不到 → 响应里 null，编译期无感（取值点现已移到 Controller，但两个字面量的源头仍是本方法的 SELECT）。
     *    即「耦合于具体列名」而非「耦合于列集合」。见 §11。
     *
     * ⚠️ goods.MerchantId 无索引（只有 PRIMARY(Id)）⇒ 库侧是**全表扫**（与另两个投影同）。
     *    缓存把这笔代价摊薄为「每 TTL 一次」，是**掩盖而非修复**。
     */
    @Cacheable(cacheNames = "goodsPrice",
            key = "#MerchantId + '::' + #GoodName + '::' + #ProductCode", sync = true)
    public Map<String, Object> priceByTriple(String MerchantId, String GoodName, String ProductCode) {
        String sql = "SELECT Price, GoodImg FROM mall_management_sql.goods "
                + "WHERE MerchantId=? AND GoodName=? AND ProductCode=?";

        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(sql, MerchantId, GoodName, ProductCode);

        if (rows.isEmpty()) {
            // 0 行 = 业务事实（查无此三元组），作为**值**返回 ⇒ 被 @Cacheable 负缓存。
            // 与改前的差别不是「该不该缓存」，而是「缓存 null，而不是缓存一个失败 marker」。
            return null;
        }
        if (rows.size() > 1) {
            // 多行 = 数据完整性异常（前提 B 不成立：三元组无唯一约束，而 collation
            // utf8mb4_0900_ai_ci 大小写/重音不敏感 ⇒ 两行只差大小写即可同时命中）。
            // ⚠️ 这一条**必须抛**，不能像改前那样吞成 {success:false}：吞下去它就是一个
            //    「正常返回的非 null Map」⇒ 被缓存满 TTL，且 TTL 内不因数据修好而自愈。
            // 类型与改前 queryForMap 抛的**同一个**；queryForList 自己不会为多行抛，故显式构造。
            throw new IncorrectResultSizeDataAccessException(1, rows.size());
        }
        return rows.get(0);   // 裸行（键 = 列名 Price / GoodImg）；成型由 Controller 负责
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

    // ==================== 写路径：唯一的失效入口 ====================

    /**
     * goods 三个写接口（goodsAdd / goodsUp / goodsDe）共用的**唯一失效入口** —— 显式调用。
     *
     * 【三条约束 —— 少一条即静默失效，见 §11】
     * ① 必须 **public**：Spring 代理只拦 public 方法，挂到非 public 上会**静默**不生效。
     * ② 形参名必须叫 **MerchantId**：`key = "#MerchantId"` 依赖它（-parameters 已开）。若改名，
     *    SpEL 会**响亮**抛 SpelEvaluationException —— 这是本方法里唯一「失败会响」的地方。
     * ③ **不得在本类内部调用**：自调用不走代理 ⇒ 注解静默失效（同本类第 16 行的读侧约束）。
     *
     * 【调用点的位置：唯一出口之前，且不早于写库】
     * 三个写方法各自**只有一个出口**，且**全部 JDBC 语句都在 try 内、catch 一律吞异常**
     * ⇒ 凡改过库的调用必然走到那个出口；反之两条**提前 return** 路径（goodsAdd「图片为空」、
     * goodsUp 的 UploadStorage.store 落盘失败）**都没改库**。故「单出口调用一次」
     * 可证明地覆盖 100% 的可能改库路径 —— 该前提精确地是「所有 JDBC 都在 try 内」，
     * 将来新增 return 分支会破坏它，故三个调用点旁都留了警告注释。
     * ⚠️ 不能改成「进方法就清」：先清后写会在「清完 → 写完」窗口内被并发读回填**旧值**，
     *    那正是 @CacheEvict(beforeInvocation = true) 的经典风险。
     *
     * 【四条 evict 的精度，逐条成立】
     * · goodsHome 用 allEntries=true —— 本 cacheName 上是**零精度损失**：只有 'all' 一个键，
     *   清整片 == 清那一个键；且结构上不会出现「改了 key 忘了改 evict」的失配。
     * · goodsByMerchant 用 key="#MerchantId" **精确清** —— 三个写接口的写入集合都由 MerchantId
     *   限定（WHERE 里都有它）⇒ 改动集合 ⊆ 该键的集合，**不存在漏清**；多清一次是保守无害的。
     *   ⚠️ 已知边界：MerchantId 是 char(20) 且 MySQL 比较**忽略尾空格与大小写**，而缓存键是字符串
     *   精确比较 ⇒ 传 "1 " 能改到 "1" 的行、却清不到 goodsByMerchant::1（陈旧到 TTL 自愈）。
     *   要对此免疫就只能 allEntries，代价是别家列表被顺带清。见 §11。
     * · goodsCodes / goodsPrice 各用 allEntries=true —— **不是「顺手统一」**：两者的键都含
     *   GoodName，而 goodsUp 会改 GoodName 且它的 WHERE 是 (MerchantId, ProductCode)
     *   ⇒ 旧 GoodName 既不在入参、也无处可查（方法体内无任何 SELECT）⇒ 旧键构造不出。更要紧的是
     *   **形态**：精确清在「未改名」时正确、改名时**静默**漏清（条件性正确）—— 测试里全绿、
     *   只在改名时错，比整片清危险。判据（可机械执行）：取键的每个分量，问写接口的 WHERE/入参
     *   **是否含它的旧值**，缺一个即不可精确。
     *   ⚠️ goodsDe **单看是能精确清的**（WHERE 恰好就是那三段、全在入参），但同一个 cacheName 上
     *   不能既精确又整片清 ⇒ 三个写接口取**一致的 allEntries**，免去「哪条是哪种精度」的记忆负担。
     *   ⚠️ 代价与前两者不同：这两个 cacheName 的键数随 商家 × 商品名（× 型号）增长，每次写整片清
     *   （L2 侧按前缀 SCAN 删），不像 goodsHome 那样零损失。见 §11。
     *
     * 【为什么是 @Caching 而不是四个 @CacheEvict】
     * Spring 的 @CacheEvict **不是 @Repeatable**（spring-context 6.2.6 实证：常量池无 Repeatable，
     * 且该包下**没有** CacheEvicts 容器类）⇒ 同一方法上写两个同名注解**不可编译**。单个
     * @CacheEvict(cacheNames={A,B}) 只能表达**同精度**的多命名空间；本例四条分两种精度，
     * 故 @Caching 是唯一写法。
     *
     * 【beforeInvocation=false（默认）+ 显式调用 ⇒ 等价于「每次改库都清」】
     * 多清一次是保守且无害的（代价 = 下次冷读回填）。
     * ⚠️ 开放问题（已登记 §11，不阻塞）：beforeInvocation=false 在**方法抛异常**时到底执不执行，
     *    未实测。**无论答案如何都不改变本方法的正确性**：那条异常路径不改库，清与不清都对。
     *
     * 【为什么 goodsAdd 也要清 goodsPrice —— 它会改变一个**已存在**三元组的答案】
     * 0 行 → 1 行（失败变价格）、1 行 → 2 行（价格变失败：goods 表无唯一约束、goodsAdd 不查重
     * ⇒ 两行都命中 ⇒ `rows.size() &gt; 1` ⇒ 响应变 {success:false}）。docs §2 该行原把失效面写成
     * goodsUp / goodsDe 两处，**漏了 goodsAdd**（2026-09-28 订正）。
     * ⚠️ 2026-09-30（P0 #2）后，括号里那半句只对**响应**成立、对**缓存**不成立：该异常不再被缓存
     *    （数据一修好即自愈）；但「goodsAdd 能改变一个**已存在**三元组的答案」这个
     *    **失效理由本身**不变 ⇒ 仍必须清。
     *
     * 【CGLIB】注解落在本类 ⇒ GoodsCacheService 被代理（本来因四个读方法就是代理）。
     * Controller 侧的 @Caching 撤除后**不再被代理**，有防回归测试断言（MallManagementApplicationTests）。
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "goodsHome", allEntries = true),
            @CacheEvict(cacheNames = "goodsByMerchant", key = "#MerchantId"),
            @CacheEvict(cacheNames = "goodsCodes", allEntries = true),
            @CacheEvict(cacheNames = "goodsPrice", allEntries = true)
    })
    public void evictByMerchant(String MerchantId) {
        // 方法体刻意留空：失效面全部由注解声明，本方法只负责「被调到」。
        // ⚠️ 不要在这里写业务逻辑 —— 它会在**任何**调用路径上执行，且抛异常会打断调用方的写接口。
    }
}
