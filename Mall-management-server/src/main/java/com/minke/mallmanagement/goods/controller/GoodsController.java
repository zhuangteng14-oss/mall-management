package com.minke.mallmanagement.goods.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.goods.service.GoodsService;
import com.minke.mallmanagement.goods.service.GoodsTypeCacheService;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


/**
 * 商品域的 HTTP 适配器 —— 9 个商品端点。
 *
 * <p>2026-10-02（笔 2）：本类此前同时承载商品 9 + 订单 8 共 17 个端点，订单那 8 个已拆到
 * {@link com.minke.mallmanagement.order.controller.OrderController}；类级前缀同时从
 * {@code /mysqlGoods} 规范化为 {@code /goods}，旧名 {@code MallManagementGoodsController}
 * 一并弃用。三件事（拆、换前缀、改名）同源 —— 任一处单独进 main 都会造成可观测断裂
 * （前端硬编码 URL 404 / 类名引用悬空），故同笔完成。契约见 docs/agent/api-contract.md。
 *
 * <p>2026-10-02（三层重构第二笔，商品域）：<b>8 个方法体整段搬进
 * {@link GoodsService}</b>（数据访问再下沉 {@link com.minke.mallmanagement.goods.dao.GoodsDao}）。
 * 本类只保留**路由与入参绑定** —— 形参名与顺序**逐字保留**（{@code @RequestParam} 多数未写显式名，
 * 全靠形参名解析，{@code -parameters} 已开），方法签名连 {@code throws IOException} 也原样不动。
 *
 * <p>⚠️ <b>本类不再持有 {@code JdbcTemplate} / {@code GoodsCacheService} / {@code app.upload-dir}</b>：
 * 8 条内联 SQL 进了 {@code GoodsDao}，缓存失效进 {@code GoodsService}，上传目录与落盘也在那里。
 * 剩下的三个协作者各司其职：
 * <ul>
 *   <li>{@link GoodsService} —— 8 个端点的业务入口；</li>
 *   <li>{@link GoodsTypeCacheService} —— **只服务 {@code goodstype} 一个端点**，
 *       因为它的 GET/POST 分支判定按裁决留在本类（见 {@link #goodstype}）。</li>
 *   <li>{@link AuditService} —— **C3 批 A 增**：审计写入，只服务 {@code goodsUp} 一个端点（「横切所有写操作」的样板挂钩，其余 15 个写端点随各自那笔挂，见 {@code docs/agent/c3-modules.md} §11）。</li>
 * </ul>
 *
 * <h2>【响应体：8 个商品端点已换成 {@link Result}；{@code goodstype} 例外】</h2>
 * 只换**返回类型**：{@code Map<String,Object>} → {@code Result<Map<String,Object>>}，
 * 方法体由 {@code return goodsService.x(…)} 改为 {@code return Result.of(goodsService.x(…))}。
 * Service 返回值仍是 {@code Map}，URL、参数、{@code throws IOException} 一字未动；
 * 因 {@code Result.of} 逐条 put 保字节等价，线上响应体逐字节不变。
 * ⚠️ {@code goodstype} **不换**（{@link Result} 类注释的例外 4/5），理由见该方法处注释。
 *
 * <h2>【多账户隔离笔（2026-10-06）：4 个端点加**入参身份校验**】</h2>
 * {@code goodsAdd} / {@code goodsSelect} / {@code goodsDe} / {@code goodsUp} 的
 * {@code MerchantId} 是「**调用者自己**」⇒ 方法体第一行过
 * {@link IdentityGuard#requireSelfOrAdmin}，拒则 403（返回类型因此由
 * {@code Result<Map<String,Object>>} 改为 {@code ResponseEntity<Object>}）。
 * ⚠️ <b>另两个端点刻意不加</b>：{@code productCodePrice} 与 {@code GoodsProductCodeSelect}
 * 的 {@code MerchantId} 是「**被浏览的商家**」（商品页在看别人的店），不是调用者 ——
 * 判它们会把正常的浏览打断。二者**也在白名单里**，语义上属公开面。
 * 判据全文见 {@link IdentityGuard}。
 */
@RestController
@RequestMapping("/goods")
public class GoodsController {

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private GoodsTypeCacheService goodsTypeCacheService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private NotificationService notificationService;

