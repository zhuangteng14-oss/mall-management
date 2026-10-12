package com.minke.mallmanagement.comment.controller;

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
import java.util.List;

/**
 * 内容域「评论 / 问答」Web 层（C3 块 6 · commit 1 评论 + commit 2 问答）—— 6 个端点：
 * 列表（公开）/ 发表（评论·回复·提问·回答共用）/ 删除 / **采纳答案** / 赞踩 / 我的历史订单。
 *
 * <h2>【挂 {@code /comment}，与方案 §三 的端点清单逐字一致】</h2>
 * 方案 A4 与 {@code 块6参考.md §三} 定死了 13 个端点与其路径，本类占前 6 个
 * （{@code /comment/imageReview} 归 commit 4，其余 6 个归 {@code /report/**} 与
 * {@code /sensitive/**} 两个新 Controller）。
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
 */
@RestController
@RequestMapping("/comment")
public class CommentController {

    private final CommentService commentService;

    /** ⚠️ 刻意是 {@link ObjectProvider}，不是裸的 {@code UserStateLookup} —— 见类注释。 */
    private final ObjectProvider<UserStateLookup> userStateLookupProvider;

    public CommentController(CommentService commentService,
                             ObjectProvider<UserStateLookup> userStateLookupProvider) {
        this.commentService = commentService;
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
     * 发表 —— 评论 / 回复 / 提问 / 回答**共用一个端点**（方案 §三的端点表逐字）。
     *
     * <p>四种形态靠 {@code ParentId} 与 {@code Kind} 区分，规则在
     * {@code CommentService.add}（顶层 {@code Kind} 吃入参、回复的 {@code Kind} 由父行推）。
     *
     * <p>⚠️ <b>图片走同一个请求的多 part，不另开端点</b>：方案 A4 是「13 个端点」，
     * 加第 14 个是要批的契约改动；且评论文本与它的图本就是一次提交。
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
