package com.minke.mallmanagement.goods.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * goodstype 的读/写分离承载层 —— 缓存注解的落点。
 *
 * 【为什么必须独立成类，而不是把注解挂在 Controller 上】
 * 1. 原 Controller.goodstype() 一个方法里同时装着 GET 读分支与 POST 写分支。
 *    @Cacheable 挂上去会连写分支一起缓存，而写分支里的 SELECT COUNT(*) 查重
 *    一旦被缓存，B-2 的 TOCTOU 缺陷会「假绿」—— A3 最值钱的证据被静默抹掉。
 *    见 docs/agent/context-packs/cache-layer.md §0。
 * 2. Spring 缓存基于 AOP 代理，同类内自调用不经过代理，@Cacheable 会静默失效。
 *    故读方法必须位于与本类调用方不同的 bean 中。
 *
 * 本类只搬运逻辑、不改语义：SQL 原样、返回结构原样、异常处理原样。
 */
@Service
public class GoodsTypeCacheService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 原 Controller 的 GET 分支（G:37-42），逐字搬运。
     * 无参数、无用户态 → 全局单键缓存。
     *
     * ⚠️ `sync = true` 不是可选优化，是单飞的**开关**，必须留着：
     *    不加它时 Spring 走 `Cache#get(key)`（两条路都各自回落 DB），
     *    `TwoLevelCache` 里用于合并并发未命中的 `get(key, Callable)`
     *    根本不会被调用 —— 即击穿防护是一段死代码。
     *    实测（2026-09-28，冷 L1 + 冷 L2 打 20 并发）：
     *    不加 sync → **20 次 DB 查询**（零合并）；加 sync → 1 次。
     * 另：sync=true 要求本注解只声明一个 cacheNames 且不能用 unless（此处均满足）。
     */
    @Cacheable(cacheNames = "goodstype", key = "'all'", sync = true)
    public Map<String, Object> listTypes() {
        // ⚠️ C3 块 3：返回列 + ParentId / Sort / State（唯一键换代与二级树的连带，用户已批「改契约」）。
        //    **不过滤 State=0**：管理端复用同一端点管树（要看见被停用的类目），
        //    买家侧的一级 chips 前端按 `ParentId===0`（+ 可选 State）过滤（见 HomePage.vue）。
        String sql = "SELECT Id, GoodType, GoodTypeNew, ParentId, Sort, State "
                + "FROM mall_management_sql.goodstype";
        List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", list);
        return result;
    }

    /**
     * 原 Controller 的 POST 分支（G:47-57），逐字搬运。
     * ⚠️ 本方法体内的 SELECT COUNT(*) 查重【绝不允许】被缓存 ——
     *    它就是 B-2 三处 TOCTOU 之一。此处只做 evict，不缓存任何读。
     *
     * <p>【2026-10-04 补：唯一约束 + catch 语义】上方那条 SELECT COUNT(*) 是「先查后插」，
     * 并发下两个请求可同时穿过它 —— 而本方法【不在】 B2 的 5 个分布式锁落点内，
     * 所以它是 B2 之后【唯一仍会造重复行】的 TOCTOU 站点（A3 的 B-2 场景实测 4/5 轮复现）。
     * 现在由 DB 兜最后一道：{@code goodstype} 加了唯一索引
     * {@code uk_goodstype_parent_name}（{@code (ParentId, GoodType)}；C3 块 3 由
     * {@code uk_goodstype_GoodType} **换代**而来，见 {@code mall_management_sql.sql} 末尾 Migration 块）。
     *
     * <p>⚠️ 这条索引与上方 SELECT 用的是【同一列、同一 collation】（{@code utf8mb4_0900_ai_ci}），
     * 故两边对「重复」的定义逐字一致（含大小写 / 重音不敏感），不会出现
     * 「应用层放行、DB 按另一套规则拦下」的语义错位。
     *
     * <p>加索引之后并发输的那一方会抛 {@link DuplicateKeyException}，
     * 故 catch 必须【分开】：见下方两个 catch 分支的注释。
     * 应用层查重【保留】—— 索引是最后防线，不是替代品（它省不掉一次正常路径的回查）。
     */
    @CacheEvict(cacheNames = "goodstype", allEntries = true)
    public Map<String, Object> addType(String GoodType, Integer GoodTypeNew, Integer ParentId) {
        try {
            // ⚠️ C3 块 3：查重必须与换代后的唯一键**同口径** —— 唯一键是
            //    `(ParentId, GoodType)` ⇒ 这里也按 (ParentId, GoodType) 查。
            //    否则「两个不同父级下同名子类目」会在这里被误拦（应用层放行、库也该放行），
            //    或反之「同父级下同名」被应用层放行、库再拦 —— 两个分支的文案会打架。
            int effectiveParent = ParentId == null ? 0 : ParentId;
            String checkSql = "SELECT COUNT(*) FROM mall_management_sql.goodstype "
                    + "WHERE GoodType = ? AND ParentId = ?";
            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, GoodType, effectiveParent);
            if (count > 0) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("msg", "类型已存在");
                return result;
            }

            String insertSql = "INSERT INTO mall_management_sql.goodstype "
                    + "(GoodType, GoodTypeNew, ParentId) VALUES (?, ?, ?)";
            jdbcTemplate.update(insertSql, GoodType, GoodTypeNew, effectiveParent);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("msg", "新增成功");
            return result;
        } catch (DuplicateKeyException e) {
            // ⚠️ 并发下「另一请求刚好先插成功」是【预期路径】，不是故障。
            // 加了 uk_goodstype_parent_name 后本分支必然会被走到：B-2 每轮 20 并发（TH=20）打同一个
            // GoodType，1 条成功、其余 19 条的响应文案都是「类型已存在」。
            // ⚠️ 但【落进本分支的只是穿过上方 SELECT 查重的那几条】，不是全部 19 条 ——
            //    ci49（2026-10-03，加索引前）实测每轮「新增成功」= 4/1/4/3/3 条 ⇒ 能穿过 SELECT 的
            //    最多 4 条/轮 ⇒ 落本分支的约 0–3 条/轮，其余 16–19 条由上方 SELECT 分支拦下。
            // 故：【不打印堆栈】（它会把预期路径渲染成告警，且 CI 会把这个 app.log 当 artifact 上传，
            // 刷屏即污染证据），且文案与上方 SELECT COUNT(*) 分支【逐字相同】——
            // 同一个业务事实，两条路径不许给出两种说法。
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("msg", "类型已存在");
            return result;
        } catch (Exception e) {
            // 其余异常（磁盘满 / 权限错 / 连接断）保持原样：打印 + 「新增失败」。
            // 修掉 printStackTrace 属另一笔（病 C/D「printStackTrace × 20」），本笔不动。
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("msg", "新增失败");
            return result;
        }
    }
}
