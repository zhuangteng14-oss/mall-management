package com.minke.mallmanagement.catalog.service;

import com.minke.mallmanagement.catalog.dao.CategoryDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 类目（{@code goodstype}）的管理端工作流（C3 块 3 商品块）。
 *
 * <h2>【模型不限深度，UI 只做二级】</h2>
 * 表结构用 {@code ParentId} 自引用，**不限制层级**（一级的 {@code ParentId=0}）；UI 只展示两级。
 * 本 Service 不校验「父级必须是一级」—— 那会把数据模型锁死在二级，与「不限深度」相悖。
 * 唯一约束交给库（{@code (ParentId, GoodType)}）。
 */
@Service
public class CategoryService {

    /** 一级类目的 {@code ParentId} 约定值（与建表块注释同源）。 */
    public static final int ROOT_PARENT_ID = 0;

    private final CategoryDao categoryDao;

    public CategoryService(CategoryDao categoryDao) {
        this.categoryDao = categoryDao;
    }

    /** 管理端类目列表（含停用，按 (ParentId, Sort, Id) 排序）—— 前端拼树。 */
    public Map<String, Object> list() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", categoryDao.selectAll());
        return result;
    }

    /**
     * 新增类目。{@code parentId} 空 ⇒ 一级（{@value #ROOT_PARENT_ID}）。
     *
     * <p>⚠️ 同父同名会撞库里的唯一键 {@code uk_goodstype_parent_name} ⇒ 翻译成「该父级下已有同名类目」。
     * **不同父级下同名是允许的**（唯一键换代的目的，见块 3 方案 §一.4）。
     */
    public Map<String, Object> create(String goodType, Integer goodTypeNew, Integer parentId, Integer sort) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(goodType)) {
            result.put("success", false);
            result.put("msg", "缺少参数：GoodType");
            return result;
        }
        int parent = parentId == null ? ROOT_PARENT_ID : parentId;
        int effectiveSort = sort == null ? 0 : sort;
        try {
            categoryDao.insert(goodType.trim(), goodTypeNew == null ? 0 : goodTypeNew, parent, effectiveSort);
        } catch (DuplicateKeyException e) {
            // 与唯一键同口径的业务事实（应用层不预查，直接让库判定 —— 顺带天然并发安全）。
            result.put("success", false);
            result.put("msg", "该父级下已有同名类目");
            return result;
        }
        result.put("success", true);
        result.put("msg", "新增成功");
        return result;
    }

    /** 改类目（改名 / 排序 / 启停 / 换父级）—— 只改传入的非空字段。 */
    public Map<String, Object> update(long id, String goodType, Integer sort, Integer state, Integer parentId) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> before = categoryDao.selectById(id);
        if (before == null) {
            result.put("success", false);
            result.put("msg", "类目不存在");
            return result;
        }
        try {
            int affected = categoryDao.update(id, blankToNull(goodType), sort, state, parentId);
            if (affected == 0) {
                result.put("success", false);
                result.put("msg", "没有可更新的字段");
                return result;
            }
        } catch (DuplicateKeyException e) {
            result.put("success", false);
            result.put("msg", "该父级下已有同名类目");
            return result;
        }
        result.put("success", true);
        result.put("msg", "更新成功");
        result.put("before", before);
        return result;
    }

    /**
     * 删类目 —— **逻辑废弃**（{@code State=0}，规则 v2）。返回 {@code children} 计数供前端提示
     * （删一级类目时其子类目**不会**被级联停用 —— 只是父级不再启用；是否级联留待有需求时再定）。
     */
    public Map<String, Object> delete(long id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> before = categoryDao.selectById(id);
        if (before == null) {
            result.put("success", false);
            result.put("msg", "类目不存在");
            return result;
        }
        long children = categoryDao.countChildren((int) id);
        categoryDao.softDelete(id);
        result.put("success", true);
        result.put("msg", "已停用");
        result.put("children", children);
        result.put("before", before);
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
