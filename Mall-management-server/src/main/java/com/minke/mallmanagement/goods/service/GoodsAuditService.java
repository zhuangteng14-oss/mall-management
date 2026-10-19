package com.minke.mallmanagement.goods.service;

import com.minke.mallmanagement.shared.web.PageLimits;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.goods.dao.GoodsAuditDao;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.marketing.coupon.dao.CouponDao;
import com.minke.mallmanagement.marketing.coupon.service.CouponService;
import com.minke.mallmanagement.notification.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    /** 列表默认条数 / 上限（与 {@code MerchantApplicationService} 同口径）。 */
    public static final int DEFAULT_LIMIT = PageLimits.DEFAULT_PAGE_SIZE;
    public static final int MAX_LIMIT = PageLimits.MAX_PAGE_SIZE;

    private final GoodsAuditDao auditDao;
    private final GoodsDao goodsDao;
    private final GoodsCacheService goodsCacheService;
    /**
     * 券定义读写口 —— **只服务「带券的提审单」**（C3 块 5 · A 组）。
     *
     * <p>为什么提审链要认识券：商家在「更改商品」里挂的**商品券**随商品一起提审，它没有自己的
     * 待审表 —— 待审态就落在 {@code coupon.Status='stopped'} 上，业务号由提审批次号派生
     * （{@link CouponService#pendingCouponId}）。故审核链要能：① 判断这条提审单带不带券
     * （{@code selectCoupon('CP'+BatchId) != null}）、② 通过时把它翻 {@code active}。
     * ⚠️ 判别**不借 {@code goods_audit.Source}** —— 那一列已被「11 张表种子标记列」占用
     * （{@code mall_management_sql.sql:1102-1124} 的 {@code Source='seed'} 口径）。
     */
    private final CouponDao couponDao;
    /**
     * 解析 {@code goods_audit.SkuSpec}（多规格矩阵快照，JSON 数组串）—— 与 {@code order.Items} /
     * {@code OrderService.resolveItems} 同款：线上用的就是 Spring Boot 自动配置的那个 ObjectMapper，
     * 不另建（那会波及既有端点的字节）。
     */
    private final ObjectMapper objectMapper;
    /**
     * 低库存告警的投递口（批四第二批）。{@link NotificationService#alertLowStock} 是 fail-soft
     * 原语 —— 审核通过 / 改库存后调用，**不影响**主流程成败。
     */
    private final NotificationService notificationService;

    public GoodsAuditService(GoodsAuditDao auditDao,
                             GoodsDao goodsDao,
                             GoodsCacheService goodsCacheService,
                             CouponDao couponDao,
                             ObjectMapper objectMapper,
                             NotificationService notificationService) {
        this.auditDao = auditDao;
        this.goodsDao = goodsDao;
        this.goodsCacheService = goodsCacheService;
        this.couponDao = couponDao;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
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
                                      Double price, Double originalPrice, String goodType, Integer goodTypeNew,
                                      Integer typeId, String productCode, String goodImg, String detail, String spec, String afterSale,
                                      String skuSpec, Boolean promiseGenuine, Boolean promiseReturn7, Boolean promiseFastRefund, Boolean promiseFreightIns) {
        // 旧签名（券提审路径等）：恒 pending、无多图快照 —— 与改前逐字一致。
        return submit(merchantId, batchId, goodName, price, originalPrice, goodType, goodTypeNew, typeId,
                productCode, goodImg, null, detail, spec, afterSale, skuSpec,
                promiseGenuine, promiseReturn7, promiseFastRefund, promiseFreightIns, STATUS_PENDING);
    }

    /**
     * 新签名（批四第二批）：多带 {@code imgList}（多图 JSON 数组）与 {@code status}
     * （{@link #STATUS_DRAFT} 草稿 / {@link #STATUS_PENDING} 提审）。
     *
     * <p>提醒：草稿也走本方法、也在 {@code goods_audit} 里落一行 —— 「提审制下 goods 表不出现
     * 未通过的商品」这条不变量不变。
     */
    @Transactional
    public Map<String, Object> submit(String merchantId, String batchId, String goodName,
                                      Double price, Double originalPrice, String goodType, Integer goodTypeNew,
                                      Integer typeId, String productCode, String goodImg, String imgList,
                                      String detail, String spec, String afterSale,
                                      String skuSpec, Boolean promiseGenuine, Boolean promiseReturn7,
                                      Boolean promiseFastRefund, Boolean promiseFreightIns, String status) {
        // 第三批㉖：不带折扣模板快照的旧签名 —— 逐字沿用改前行为。
        return submit(merchantId, batchId, goodName, price, originalPrice, goodType, goodTypeNew, typeId,
                productCode, goodImg, imgList, detail, spec, afterSale, skuSpec,
                promiseGenuine, promiseReturn7, promiseFastRefund, promiseFreightIns, status,
                null, null, null, null);
    }

    /**
     * 第三批㉖：带折扣模板快照的新签名。快照来自 {@code DiscountService.snapshotForSubmit}
     * （服务端反算），此处**只负责落库**，不再计算 —— 计算与落库分开，审核通过才能原样搬运。
     */
    @Transactional
    public Map<String, Object> submit(String merchantId, String batchId, String goodName,
                                      Double price, Double originalPrice, String goodType, Integer goodTypeNew,
                                      Integer typeId, String productCode, String goodImg, String imgList,
                                      String detail, String spec, String afterSale,
                                      String skuSpec, Boolean promiseGenuine, Boolean promiseReturn7,
                                      Boolean promiseFastRefund, Boolean promiseFreightIns, String status,
                                      Long discountTemplateId, String discountType,
                                      java.math.BigDecimal discountRate, java.math.BigDecimal discountAmount) {
        Map<String, Object> result = new HashMap<>();
        Integer effectiveTypeId = typeId != null ? typeId : goodsDao.selectTypeIdByName(goodType);
        String effectiveStatus = isBlank(status) ? STATUS_PENDING : status;

        auditDao.insert(merchantId, batchId, goodName, price, originalPrice, goodType, goodTypeNew, effectiveTypeId,
                productCode, goodImg, imgList, detail, spec, afterSale, skuSpec, LocalDateTime.now(),
                boolOrTrue(promiseGenuine), boolOrTrue(promiseReturn7), boolOrTrue(promiseFastRefund), boolOrTrue(promiseFreightIns),
                effectiveStatus,
                discountTemplateId, discountType, discountRate, discountAmount,
                discountTemplateId == null ? null : LocalDateTime.now());

        result.put("success", true);
        result.put("batchId", batchId);
        result.put("status", effectiveStatus);
        return result;
    }

    /**
     * 商家把草稿提交审核：{@code draft → pending} 的 CAS（幂等）。
     * {@code 0} 行 = 不存在 / 不是自己的 / 已提交过 —— 回 {@code success:false} 并给明确文案。
     */
    @Transactional
    public Map<String, Object> submitDraft(String merchantId, Long id) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(merchantId) || id == null || id <= 0) {
            result.put("success", false);
            result.put("msg", "缺少参数：MerchantId / Id");
            return result;
        }
        int affected = auditDao.casSubmitDraft(id, merchantId);
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "草稿不存在、不属于当前商家或已提交过");
            return result;
        }
        Map<String, Object> row = auditDao.selectById(id);
        result.put("success", true);
        result.put("status", STATUS_PENDING);
        if (row != null) {
            result.put("batchId", str(row.get("BatchId")));
            result.put("goodName", str(row.get("GoodName")));
        }
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

    /**
     * 管理端提审列表（{@code status} 可空 ⇒ 全部）。
     *
     * <p>⚠️ C3 块 5 · A 组：**带券的提审单**在这里回显它挂的那张券（键 {@code Coupon}）——
     * 审核人得先看见券面，才谈得上「一并审券」。判别 / 取法见 {@link #couponOfAuditRow}。
     */
    public Map<String, Object> listForAdmin(String status, Integer limit) {
        int effective = normalizeLimit(limit);
        List<Map<String, Object>> rows = auditDao.selectByStatus(status, effective);
        for (Map<String, Object> row : rows) {
            Map<String, Object> coupon = couponOfAuditRow(row);
            if (coupon != null) {
                row.put("Coupon", coupon);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", rows);
        result.put("total", auditDao.countByStatus(status));
        result.put("limit", effective);
        return result;
    }

    /**
     * 这条提审单挂的券 —— 由 {@code BatchId} 派生业务号（{@link CouponService#pendingCouponId}）
     * 反查 {@code coupon}；查不到 ⇒ {@code null}（= 这是一条**普通商品提审单**）。
     *
     * <p>⚠️ 这就是「零 DDL 挂接」的全部实现：判别与取券**同一个查询**，不依赖任何新增列。
     */
    private Map<String, Object> couponOfAuditRow(Map<String, Object> auditRow) {
        String batchId = str(auditRow.get("BatchId"));
        if (isBlank(batchId)) {
            return null;
        }
        return couponDao.selectCoupon(CouponService.pendingCouponId(batchId));
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
            // 批四第二批：**对已有 goods 行的复审**被驳回 ⇒ 同步 goods.Status=3（驳回态）。
            // 首次提审的 GoodsId 恒 NULL（商品还没进 goods 表）⇒ 不凭空插行，驳回态只在
            // goods_audit 里（提审制的定义，见类注释）。
            Integer goodsId = toInteger(before.get("GoodsId"));
            if (goodsId != null && goodsId > 0) {
                goodsDao.updateGoodsStatus(goodsId, 3);
                goodsCacheService.evictByMerchant(merchantId);
            }
            result.put("success", true);
            result.put("outcome", "rejected");
            result.put("merchantId", merchantId);
            result.put("rejectReason", rejectReason);
            return result;
        }

        // ⚠️ C3 块 5 · A 组：**带券的提审单**走另一条通过腿 —— 它不代表「新增商品」，
        //    只是那张待审商品券（Status='stopped'）的载体（商家在「更改商品」里挂券时，
        //    goods 行**当场就改好了**，没有待建的商品）。若照下面走 writeGoodsFromAudit，
        //    会给一件**已经存在**的商品再插一行 goods ⇒ 重复商品。
        Map<String, Object> pendingCoupon = couponOfAuditRow(before);
        if (pendingCoupon != null) {
            // 待审 ⇒ 生效。updateStatus 谓词带 `Status <> ?` ⇒ 重复调用是幂等无操作。
            couponDao.updateStatus(CouponService.pendingCouponId(str(before.get("BatchId"))), "active");
            result.put("success", true);
            result.put("outcome", "approved");
            result.put("merchantId", merchantId);
            result.put("couponId", pendingCoupon.get("CouponId"));
            result.put("isCoupon", true);
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
                toDouble(auditRow.get("OriginalPrice")),
                str(auditRow.get("GoodImg")),
                str(auditRow.get("GoodType")),
                str(auditRow.get("ProductCode")),
                toInt(auditRow.get("GoodTypeNew"), 0),
                toInteger(auditRow.get("TypeId")), str(auditRow.get("Detail")), str(auditRow.get("Spec")), str(auditRow.get("AfterSale")),
                boolOrTrue(auditRow.get("PromiseGenuine")), boolOrTrue(auditRow.get("PromiseReturn7")),
                boolOrTrue(auditRow.get("PromiseFastRefund")), boolOrTrue(auditRow.get("PromiseFreightIns")),
                str(auditRow.get("ImgList")), 2));

        Integer lastId = goodsDao.lastInsertId();
        long goodsId = lastId == null ? 0L : lastId.longValue();

        // 第三批㉖：折扣模板快照原样搬进 goods（提审时服务端已反算好，此处不重算）。
        // 与 insertGoods 同事务 ⇒ 不会留下「商品在、快照丢」的半完成态。
        goodsDao.updatePriceSnapshot(goodsId,
                toLong(auditRow.get("DiscountTemplateId")),
                str(auditRow.get("DiscountType")),
                toBigDecimal(auditRow.get("DiscountRate")),
                toBigDecimal(auditRow.get("DiscountAmount")),
                toLocalDateTime(auditRow.get("PriceSnapshotAt")));

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
            // 批四第二批：上架时库存已低于阈值 ⇒ 给商家一条站内信（每 SKU 每日最多一条）。
            for (GoodsDao.SkuRow row : rows) {
                notificationService.alertLowStock(str(auditRow.get("MerchantId")), row.skuCode(),
                        row.axisName(), str(auditRow.get("GoodName")), row.stock());
            }
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

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(v.toString());
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(v.toString());
    }

    /** {@code goods_audit.PriceSnapshotAt} 经 {@code DATE_FORMAT} 投影成字符串，这里解回。 */
    private static LocalDateTime toLocalDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime dt) {
            return dt;
        }
        return LocalDateTime.parse(v.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
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
