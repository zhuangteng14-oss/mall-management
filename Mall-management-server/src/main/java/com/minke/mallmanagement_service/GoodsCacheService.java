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
}
