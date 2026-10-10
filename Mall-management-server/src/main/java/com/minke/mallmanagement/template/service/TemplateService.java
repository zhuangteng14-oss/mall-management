package com.minke.mallmanagement.template.service;

import com.minke.mallmanagement.template.TemplateTypes;
import com.minke.mallmanagement.template.dao.TemplateDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用模板库（{@code template}）的工作流 —— **各域共用的同一个读写口**
 * （C3 块 3 就地改造，2026-10-10；前身为 {@code catalog.service.GoodsTemplateService}，已删）。
 *
 * <h2>【模板在这个仓里是什么】</h2>
 * <b>「提供思路」，不是「引用」</b>：管理端建模板 → 各输入框旁的「选模板」按钮拉列表 →
 * 选一条**填入输入框** → 用户**可改** → 提交时落库的是**用户改完的最终文本**
 * （商品侧落 {@code goods.Detail} / {@code Spec} / {@code AfterSale}；理由侧落
 * {@code goods_audit.RejectReason} / {@code merchant_application.RejectReason} / {@code user.banReason}）。
 * ⇒ **事后改模板，已提交的数据不变**。这是与块 3 首版「商品存模板 Id」的**相反**语义，
 * 用户 2026-10-10 明确批的就是这一条。
 *
 * <h2>【读写侧宽严不同】</h2>
 * <ul>
 *   <li><b>读（{@link #list} / {@link #get}）不校验 type</b> —— 未知 type 回空列表 / {@code null}，
 *       不报错。公共读端点任何一端都可能传新 type（比如块 6 的 {@code comment.reply} 落地前
 *       前端先挂上按钮），读侧报错会变成「页面一开就红」。</li>
 *   <li><b>写（{@link #create}）要求 {@link TemplateTypes#known}</b> —— 建错 type 的模板是
 *       永远查不出来的死行，写侧收紧。</li>
 * </ul>
 *
 * <p>⚠️ 全部返回 {@code Map} 形状 {success, list|row|msg}，与 {@code CategoryService} /
 * {@code GoodsTemplateService} 的既有口径一致（本仓 Service 不抛业务异常）。
 */
@Service
public class TemplateService {

    private final TemplateDao templateDao;

    public TemplateService(TemplateDao templateDao) {
        this.templateDao = templateDao;
    }

    // ==================== 读 ====================

    /**
     * 列表。三条筛选各自可空：
     * <ul>
     *   <li>{@code type} 非空 ⇒ 精确 type（商品侧弹窗「选模板」走这支）；</li>
     *   <li>{@code typePrefix} 非空 ⇒ type 前缀（{@code NotifyPage} 的 {@code 'notification.'}
     *       / {@code CatalogPage} 的 {@code 'goods.'}）；</li>
     *   <li>两个都空 ⇒ 全部（管理端「全部」视图）。</li>
     * </ul>
     * {@code includeDisabled} 只有管理端传 true（公共读恒 false ⇒ 用户选不到停用的模板）。
     */
    public Map<String, Object> list(String type, String typePrefix, boolean includeDisabled) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        if (notBlank(type)) {
            result.put("list", templateDao.selectByType(type, includeDisabled));
        } else if (notBlank(typePrefix)) {
            result.put("list", templateDao.selectByTypePrefix(typePrefix, includeDisabled));
        } else {
            result.put("list", templateDao.selectByType(null, includeDisabled));
        }
        return result;
    }

    /**
     * 「取一条」—— 某 type 下第一条启用中的模板（按 {@code Sort, Id}）。
     *
     * <p>⚠️ 查不到回 {@code {success:true, row:null}} 而**不是** {@code success:false}：
     * 「这个 type 还没有模板」是**正常状态**（新建的 {@code template} 表初始就是空的），
     * 不是错误。
     */
    public Map<String, Object> get(String type) {
        Map<String, Object> result = new HashMap<>();
        if (!notBlank(type)) {
            result.put("success", false);
            result.put("msg", "缺少参数：Type");
            return result;
        }
        result.put("success", true);
        result.put("row", templateDao.selectFirstEnabled(type));
        return result;
    }

    // ==================== 写 ====================

    /** 新增模板 —— ⚠️ 与块 1 的 {@code saveTemplate} 不同，本方法**只插不改**（改走 {@link #update} 按 Id）。 */
    public Map<String, Object> create(String type, String name, String title, String body, Integer sort) {
        Map<String, Object> result = new HashMap<>();
        if (!notBlank(type) || !TemplateTypes.known(type)) {
            result.put("success", false);
            result.put("msg", "未知模板类型：" + type);
            return result;
        }
        if (!notBlank(name)) {
            result.put("success", false);
            result.put("msg", "缺少参数：Name");
            return result;
        }
        String t = type.trim();
        // 通知类是「标题 + 正文」两段（原 notification_template 两列都 NOT NULL）；商品类 / 理由类的
        // Title 无意义，允许为空 —— 见 TemplateTypes.requiresTitle。
        if (TemplateTypes.requiresTitle(t) && !notBlank(title)) {
            result.put("success", false);
            result.put("msg", "通知类模板缺少参数：Title");
            return result;
        }
        try {
            templateDao.insert(t, name.trim(), blankToNull(title), body, sort);
        } catch (DuplicateKeyException e) {
            // 与唯一键 uk_template_type_name 同口径的业务事实（应用层不预查，让库判定 —— 天然并发安全）。
            result.put("success", false);
            result.put("msg", "该类型下已有同名模板");
            return result;
        }
        result.put("success", true);
        result.put("msg", "新增成功");
        return result;
    }

    /**
     * 改模板（改名 / 改标题 / 改正文 / 启停 / 改排序）。**Type 不可改**（见 {@code TemplateDao.update}）。
     *
     * <p>成功时把 {@code before} 快照带回给调用方 —— 管理端 Controller 用它写审计。
     */
    public Map<String, Object> update(long id, String name, String title, String body,
                                      Integer enabled, Integer sort) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> before = templateDao.selectById(id);
        if (before == null) {
            result.put("success", false);
            result.put("msg", "模板不存在");
            return result;
        }
        int affected;
        try {
            affected = templateDao.update(id, blankToNull(name), title, body, enabled, sort);
        } catch (DuplicateKeyException e) {
            result.put("success", false);
            result.put("msg", "该类型下已有同名模板");
            return result;
        }
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "没有可更新的字段");
            return result;
        }
        result.put("success", true);
        result.put("msg", "更新成功");
        result.put("before", before);
        return result;
    }

    /** 删模板 —— **逻辑废弃**（{@code Enabled=0}，规则 v2），不物理 DELETE。 */
    public Map<String, Object> delete(long id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> before = templateDao.selectById(id);
        if (before == null) {
            result.put("success", false);
            result.put("msg", "模板不存在");
            return result;
        }
        templateDao.softDelete(id);
        result.put("success", true);
        result.put("msg", "已停用");
        result.put("before", before);
        return result;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s.trim() : null;
    }
}
