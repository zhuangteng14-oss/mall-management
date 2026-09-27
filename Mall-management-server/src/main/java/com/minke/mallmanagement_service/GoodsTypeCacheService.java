package com.minke.mallmanagement_service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
     */
    @Cacheable(cacheNames = "goodstype", key = "'all'")
    public Map<String, Object> listTypes() {
        String sql = "SELECT Id, GoodType, GoodTypeNew FROM mall_management_sql.goodstype";
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
     */
    @CacheEvict(cacheNames = "goodstype", allEntries = true)
    public Map<String, Object> addType(String GoodType, Integer GoodTypeNew) {
        try {
            String checkSql = "SELECT COUNT(*) FROM mall_management_sql.goodstype WHERE GoodType = ?";
            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, GoodType);
            if (count > 0) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("msg", "类型已存在");
                return result;
            }

            String insertSql = "INSERT INTO mall_management_sql.goodstype (GoodType, GoodTypeNew) VALUES (?, ?)";
            jdbcTemplate.update(insertSql, GoodType, GoodTypeNew);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("msg", "新增成功");
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("msg", "新增失败");
            return result;
        }
    }
}
