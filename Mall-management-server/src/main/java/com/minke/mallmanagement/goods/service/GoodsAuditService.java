package com.minke.mallmanagement.goods.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.goods.dao.GoodsAuditDao;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商品**提审单**：提交 / 我的提审 / 审核（C3 块 3 商品块）—— 照 {@code MerchantApplicationService}
 * 的三段式（提交 / 读 / 审核 CAS），但**审核通过多写一张表**。
 *
 * <h2>【提审制：{@code goodsAdd} 只写 {@code goods_audit}，不写 {@code goods} 行】</h2>
 * 这是本块最重的行为变更（A 档用户已批，硬线「改契约」）。{@link #submit} 落一条
 * {@code goods_audit(pending)}（商品字段**快照**副本，含 {@code GoodTypeNew} / {@code TypeId}），
 * **一列都不写 {@code goods}** —— 故买家侧「看不到待审商品」是因为**压根没上架**，
 * 不是脏过滤（验收项 3 的正面证据）。
 *
 * <h2>【审核 = CAS + 写 {@code goods} 行 + 回填 {@code GoodsId} **同一事务**】</h2>
 * {@link #audit} 把三件事放进<b>一个</b> {@code @Transactional}：
 * <ol>
 *   <li>CAS 改提审单状态（{@code pending → approved}，见 {@code GoodsAuditDao}）；</li>
 *   <li>通过时**用提审单的行重建**一次 {@code goods} 行（{@code insertGoods} + {@code insertProductsales}
 *       —— 复用的正是原 {@code goodsAdd} 的写口）+ 回填 {@code GoodsId}；</li>
 *   <li>失效缓存（{@code evictByMerchant}，让买家侧立刻看到新上架）。
 * </ol>
 * 不放进同一个事务就会出现「提审单已 approved、但 {@code goods} 表没有行」的**半完成态**
 * —— 商家看到「通过了」却没人看得到商品，而 CAS 已消费、不可回退。
 *
 * <h2>【驳回 = 只改提审单，不碰 {@code goods}】</h2>
 * {@code Status='rejected'} + {@code RejectReason}（这一列**是给商家看的**，见块 2 同名列口径）。
 * 再次提审 = **新插一条** {@code goods_audit} 行（保留历史），故本表可多次 INSERT、可 UPDATE。
 *
 * <h2>【⚠️ 通知与审计**不在这里** —— 在 Controller，事务提交之后】</h2>
 * 同 {@code MerchantApplicationService}：{@code NotificationService.send} 与
 * {@code AuditService.record} 都是 fail-soft、无事务的，放进业务事务会被回滚一起卷走。
 * 故本方法只**返回**「该通知谁、通知什么」，由 Controller 发。
 */
@Service
public class GoodsAuditService {

    /** 提审单状态码 —— 与建表块里 {@code goods_audit.Status} 列的注释同源（小写）。 */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    /** 列表默认条数 / 上限（与 {@code MerchantApplicationService} 同口径）。 */
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    private final GoodsAuditDao auditDao;
    private final GoodsDao goodsDao;
    private final GoodsCacheService goodsCacheService;
    /**
     * 解析 {@code goods_audit.SkuSpec}（多规格矩阵快照，JSON 数组串）—— 与 {@code order.Items} /
     * {@code OrderService.resolveItems} 同款：线上用的就是 Spring Boot 自动配置的那个 ObjectMapper，
     * 不另建（那会波及既有端点的字节）。
     */
    private final ObjectMapper objectMapper;

    public GoodsAuditService(GoodsAuditDao auditDao,
                             GoodsDao goodsDao,
                             GoodsCacheService goodsCacheService,
                             ObjectMapper objectMapper) {
        this.auditDao = auditDao;
        this.goodsDao = goodsDao;
        this.goodsCacheService = goodsCacheService;
        this.objectMapper = objectMapper;
    }

    // ==================== 提交（提审） ====================

    /**
     * 落一条待审提审单 —— {@code goodsAdd} 编排完图片落盘后的**唯一写库动作**。
     *
     * <p>⚠️ <b>不写 {@code goods} 行</b>（提审制的定义，见类注释）。
     *
     * <p>⚠️ {@code TypeId} 的两种来源：前端传了就用它（新前端）；没传（老前端只给类目名）
     * 则按名回查（{@link GoodsDao#selectTypeIdByName}）—— 名字可能不唯一 ⇒ 取 Id 最小的一条，
     * **接受歧义**（正确通路是前端传 {@code TypeId}）。回查不到 ⇒ {@code null}（审核通过时
     * {@code goods.TypeId} 也留 NULL，买家二级筛选查不到它，但这不伪造数据）。
     *
     * @param batchId 提审批次号（跨重导稳定的审计 / 通知 TargetId），由调用方生成
     * @return {@code {success:true, batchId}}
     */
    @Transactional
    public Map<String, Object> submit(String merchantId, String batchId, String goodName,
                                      Double price, String goodType, Integer goodTypeNew,
                                      Integer typeId, String productCode, String goodImg, String detail, String spec, String afterSale,
                                      String skuSpec, Boolean promiseGenuine, Boolean promiseReturn7, Boolean promiseFastRefund, Boolean promiseFreightIns) {
        Map<String, Object> result = new HashMap<>();
        Integer effectiveTypeId = typeId != null ? typeId : goodsDao.selectTypeIdByName(goodType);

        auditDao.insert(merchantId, batchId, goodName, price, goodType, goodTypeNew, effectiveTypeId,
                productCode, goodImg, detail, spec, afterSale, skuSpec, LocalDateTime.now(),
                boolOrTrue(promiseGenuine), boolOrTrue(promiseReturn7), boolOrTrue(promiseFastRefund), boolOrTrue(promiseFreightIns));

        result.put("success", true);
        result.put("batchId", batchId);
        return result;
    }

    /** 生成一个新的提审批次号 —— 提审（新单）与再次提审都用它。 */
    public static String newBatchId() {
        return UUID.randomUUID().toString();
    }

    // ==================== 读 ====================

    /** 「我的提审」（商家号维度，时间倒序）—— 商家端商品状态列并表用。 */
    public Map<String, Object> myApplications(String merchantId, Integer limit) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(merchantId)) {
            result.put("success", false);
            result.put("msg", "缺少参数：MerchantId");
            return result;
        }
        int effective = normalizeLimit(limit);
        List<Map<String, Object>> rows = auditDao.selectByMerchant(merchantId, effective);
        result.put("success", true);
        result.put("list", rows);
        result.put("total", rows.size());
        result.put("limit", effective);
        return result;
    }

    /** 管理端提审列表（{@code status} 可空 ⇒ 全部）。 */
    public Map<String, Object> listForAdmin(String status, Integer limit) {
        int effective = normalizeLimit(limit);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", auditDao.selectByStatus(status, effective));
        result.put("total", auditDao.countByStatus(status));
        result.put("limit", effective);
        return result;
    }

    // ==================== 审核 ====================

    /**
     * 审核一条提审单。批准 ⇒ {@code approve=true}；驳回 ⇒ {@code approve=false} 且
     * {@code rejectReason} 必填。
     *
     * <p>返回值里的 {@code outcome} 是给 Controller 决定「发什么通知」用的：
     * {@code approved} / {@code rejected} / {@code already-handled}。
     */
    @Transactional
    public Map<String, Object> audit(String reviewer, Long id, boolean approve, String rejectReason) {
        Map<String, Object> result = new HashMap<>();
        if (id == null || id <= 0) {
            result.put("success", false);
            result.put("msg", "缺少参数：id");
            return result;
        }
        if (!approve && isBlank(rejectReason)) {
            result.put("success", false);
            result.put("msg", "驳回必须填写理由");
            return result;
        }

        Map<String, Object> before = auditDao.selectById(id);
        if (before == null) {
            result.put("success", false);
            result.put("msg", "提审单不存在");
            return result;
        }
        String merchantId = str(before.get("MerchantId"));

        LocalDateTime now = LocalDateTime.now();
        int affected = approve
                ? auditDao.casApprove(id, reviewer, now)
                : auditDao.casReject(id, reviewer, now, rejectReason);

        if (affected == 0) {
            // CAS 落空 —— 已被别的管理员处理过（或状态本就不是 pending）。**不是错误**。
            result.put("success", true);
            result.put("outcome", "already-handled");
            result.put("merchantId", merchantId);
            return result;
        }

        if (!approve) {
            result.put("success", true);
            result.put("outcome", "rejected");
            result.put("merchantId", merchantId);
            result.put("rejectReason", rejectReason);
            return result;
        }

        // 通过：用提审单的**快照**重建 goods 行（复用原 goodsAdd 的两个写口）。同事务 ⇒ 任一步
        // 失败则 CAS 一起回滚，提审单退回 pending（不留「approved 但没商品」的半完成态）。
        long goodsId = writeGoodsFromAudit(before);
        auditDao.fillGoodsId(id, goodsId);
        goodsCacheService.evictByMerchant(merchantId);

        result.put("success", true);
        result.put("outcome", "approved");
        result.put("merchantId", merchantId);
        result.put("goodsId", goodsId);
        return result;
    }

    // ==================== 内部 ====================

    /**
     * 用提审单快照写 {@code goods} + {@code productsales} 两行，返回新 {@code goods.Id}。
     *
     * <p>⚠️ <b>{@code lastInsertId()} 在此**可信**</b>：本方法跑在 {@code @Transactional} 里
     * ⇒ 两条语句共用同一条连接 ⇒ {@code LAST_INSERT_ID()} 就是刚插那行的 Id
     * （{@code GoodsDao.lastInsertId()} 的 ⚠️ 说的「两次调用可能落在不同连接」在**无事务**时才成立）。
     */
    private long writeGoodsFromAudit(Map<String, Object> auditRow) {
        goodsDao.insertGoods(new GoodsDao.GoodsRow(
                str(auditRow.get("MerchantId")),
                str(auditRow.get("GoodName")),
                toDouble(auditRow.get("Price")),
                str(auditRow.get("GoodImg")),
                str(auditRow.get("GoodType")),
                str(auditRow.get("ProductCode")),
                toInt(auditRow.get("GoodTypeNew"), 0),
                toInteger(auditRow.get("TypeId")), str(auditRow.get("Detail")), str(auditRow.get("Spec")), str(auditRow.get("AfterSale")),
                boolOrTrue(auditRow.get("PromiseGenuine")), boolOrTrue(auditRow.get("PromiseReturn7")),
                boolOrTrue(auditRow.get("PromiseFastRefund")), boolOrTrue(auditRow.get("PromiseFreightIns"))));

        Integer lastId = goodsDao.lastInsertId();
        long goodsId = lastId == null ? 0L : lastId.longValue();

        goodsDao.insertProductsales(str(auditRow.get("MerchantId")),
                str(auditRow.get("GoodName")), str(auditRow.get("ProductCode")));

        // 多规格矩阵：把提审单里的 SkuSpec 快照落进 goods_sku（父 Id = 刚建的 goods.Id）。
        // ⚠️ 只能在这里写：提审制下 goods 行本就是此处才建，SKU 的父 Id 只有此刻才知道。
        writeSkusFromAudit(goodsId, auditRow);

        return goodsId;
    }

    /**
     * 把提审单的 {@code SkuSpec} 快照（JSON 数组串，元素 {@code {spec,price,stock,code}}）解析后
     * 逐条写进 {@code goods_sku}。
     *
     * <p>⚠️ <b>解析失败一律「当没有多规格」处理、绝不抛</b>：审核通过是**不可回退**的关键路径
     * （CAS 已消费），不能因为一段可选的、商家手填的矩阵文本格式不对，就把整次「通过」搞成失败。
     * 空 / 空白 / 非数组 ⇒ 直接不写（该商品就是单规格，与今日行为一致）。
     */
    private void writeSkusFromAudit(long goodsId, Map<String, Object> auditRow) {
        String skuSpec = str(auditRow.get("SkuSpec"));
        if (isBlank(skuSpec) || goodsId <= 0) {
            return;
        }
        List<Map<String, Object>> raw;
        try {
            raw = objectMapper.readValue(skuSpec, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            // 见方法注释：可选数据，坏了就跳过，不阻断「通过」。
            e.printStackTrace();
            return;
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }
        List<GoodsDao.SkuRow> rows = new java.util.ArrayList<>();
        int i = 0;
        for (Map<String, Object> m : raw) {
            if (m == null) {
                continue;
            }
            String axis = str(m.get("spec"));
            if (isBlank(axis)) {
                continue;   // 无组合名的行没有意义（唯一键也是按它）
            }
            rows.add(new GoodsDao.SkuRow(axis, toDouble(m.get("price")), toInteger(m.get("stock")),
                    str(m.get("code")), i++));
        }
        if (!rows.isEmpty()) {
            goodsDao.insertSkus(goodsId, str(auditRow.get("MerchantId")), str(auditRow.get("GoodName")), rows);
        }
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(v.toString());
    }

    private static Integer toInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(v.toString());
    }

    private static int toInt(Object v, int fallback) {
        Integer i = toInteger(v);
        return i == null ? fallback : i;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 服务承诺位的取值归一：{@code null} ⇒ {@code true}。
     *
     * <p>⚠️ 为什么要「缺省为真」：这些列**默认 1**（详情页 `.promise` 改前对每件商品都显示四条），
     * 老前端不传这 4 个参数 ⇒ Spring 绑成 {@code null} ⇒ 若按 {@code false} 处理，会把「没传」
     * 误读成「全不勾」⇒ 老客户端上架的商品承诺条**全空**。取真才是「保持现状」。
     * 兼容 {@code Boolean}（参数直传）与 {@code Number}/{@code Boolean}（从库读回：
     * {@code tinyint(1)} 经 Connector/J 可能映射成 Boolean 或 Integer）。
     */
    private static boolean boolOrTrue(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(v.toString());
    }
}
