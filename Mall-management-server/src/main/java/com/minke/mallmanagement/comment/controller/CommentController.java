package com.minke.mallmanagement.comment.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.comment.service.CommentService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容域「评论 / 问答」Web 层（C3 块 6 · commit 1 评论 + commit 2 问答 + 整改 commit 4 图片审核
 * + 整改补笔 {@code /adminList}）—— <b>8 个端点</b>：列表（公开）/ **全站列表（管理端，第 14 条）** /
 * 发表（评论·回复·提问·回答共用）/ 删除 / **采纳答案** / 赞踩 / 我的历史订单 / **图片审核（管理端）**。
 *
 * <h2>【挂 {@code /comment}，与方案 §三 的端点清单逐字一致】</h2>
 * 方案 A4 与 {@code 块6参考.md §三} 定死了 13 个端点与其路径，本类占其中 7 个
 * （其余 6 个归 {@code /report/**} 与 {@code /sensitive/**} 两个新 Controller，
 * 见 {@code ReportController} / {@code SensitiveWordController}）。
 * <p>⚠️ <b>2026-10-13 起内容域是 14 条</b>：用户批准新增 {@link #adminList}
 * （{@code /comment/adminList}，管理端全站列表 —— 修「评论管理 / 问答管理两栏看不到全站待办」）。
 * 它**不在方案 A4 的 13 条里**，是本笔**唯一**一条经批准的契约新增；其余 6 个 Controller 的端点
 * 一条没动，公开面 {@code /comment/list} 的「必须给 {@code TargetId}」也没动。
 *
 * <h2>【{@code /comment/list} 是**公开**端点 ⇒ 已登记进 {@code WebAuthConfig.AUTH_WHITELIST}】</h2>
 * 商品详情页的评价区要能在**未登录**时读（那页本身是浏览面）。⚠️ 公开的是**读** ——
 * 列表中「这条是不是我发的」走 {@link AuthSession#currentUserId} 从**会话**取，
 * <b>不接受入参声明自己是谁</b>：那等于让人拿别人的登录名去问「这条匿名评论是不是你写的」，
 * 匿名就成了摆设（见 {@code CommentService.decorate} 的 ⚠️）。
 *
 * <h2>【6 个端点里 5 个走 {@code Account} 入参判据（登录名版）】</h2>
 * {@code comment.AuthorId} / {@code comment_like.UserId} 存的是**登录名**
 * （{@code login.UserName}），故走 {@link IdentityGuard#requireSelfOrAdminByUsername} ——
 * 不是业务号版的 {@code requireSelfOrAdmin}。⚠️ 两者的区别与「双 403 事故」见
 * {@code CouponController} 类注释那一节，同一个坑。
 *
 * <h2>【⚠️ {@code UserStateLookup} 一律走 {@code ObjectProvider}】</h2>
 * 它刻意不被 {@code AbstractSmokeTest} {@code @Import}（见该文件那条 ⚠️）
 * ⇒ 裸注入会让整个 {@code @WebMvcTest} 切片在**上下文加载期**就红。取不到时
 * {@code getIfAvailable()} 回 {@code null}，{@code requireSelfOrAdminByUsername} 的第三处
 * fail-open 接住它。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}；参数一律 {@code required=false}】</h2>
 * 同 {@code CartController} / {@code CouponController}：约束异常会逃逸成 400 空体，
 * 且撞 {@code xxxControllerIsNotAopProxied} 断言族。
 *
 * <h2>【⚠️ 新增 Controller 必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 本类依赖 {@code CommentController → CommentService → CommentDao}，两个 bean 都要登记，
 * 否则切片在上下文加载期红（这是本仓**第 13 次**同一处地雷，见该文件里的计数）。
 * <p>⚠️ 2026-10-13 整改补笔又给本类加了一个构造参数 {@link AuditService}（图片审核留痕，
 * 见 {@link #auditImageReview}）—— 那个 bean **已在**该文件的 {@code @Import} 里（块 1 起就在），
 * 故这次不需要动它；地雷形状不变：给 Controller 加构造依赖 = 那个 bean 必须在切片里拿得到。
 */
@RestController
@RequestMapping("/comment")
public class CommentController {

    private final CommentService commentService;

    /**
     * 审计写入口（2026-10-13 整改补笔）。⚠️ 与 {@code ReportController} / {@code SensitiveWordController}
     * 同款 —— 它**已在** {@code AbstractSmokeTest} 的 {@code @Import} 里（块 1 起就在），
     * 故这次不必动那个文件；但地雷的形状不变：给 Controller 加构造依赖 = 那个 bean 必须在切片里拿得到。
     */
    private final AuditService auditService;

    /** ⚠️ 刻意是 {@link ObjectProvider}，不是裸的 {@code UserStateLookup} —— 见类注释。 */
    private final ObjectProvider<UserStateLookup> userStateLookupProvider;

    public CommentController(CommentService commentService,
                             AuditService auditService,
                             ObjectProvider<UserStateLookup> userStateLookupProvider) {
        this.commentService = commentService;
        this.auditService = auditService;
        this.userStateLookupProvider = userStateLookupProvider;
    }

    /**
     * 评论列表（顶层 + 回复 + 统计卡）。**公开端点**，无 {@code Account} 入参。
     *
     * @param TargetType 评论对象类型（默认 {@code goods}）
     * @param TargetId   对象 ID —— 商品评论时是 {@code goods.Id}（前端商品页 {@code route.query.Id}）
     * @param Kind       {@code review}（评价）/ {@code question}（提问）；省略 = 不筛
     * @param Sort       {@code hot}（按点赞数）/ {@code new}（按时间，默认）
     * @param Solved     仅对提问有意义：{@code "1"} 已解决 / {@code "0"} 未解决
     * @param Limit      页大小（默认 20，上限 100）
     * @param Offset     偏移（默认 0）
     */
    @PostMapping("/list")
    public ResponseEntity<Object> list(
            @RequestParam(value = "TargetType", required = false) String TargetType,
            @RequestParam(value = "TargetId", required = false) String TargetId,
            @RequestParam(value = "Kind", required = false) String Kind,
            @RequestParam(value = "Sort", required = false) String Sort,
            @RequestParam(value = "Solved", required = false) String Solved,
            @RequestParam(value = "Limit", required = false) Integer Limit,
            @RequestParam(value = "Offset", required = false) Integer Offset,
            HttpServletRequest request
    ) {
        // ⚠️ 观看者只有**会话**一个来源（见类注释）—— 且给的只是**业务号**，
        //    「业务号 → 登录名」那一跳在 Service 里、**校验通过之后**才做
        //    （切片里 JdbcTemplate 是替身，提前查会把参数校验类的用例打成 NPE）。
        String viewerUserId = AuthSession.currentUserId(request);
        return ResponseEntity.ok(Result.of(commentService.list(
                TargetType, TargetId, Kind, Sort, Solved, Limit, Offset, viewerUserId)));
    }

    /**
     * <b>管理端</b>全站列表（{@code /comment/adminList}）—— 内容域的**第 14 条端点**，
     * 用户 2026-10-13 批的甲案（「评论管理 / 问答管理」两栏此前缺全站列表：公开面
     * {@code /comment/list} 强制要 {@code TargetId}，那条约束**不动**）。
     *
     * <h2>【逐字确认：本端点**不在** {@code WebAuthConfig.AUTH_WHITELIST}】</h2>
     * 白名单里 {@code /comment} 域只有 {@code /comment/list} 一条。本端点走
     * {@code AuthInterceptor}（注册在 {@code /**}）—— <b>闸开着（默认 profile / prod）时匿名请求
     * 收 401</b>，与 {@code imageReview} / {@code report.*} / {@code sensitive.*} 其余管理端端点同族。
     *
     * <h2>【⚠️ 但 a3 profile 下它匿名可达 —— 这是全仓同口径，不是本端点引入的漏洞】</h2>
     * a3 是 {@code app.auth.enabled=false}（闸关）⇒ 拦截器只补水身份、不做强制
     * （见 {@code WebAuthConfig} 的构造那一段）。<b>该 profile 下的每一个管理端端点都如此</b>
     * （{@code admin/**} 全部、{@code report/handle}、{@code sensitive/add|delete}、
     * {@code comment/imageReview} …）—— 故「a3 匿名能拉全站评论」既不是契约放宽、也不是本笔
     * 新开的口子，它是**验证环境**的既定形态。默认 profile 下的判据以 401 为准（手验清单里那条）。
     *
     * <h2>【不收 {@code Account}、也不挂 {@code requireAdmin}】</h2>
     * 与 {@link #imageReview} 同一处置：本仓管理端端点靠「路由受保护 + 会话」识别处理人，
     * **不由入参声明**。⚠️ 故这里**没有** {@code IdentityGuard} 调用 —— 无 {@code Account} 可判，
     * 加一个只会变成「谁都能传自己的名字」的假闸。
     *
     * <h2>【⚠️ 也不收观看者（会话身份）】</h2>
     * 与 {@link #list} 相反：那条要从会话取 {@code viewerUserId} 算 {@code Mine} / {@code MyLike}
     * （所以它必须转发 {@code AuthSession}）；本列表没有「我」这个视角
     * （{@code Mine} 恒 {@code false}），故**不读会话** —— 少一处就不必担心「会话里有谁」
     * 会改变响应内容。
     *
     * @param Kind       **必填**：{@code review} / {@code question} / {@code answer}（未知值拒）
     * @param TargetType / TargetId 可选**成对**收窄（都不传 = 全站；只传一个 ⇒ 拒）
     * @param Solved     仅 {@code Kind=question} 有意义：{@code "1"} / {@code "0"}（给别的 kind ⇒ 拒）
     * @param Limit      页大小（默认 20，上限 100）
     * @param Offset     偏移（默认 0）
     */
    @PostMapping("/adminList")
    public ResponseEntity<Object> adminList(
            @RequestParam(value = "Kind", required = false) String Kind,
            @RequestParam(value = "TargetType", required = false) String TargetType,
            @RequestParam(value = "TargetId", required = false) String TargetId,
            @RequestParam(value = "Solved", required = false) String Solved,
            @RequestParam(value = "Limit", required = false) Integer Limit,
            @RequestParam(value = "Offset", required = false) Integer Offset
    ) {
        return ResponseEntity.ok(Result.of(commentService.adminList(
                Kind, TargetType, TargetId, Solved, Limit, Offset)));
    }

    /**
     * 发表 —— 评论 / 回复 / 提问 / 回答**共用一个端点**（方案 §三的端点表逐字）。
     *
     * <p>四种形态靠 {@code ParentId} 与 {@code Kind} 区分，规则在
     * {@code CommentService.add}（顶层 {@code Kind} 吃入参、回复的 {@code Kind} 由父行推）。
     *
     * <p>⚠️ <b>图片走同一个请求的多 part，不另开端点</b>：方案 A4 是「13 个端点」，
     * 加第 14 个是要批的契约改动（⚠️ 该批准已于 2026-10-13 用于 {@link #adminList}，
     * 但**不是**给图片用的）；且评论文本与它的图本就是一次提交。
     * 故这里用 {@link MultipartHttpServletRequest#getFiles} 手工取 part，而不是
     * {@code @RequestParam List<MultipartFile>} —— 后者在**非 multipart** 请求（就是一条
     * 纯文字的评论）上的绑定行为随 Spring 版本而变，前者两种情况下都是「没有文件」。
     */
    @PostMapping("/add")
    public ResponseEntity<Object> add(
            @RequestParam(value = "Account", required = false) String Account,
            @RequestParam(value = "TargetType", required = false) String TargetType,
            @RequestParam(value = "TargetId", required = false) String TargetId,
            @RequestParam(value = "ParentId", required = false) String ParentId,
            @RequestParam(value = "ReplyToId", required = false) String ReplyToId,
            @RequestParam(value = "Kind", required = false) String Kind,
            @RequestParam(value = "Content", required = false) String Content,
            @RequestParam(value = "Rating", required = false) String Rating,
            @RequestParam(value = "Anonymous", required = false) String Anonymous,
            @RequestParam(value = "ProductCode", required = false) String ProductCode,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(commentService.add(Account, TargetType, TargetId,
                ParentId, ReplyToId, Kind, Content, Rating, Anonymous, ProductCode, filesOf(request))));
    }

    /**
     * 删一条评论（作者 / 管理员）。顶层的删除**连带**该楼回复，见 {@code CommentService.delete}。
     */
    @PostMapping("/delete")
    public ResponseEntity<Object> delete(
            @RequestParam(value = "Account", required = false) String Account,
            @RequestParam(value = "Id", required = false) Integer Id,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        if (Id == null) {
            return ResponseEntity.ok(Result.fail("缺少参数：Id"));
        }
        return ResponseEntity.ok(Result.of(commentService.delete(Id.longValue(), Account, isAdmin(request))));
    }

    /**
     * 采纳一条答案（问答，方案 §4.2）。
     *
     * @param Id      被采纳答案所在的**顶层提问** Id（{@code comment.Id}）
     * @param ReplyId 被采纳的那条**回复** Id —— ⚠️ 必须挂在 {@code Id} 这条提问下，
     *                否则 {@code CommentService.accept} 拒（「这条回答不属于该提问」）
     */
    @PostMapping("/accept")
    public ResponseEntity<Object> accept(
            @RequestParam(value = "Account", required = false) String Account,
            @RequestParam(value = "Id", required = false) Integer Id,
            @RequestParam(value = "ReplyId", required = false) Integer ReplyId,
            HttpServletRequest request
    ) {
        // ⚠️ 与 delete 同档：身份面判据（登录名版）在这里，**「是不是提问者」那一条判在 Service**
        //    —— 管理员也能过这道闸（可代采纳），而「只有提问者本人 / 管理员」是业务规则。
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        if (Id == null) {
            return ResponseEntity.ok(Result.fail("缺少参数：Id"));
        }
        if (ReplyId == null) {
            return ResponseEntity.ok(Result.fail("缺少参数：ReplyId"));
        }
        return ResponseEntity.ok(Result.of(commentService.accept(
                Id.longValue(), ReplyId.longValue(), Account, isAdmin(request))));
    }

    /**
     * 赞 / 踩一条评论。{@code Type} **必填**（再点同一种是「取消」，也是显式传同一个值，
     * 不靠「省略 = 切换」这种隐式语义 —— 隐式语义在前端重试时会翻成反效果）。
     */
    @PostMapping("/like")
    public ResponseEntity<Object> like(
            @RequestParam(value = "Account", required = false) String Account,
            @RequestParam(value = "Id", required = false) Integer Id,
            @RequestParam(value = "Type", required = false) String Type,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        if (Id == null) {
            return ResponseEntity.ok(Result.fail("缺少参数：Id"));
        }
        return ResponseEntity.ok(Result.of(commentService.like(Id.longValue(), Account, Type)));
    }

    /**
     * 图片审核（管理端，方案 §4.3）—— 一条端点、三个动作，靠 {@code Action} 分派。
     *
     * <p>⚠️ <b>本端点是管理端专属，但**刻意不挂 {@code requireAdmin}**</b> —— 本仓对「管理端页面上
     * 的端点」一贯如此（见 {@code admin/**Controller} 的既有做法）：闸开着时 {@code AuthInterceptor}
     * 已经把非管理员的 {@code role=3} cookie 挡在外面，闸关着时（{@code app.auth.enabled=false}）
     * 本就是本地开发/演示场景。此处**不新造**一道与全仓不一致的鉴权。
     *
     * @param Action {@code list}（默认）/ {@code approve} / {@code reject}
     * @param Id     单张图 Id（{@code approve} / {@code reject} 必填）
     * @param Status {@code list} 的筛子：{@code pending}（默认）/ {@code approved} / {@code rejected}
     */
    @PostMapping("/imageReview")
    public ResponseEntity<Object> imageReview(
            @RequestParam(value = "Action", required = false) String Action,
            @RequestParam(value = "Id", required = false) Integer Id,
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "Limit", required = false) Integer Limit,
            @RequestParam(value = "Offset", required = false) Integer Offset,
            HttpServletRequest request
    ) {
        Long id = Id == null ? null : Id.longValue();
        Map<String, Object> result = commentService.imageReview(Action, id, Status, Limit, Offset);
        auditImageReview(result, request);
        return ResponseEntity.ok(Result.of(result));
    }

    /** 可评论的历史商品（发表评论时「选对应商品」的下拉，方案 §4.1）。 */
    @PostMapping("/myOrders")
    public ResponseEntity<Object> myOrders(
            @RequestParam(value = "Account", required = false) String Account,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, Account,
                userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(commentService.myOrders(Account)));
    }

    // ==================== 内部 ====================

    /**
     * 图片审核的审计挂钩点（2026-10-13 整改补笔）—— 只有 {@code approve} / {@code reject}
     * 两条**写**路留痕，{@code list} 不留（读动作不进 {@code admin_audit}，同全仓口径）。
     *
     * <p>判据用回体里的 {@code before}：**只有写路会带它**（`CommentService.imageReview` 在
     * CAS 成功之后 `put("before", image)`），`list` 那条路连这个键都没有
     * ⇒ 它同时就是「这次是不是一次写」的判据，不必在这里把 {@code Action} 再归一化一遍
     * （那会是第二处 `list / approve / reject` 的分支，与 Service 里那份漂开只是时间问题）。
     *
     * <p>⚠️ <b>处理人取 {@link AuthSession#identify}（会话），不加 {@code Account} 入参</b>
     * —— 与 {@code report.handle} 同口径：入参声明身份等于让人随便署别人的名。
     *
     * <p>⚠️ {@code TargetId} 用 `ImageUrl` 的**尾段（落盘名）**而不是图的自增 Id：
     * 落盘名是那张图唯一的稳定标识（URL 前缀随部署变），且驳回会把文件**真删掉**
     * ⇒ 事后要问「删的是哪个文件」，只有这一列答得出。取不到尾段时退回整条 URL。
     */
    private void auditImageReview(Map<String, Object> result, HttpServletRequest request) {
        Object before = result.get("before");
        if (!Boolean.TRUE.equals(result.get("success")) || !(before instanceof Map<?, ?> image)) {
            return;
        }
        // 通过 / 驳回看**回体里的新状态**（`before` 那份恒是 `pending`，判不出结论）。
        boolean rejected = "rejected".equals(safeStr(result.get("Status")));
        Map<String, Object> after = new HashMap<>();
        after.put("Status", result.get("Status"));
        after.put("FileDeleted", result.get("fileDeleted"));
        after.put("CommentId", image.get("CommentId"));
        after.put("ImageUrl", image.get("ImageUrl"));
        // 被评论的对象（商品 / 用户）—— 图本身只挂在评论下，这一层是事后追查的入口。
        after.put("TargetType", image.get("TargetType"));
        after.put("TargetId", image.get("TargetId"));
        after.put("AuthorId", image.get("AuthorId"));
        auditService.record(AuthSession.identify(request),
                rejected ? AuditActions.COMMENT_IMAGE_REJECT : AuditActions.COMMENT_IMAGE_APPROVE,
                "comment_image", imageFileName(safeStr(image.get("ImageUrl"))), image, after);
    }

    /**
     * `ImageUrl` → 落盘名（尾段）。⚠️ 与 `CommentService.deleteImageFile` 剥的是**同一个前缀**，
     * 但**故意不复用**那个私有方法（它是「删文件」链路的一环，共用一个取名字的函数会让
     * 「改了删除策略顺手动到审计的业务键」成为可能）—— 两处都以后缀名取尾段，改前缀会同时红。
     */
    private static String imageFileName(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return "(无 ImageUrl)";
        }
        int slash = imageUrl.lastIndexOf('/');
        String name = slash < 0 ? imageUrl : imageUrl.substring(slash + 1);
        return name.isBlank() ? imageUrl : name;
    }

    /** {@code Object → String}（{@code null} ⇒ {@code null}）—— 避免各处 {@code String.valueOf} 写出 "null" 字面量。 */
    private static String safeStr(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * 取本次请求的图片 part（非 multipart 请求 ⇒ 空表）。见 {@link #add} 的 ⚠️。
     */
    private static List<MultipartFile> filesOf(HttpServletRequest request) {
        if (request instanceof MultipartHttpServletRequest multipart) {
            return multipart.getFiles("Files");
        }
        return Collections.emptyList();
    }

    /**
     * 调用者是不是管理员 —— 决定 {@code /comment/delete} 能不能删别人的。
     *
     * <p>⚠️ 用**识别**口径（{@link AuthSession#identify}）而不是授权口径：与
     * {@link IdentityGuard} 取同一个口径（见那个类的「为什么用识别口径」一节）。
     * 闸开着时业务端点上的 {@code role=3} cookie 本来就被 {@code AuthInterceptor} 挡在外面，
     * 故这一行在闸开着时**只可能**从 token 里读到管理员。
     */
    private static boolean isAdmin(HttpServletRequest request) {
        AuthSession.Identity identity = AuthSession.identify(request);
        return identity != null && identity.role() != null && identity.role() == AuthSession.ROLE_ADMIN;
    }
}