    /** 管理端共享收件箱的收件人标识（与块 2 的 `MerchantApplicationController` 同口径）。 */
    private static final String ADMIN_INBOX_ID = "*";
    private static final String TO_TYPE_ADMIN = "admin";

    //这是一个接口（用于提供和更新上架商品的可选类型）
    // ⚠️ 本方法是全类**唯一**没有随三层重构下沉的方法 —— 裁决③：GET/POST 的分支判定权
    //    留在 Controller（路由与「GET 带 GoodType 参数走写分支」等边角行为均不变）。
    //    缓存注解在 Service 上（@Cacheable 只覆盖 listTypes()，写分支不在其方法体内）。
    // ⚠️ 例外 4/5：返回 Object 而非 Map —— GET/POST **双形态**（GoodType == null → {success,list}；
    //    否则 → {success,msg}），无法用单一 Result<Map> 表达。别「顺手统一」。见 Result 类注释。
    // ⚠️ C3 块 3：GET 读分支的返回列已 + ParentId/Sort/State（缓存 Service 侧改）；POST 写分支
    //    多接一个可选 `ParentId`（空 ⇒ 0=一级），供建二级类目 —— 老调用方不传照旧。
    @RequestMapping(value = "/goodstype", method = {RequestMethod.GET, RequestMethod.POST})
    public Object goodstype(
            @RequestParam(required = false) String GoodType,
            @RequestParam(required = false) Integer GoodTypeNew,
            @RequestParam(required = false) Integer ParentId
    ) {
        // GET 请求：查询所有类型
        // 分支判定权保留在此处 —— 路由与「GET 带 GoodType 参数走写分支」等边角行为均不变。
        // 缓存注解在 Service 上（@Cacheable 只覆盖 listTypes()，写分支不在其方法体内）。
        if (GoodType == null) {
            return goodsTypeCacheService.listTypes();
        }

        // POST 请求：新增类型
        return goodsTypeCacheService.addType(GoodType, GoodTypeNew, ParentId);
    }

    //这是一个接口用于上架商品
    // 方法体（上传编排 + 两条早退 + 三条语句 + 全部文案 + 唯一出口前的 evict）逐字搬到
    // GoodsService.goodsAdd()，SQL 再下沉 GoodsDao。本方法只保留路由与入参绑定。
    // ⚠️ 提前 return 分支（图片为空 / 落盘失败）**不再清缓存** —— 该路径一行未改库，
    //    清它是纯浪费。这是**设计选择，非缺陷**，见 §11；该不变量随方法体进了 Service。
    // 笔 3：只对 4 个**字符串**写参做非空校验。刻意**不碰**三个非字符串入参：
    //   · file（MultipartFile）—— 非空由 @RequestParam 必填本身保证（缺失即 400）；
    //   · Price（Double）—— 类型/范围属业务规则，归 Service；
    //   · GoodTypeNew（int）—— ⚠️ **绝不能加 @Min(1)**：既有 smoke 用例与 A3 都发 0
    //     （GoodsAddUploadFailSmokeTest.java:52、ConcurrencyRunner.java:1164-1166 的 PayIf 族同理）。
    @PostMapping("/goodsAdd")
    public ResponseEntity<Object> goodsAdd(
            @NotBlank(message = "不能为空") @RequestParam("MerchantId") String MerchantId,
            @NotBlank(message = "不能为空") @RequestParam("GoodName") String GoodName,
            @RequestParam("Price") Double Price,
            @RequestParam("file") MultipartFile file,  // 图片用 MultipartFile
            @NotBlank(message = "不能为空") @RequestParam("GoodType") String GoodType,
            @NotBlank(message = "不能为空") @RequestParam("ProductCode") String ProductCode,
            @RequestParam("GoodTypeNew") int GoodTypeNew,
            @RequestParam(value = "TypeId", required = false) Integer TypeId, @RequestParam(value = "Detail", required = false) String Detail, @RequestParam(value = "Spec", required = false) String Spec, @RequestParam(value = "AfterSale", required = false) String AfterSale,
            // 可视化段补丁：多规格矩阵快照（JSON 数组串）+ 4 个服务承诺位。
            // ⚠️ 四项承诺 `required = false` **且**缺省按「真」处理（见 `GoodsAuditService.boolOrTrue`）——
            //    老前端不传 ⇒ null ⇒ 当真 ⇒ 与改前「每件商品都显示四条承诺」一致（硬线：不动旧契约）。
            @RequestParam(value = "SkuSpec", required = false) String SkuSpec,
            @RequestParam(value = "PromiseGenuine", required = false) Boolean PromiseGenuine,
            @RequestParam(value = "PromiseReturn7", required = false) Boolean PromiseReturn7,
            @RequestParam(value = "PromiseFastRefund", required = false) Boolean PromiseFastRefund,
            @RequestParam(value = "PromiseFreightIns", required = false) Boolean PromiseFreightIns,
            HttpServletRequest request
    ) throws IOException {
        // ⚠️ 第一行：上架只能挂到**自己**名下（管理员豁免）—— 必须在落盘/写库之前。
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        // ═══ C3 块 3：提审制的四步钩子（照 GoodsController.goodsUp 的形状）═══
        // 业务（本方法）改为「落一条 goods_audit(pending)，不写 goods 行」；
        // 只有提交成功才**触达管理端**（共享收件箱）+ 记账。
        Map<String, Object> result = goodsService.goodsAdd(MerchantId, GoodName, Price, file,
                GoodType, ProductCode, GoodTypeNew, TypeId, Detail, Spec, AfterSale, SkuSpec,
                PromiseGenuine, PromiseReturn7, PromiseFastRefund, PromiseFreightIns);
        if (Boolean.TRUE.equals(result.get("success"))) {
            String batchId = str(result.get("batchId"));
            notificationService.send(UUID.randomUUID().toString(),
                    ADMIN_INBOX_ID, TO_TYPE_ADMIN,
                    NotificationEvents.GOODS_APPLY_SUBMITTED,
                    "新的商品上架申请",
                    "商家「" + MerchantId + "」提交了商品「" + GoodName + "」的上架申请，待审核。",
                    "goods_audit", batchId);
            // 新增类动作：BeforeJson 恒 NULL；TargetId 用 BatchId（跨重导稳定，同块 2 口径）。
            Map<String, Object> after = new HashMap<>();
            after.put("MerchantId", MerchantId);
            after.put("GoodName", GoodName);
            after.put("Price", Price);
            after.put("GoodType", GoodType);
            after.put("ProductCode", ProductCode);
            auditService.record(AuthSession.identify(request), AuditActions.GOODS_APPLY_CREATE,
                    "goods_audit", batchId, null, after);
        }
        return ResponseEntity.ok(Result.of(result));
    }

    //这是一个接口（用于显示商家端查看商品页面外部内容）
    // 三层前方法体已搬到 GoodsCacheService.listByMerchant()；本笔再上移一层：
    // Controller → GoodsService（纯委托）→ GoodsCacheService（缓存注解与 SQL 的落点）。
    // 为什么多这一跳：本域对「Controller 只认 Service」取齐，且缓存读与写共用一条 evict 面 ——
    // 见 GoodsService 类注释。
    // ⚠️ 形参名 MerchantId 必须保留：@RequestParam 未写显式名依赖它解析，
    //    Service 侧 key="#MerchantId" 也依赖同一个形参名（-parameters 已开）。
    @PostMapping("/goodsSelect")
    public ResponseEntity<Object> goodsSelect(
            @RequestParam String MerchantId,
            HttpServletRequest request
    ) {
        // ⚠️ 第一行：商家端「我的商品」只能看自己的（管理员豁免）。
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(goodsService.goodsSelect(MerchantId)));
    }

    //这是一个接口（用于返回商品对应的所有信息）
    // 方法体与缓存注解在 GoodsCacheService.listHome()；本笔再上移一层（纯委托）。
    @PostMapping("/goodsSelectHome")
    public Result<Map<String, Object>> goodsSelectHome() {
        return Result.of(goodsService.goodsSelectHome());
    }

    // 这是一个接口（用于下架商品）
    // 方法体（两条 DELETE、无条件 success、唯一出口前 evict）逐字搬到 GoodsService.goodsDe()。
    // ⚠️ 四条 evict 的理由已收敛到 GoodsCacheService.evictByMerchant() 的 javadoc（D 笔，2026-09-28）。
    @PostMapping("/goodsDe")
    public ResponseEntity<Object> goodsDe(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("ProductCode") String ProductCode,
            HttpServletRequest request
    ) {
        // ⚠️ 第一行：下架只能下自己的（管理员豁免）—— 必须在两条 DELETE 之前。
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(goodsService.goodsDe(MerchantId, GoodName, ProductCode)));
    }

    //这是一个接口（用于根据商品的不同型号切换显示对应的价格）
    // 【2026-09-30】try/catch 与响应组装曾**搬回本方法**（让 Service 侧能技术异常上抛）；
    // 【2026-10-02 本笔】再搬进 GoodsService.productCodePrice() —— 判据是「catch 不能与
    // @Cacheable **同一个 bean**」，而 GoodsService 与 GoodsCacheService 是两个 bean，
    // 异常照常逃出被缓存的方法、缓存拦截器照常不写入 ⇒ 不违反那条决定。
    // ⚠️ 该推理写在 GoodsService 类注释里：**谁把 catch 挪回 GoodsCacheService 类内
    //    （哪怕另起一个方法再自调用），缓存就会静默失效**（cache-layer.md §11 ㊸③）。
    // ⚠️ 形参名必须保留：@RequestParam 与 Service 侧 SpEL 的三段都依赖它们（-parameters 已开，
    //    javap 该方法的 MethodParameters 可见 MerchantId / GoodName / ProductCode）。
    // 笔 3：非空校验。⚠️ 缺参**仍走 400**（@RequestParam 必填先于校验触发），
    // 故 PriceTripleFailSmokeTest.missingParamStaysABadRequest 继续绿。
    @PostMapping("/productCodePrice")
    public Result<Map<String, Object>> productCodePrice(
            @NotBlank(message = "不能为空") @RequestParam("MerchantId") String MerchantId,
            @NotBlank(message = "不能为空") @RequestParam("GoodName") String GoodName,
            @NotBlank(message = "不能为空") @RequestParam("ProductCode") String ProductCode
    ) {
        return Result.of(goodsService.productCodePrice(MerchantId, GoodName, ProductCode));
    }

    /**
     * 某商家某商品的多规格矩阵（{@code goods_sku}）—— 商品详情页 / 上架页读回用（可视化段补丁）。
     *
     * <p>⚠️ <b>新端点是纯新增</b>：不改任何既有端点的入参 / 出参（硬线：不动旧契约）。
     * 空 {@code list} = 该商品没有多规格 ⇒ 前端回落到既有的「单型号 {@code ProductCode}」路径。
     * ⚠️ 与 {@code productCodePrice} 一样**不做身份闸**：读的是公开可见的商品信息（商家号 + 商品名
     * 本来就是买家首页列表里就有的字段），加闸反而会让未登录的浏览路径拿不到规格。
     */
    @PostMapping("/skuList")
    public Result<Map<String, Object>> skuList(
            @NotBlank(message = "不能为空") @RequestParam("MerchantId") String MerchantId,
            @NotBlank(message = "不能为空") @RequestParam("GoodName") String GoodName
    ) {
        return Result.of(goodsService.skusByMerchantGoodName(MerchantId, GoodName));
    }

    //这是一个接口（用于更新商品数据）
    // 方法体（上传编排 + 两条语句 + 全部文案 + 唯一出口前 evict）逐字搬到 GoodsService.goodsUp()。
    // ⚠️ 正是本方法的**改名**使 goodsCodes / goodsPrice 的失效只能 allEntries（键含 GoodName，
    //    而 WHERE 是 (MerchantId, ProductCode)，旧名构造不出）。见 §11「精确 evict 的边界」。
    // ⚠️ 形参 GoodType 是既有入参、方法体内未使用 —— 签名必须原样保留（前端在传，去掉即 400）。
    // ⚠️ 签名里的 throws IOException 与三层前一致（落盘异常已在 Service 侧 catch，本类不会抛）。
    @PostMapping("/goodsUp")
    public ResponseEntity<Object> goodsUp(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName,
            @RequestParam("Price") Double Price,
            @RequestParam("GoodType") String GoodType,
            @RequestParam("ProductCode") String ProductCode,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request
    ) throws IOException {
        // ⚠️ 第一行：改价/改图只能改自己的（管理员豁免）—— 必须在落盘与 UPDATE 之前。
        //    这是本笔要堵的那一行 curl（改别人的商品）的正面判据。
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        // ═══ C3 批 A：审计的**样板挂钩**（其余 15 个写端点照这个形状挂）═══
        // 四步：改前快照 → 业务 → 判成功 → 改后快照 + 记账。
        // （1）改前快照：必须在 UPDATE **之前**取；谓词与 goodsDao.updateGoods 的 WHERE 逐字相同。
        List<Map<String, Object>> before = goodsService.snapshotGoodsForAudit(MerchantId, ProductCode);
        // （2）业务。goodsUp 自带 @Transactional —— 它返回时事务已提交，「改后」读得到新值。
        Map<String, Object> result = goodsService.goodsUp(MerchantId, GoodName, Price, GoodType, ProductCode, file);
        // （3）**只有 success=true 才记**：图片为空/落盘失败两条早退路径一行未改库，无「改」可记。
        if (Boolean.TRUE.equals(result.get("success"))) {
            // （4）改后快照 + 记账。⚠️ 本类**无事务** ⇒ 记账是一条独立的自动提交 INSERT：
            //     业务若是回滚的（不走到这里），审计不会留下一条「其实没发生」的行；
            //     审计自身失败也只记 ERROR 日志（AUDIT），**不会**把成功翻成 500。见 AuditService。
            // ⚠️ targetType 字面量 "goods" 必须与 AuditActions.GOODS_UPDATE 的点号**前段**一致
            //    （tag 口径 = `<TargetType>.<动作>`）；TargetId 用 `MerchantId:ProductCode`
            //    而不是自增 Id —— 审计表跨重导保留，而 goods.Id 会随全量重导变。
            List<Map<String, Object>> after = goodsService.snapshotGoodsForAudit(MerchantId, ProductCode);
            auditService.record(AuthSession.identify(request), AuditActions.GOODS_UPDATE, "goods",
                    MerchantId + ":" + ProductCode, before, after);
        }
        return ResponseEntity.ok(Result.of(result));
    }

    // 这是一个接口（根据商家ID和商品名称，获取所有不同的 ProductCode 型号列表）
    // 方法体与缓存注解在 GoodsCacheService.listCodes()；本笔再上移一层（纯委托）。
    // ⚠️ 形参名必须保留：@RequestParam 未写显式名依赖它解析，Service 侧
    //    key="#MerchantId + '::' + #GoodName" 也依赖同一组形参名（-parameters 已开）。
    @PostMapping("/GoodsProductCodeSelect")
    public Result<Map<String, Object>> GoodsProductCodeSelect(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("GoodName") String GoodName
    ) {
        return Result.of(goodsService.GoodsProductCodeSelect(MerchantId, GoodName));
    }


    //这是一个接口（用于商品搜索页的内容搜索信息返回）
    // 方法体（含 keyword 的 null 归一）搬到 GoodsService.productSearch()，
    // 动态 SQL 的构造随之下沉 GoodsDao.searchByKeyword()。
    // ⚠️ 该 SQL 的两个已知问题（逐字 LIKE 拼串 + 无 schema 前缀）**本笔不修** —— 见 GoodsDao。
    @PostMapping("/productSearch")
    public Result<Map<String, Object>> productSearch(String keyword) {
        return Result.of(goodsService.productSearch(keyword));
    }

    // ═══ C3 块 3 新增：二级类目筛选 ═══
    // 按 **TypeId**（不是类目名）筛 —— 唯一键换代后「电子用品>配件」与「服装>配件」同名，名字不再唯一。
    // 只回买家可见的（ShelfState=1 且 DeletedAt 为空），故**不加** IdentityGuard（它就是公开浏览面，
    // 与 goodsSelectHome / productSearch 同类；判「自己的」会误伤正常浏览）。
    // ⚠️ 承载类目树的那个前端（GoodsSelect.vue 二级树）今天**还没接线** ⇒ 接的是新端点，
    //    不算改契约（既有的名字路径 HomePage chips / 搜索框一行不动）。
    @PostMapping("/categoryGoods")
    public Result<Map<String, Object>> categoryGoods(
            @RequestParam("TypeId") Integer TypeId,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return Result.of(goodsService.categoryGoods(TypeId, keyword));
    }

    // ═══ C3 块 3 新增：商家查自己的提审历史 / 状态 ═══
    // ⚠️ MerchantId 是「调用者自己」⇒ 第一行过 IdentityGuard（同 goodsSelect）。
    // 商家端「我的商品」状态列需并表：/goods/goodsSelect（已上架）∪ 本端点（待审 / 驳回）。
    @PostMapping("/myApplications")
    public ResponseEntity<Object> myApplications(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(goodsService.myApplications(MerchantId, limit)));
    }

    /** {@code Object → String}（审计快照用；{@code null} 原样返回）。 */
    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

}
