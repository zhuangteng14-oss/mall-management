package com.minke.mallmanagement.comment.service;

import com.minke.mallmanagement.comment.CommentKinds;
import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.upload.UploadStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内容域的服务层（C3 块 6 · commit 1「评论」）—— <b>业务规则 + 响应组装</b>。
 * 路由与入参绑定在 {@code CommentController}，数据访问全在 {@link CommentDao}；
 * 字面量（{@code TargetType} / {@code Kind} / 状态 / 角色）一律引 {@link CommentKinds}。
 * 契约与规则见 {@code docs/agent/context-packs/块6参考.md}（下称「方案」）。
 *
 * <h2>【本类产出的评论行，读写两侧的三个「账号」口径】</h2>
 * <ul>
 *   <li>{@code comment.AuthorId} / {@code comment_like.UserId}：**登录名**（{@code login.UserName}）。</li>
 *   <li>会话里是**业务号**（{@code user.customerid}），{@code order.CustomerId} 也是业务号
 *       ⇒ 「买过没有」要先 {@code 登录名 → customerid} 翻一次。</li>
 * </ul>
 * 两个方向的换算都在 {@link CommentDao} 里，本类只调用。**别在这里再写一份**。
 *
 * <h2>【{@code Kind} 的写侧收敛 —— 回复的 Kind 由**服务端按父亲推**，不吃入参】</h2>
 * 表里只有三个值（{@code review / question / answer}）。回复（{@code ParentId != NULL}）的
 * {@code Kind} **不由前端给**：回复「评价」得 {@code review}、回复「提问」得 {@code answer}
 * —— 由 {@link CommentDao#selectComment} 读回的父行决定。理由：让前端传就等于把
 * 「这条回复挂在哪类楼层下」这条不变量复制到前端，一旦传错，读侧的
 * {@code Kind='review'} 筛子就会把一条正常回复**静默**漏掉（列表少一行，没人报错）。
 *
 * <h2>【匿名 = {@code Rating} 强制 5，且**列表里不回 {@code AuthorId}**】</h2>
 * 方案 §4.1：「匿名 → {@code Rating} 强制 5 + 显示 {@code 首字*****}」。
 * ⚠️ 但只把**显示名**打码是不够的 —— {@code AuthorId} 若原样回给前端，页面源码里仍能
 * 直接读到是谁（一份「匿名」然而可反查的响应体没有意义）⇒ {@link #decorate} 里把匿名行的
 * {@code AuthorId} 置 {@code null}。{@code Mine} / {@code MyLike} 在那**之前**算好
 * （它们只反映**调用者自己**，不泄露作者）。
 *
 * <h2>【图片：先审后发（方案 §4.3），失败不回滚评论】</h2>
 * 文本与图片是**两件事**：评论文本立即可见，图片落 {@code pending} 等管理端过。
 * 故落盘失败时**不**把整条评论判失败（那会让用户重发一次已经存在的评论）——
 * 如实回一句 {@code msg} 与 {@code imageFailed} 计数，让用户自己看着办。
 * 本仓零业务异常抛出的口径（同 {@code CartService} / {@code CouponService}）在这里同样适用。
 */
@Service
public class CommentService {

    /** 列表默认页大小。 */
    public static final int DEFAULT_LIMIT = 20;

    /** 列表上限 —— 防止一次拉爆整表（同 {@code CouponService.MAX_LIMIT} 的口径）。 */
    public static final int MAX_LIMIT = 100;

    /** {@code Content} 列是 {@code varchar(1000)}（方案 §一.1）—— 超了会在 strict mode 下 ERROR 1406。 */
    public static final int MAX_CONTENT = 1000;

    /**
     * 单条评论的图片数上限。⚠️ **方案没写这个数**，是本地裁定：九宫格是评论区的通行形状，
     * 且 {@code comment_image} 一行一图 ⇒ 上限只是防「一条评论挂几百张图」把库撑爆。
     * 要改就改这一个常量（前端那份也引同一语义的数）。
     */
    public static final int MAX_IMAGES = 9;

    /** 「我的历史订单」默认条数。 */
    public static final int MY_ORDERS_LIMIT = 100;

    /** {@code ProductCode} 列是 {@code varchar(40)}（方案 §一.1）。 */
    private static final int MAX_PRODUCT_CODE = 40;

    /** 上传子目录 —— 方案 §A5「图片存储 {@code static/img/comment/}」。 */
    private static final String IMG_SUBDIR = "comment";

    /**
     * 发通知时写在 {@code notification.TargetType} 里的值（建表注释的取值域
     * {@code goods / user / comment…} 里的 {@code comment}）。
     *
     * <p>⚠️ <b>别和 {@code comment.TargetType} 混</b> —— 那是**另一个表的另一列**，
     * 本域那一列的值只有 {@code goods} / {@code user} 两种（见 {@link CommentKinds} 类注释
     * 那条「别把 {@code TargetType='comment'} 写进来」）。这里写 {@code 'comment'} 是给
     * **通知**用的：「这条通知说的是某条评论」—— 与「某条评论挂在什么对象上」是两回事。
     * 正因为它俩同名不同义，才不在 {@code CommentKinds} 里给它一个常量（放进去必然被误引）。
     */
    private static final String NOTIFY_TARGET_COMMENT = "comment";

    /**
     * 图片 URL 前缀。⚠️ 照既有三处上传点（{@code GoodsService} / {@code UserService}）的
     * **硬编码 host** 口径 —— 前端 {@code <img :src="item.goodImg">} 用的是绝对 URL，
     * 改这里要连带改前端（或一次把四处都改成同源相对路径，那是另一笔）。
     */
    private static final String IMG_URL_PREFIX = "http://localhost:8088/img/" + IMG_SUBDIR + "/";

    private final CommentDao commentDao;
    private final NotificationService notificationService;
    private final String uploadDir;

    public CommentService(CommentDao commentDao,
                          NotificationService notificationService,
                          @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}") String uploadDir) {
        this.commentDao = commentDao;
        this.notificationService = notificationService;
        this.uploadDir = uploadDir;
    }

    // ==================== 读：列表 ====================

    /**
     * 拉一层评论（顶层）+ 各自的回复，附统计卡、图片、我对每条的态度、以及「这条是不是我发的」。
     *
     * <p>响应形状（全仓列表端点惯例 + 本域自己的三块）：
     * <pre>{@code
     * {"success":true, "list":[…], "total":N, "limit":K, "offset":O,
     *  "stats":{…}}   // kind=question ⇒ {Total,Solved,Unsolved}；其余 ⇒ {Total,AvgRating,S5…S1}
     * }</pre>
     * 每行额外带 {@code Author}（显示名，匿名已打码）、{@code Images}、{@code MyLike}、
     * {@code Mine}、{@code Replies}（回复同形，但**不再嵌一层** —— 方案 §4.1 是**两层**扁平）。
     *
     * @param viewerUserId 调用者的**业务号**（会话里那个；{@code null} = 未登录）
     *                     —— ⚠️ 只在**校验通过之后**才拿去查登录名：本方法前半段是纯参数校验，
     *                     smoke 切片（{@code JdbcTemplate} 是替身）只走得到那里
     */
    public Map<String, Object> list(String targetType, String targetId, String kind, String sort,
                                    String solved, Integer limit, Integer offset, String viewerUserId) {
        if (isBlank(targetId)) {
            return fail("缺少参数：TargetId");
        }
        String tt = isBlank(targetType) ? CommentKinds.TARGET_GOODS : targetType.trim();
        if (!CommentKinds.knownTargetType(tt)) {
            return fail("未知的评论对象类型：" + targetType + "（只能是 goods / user）");
        }
        String k = isBlank(kind) ? null : kind.trim();
        if (k != null && !CommentKinds.knownKind(k)) {
            return fail("未知的评论类型：" + kind + "（只能是 review / question / answer）");
        }
        String sortKey = isBlank(sort) ? null : sort.trim();
        if (sortKey != null && !"hot".equals(sortKey) && !"new".equals(sortKey)) {
            return fail("未知的排序方式：" + sort + "（只能是 hot / new）");
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = (offset == null || offset < 0) ? 0 : offset;
        try {
            String viewer = (viewerUserId == null) ? null : commentDao.selectUsernameByUserId(viewerUserId.trim());
            List<Map<String, Object>> top = commentDao.selectTopLevel(tt, targetId.trim(), k,
                    trimToNull(solved), sortKey, effectiveLimit, effectiveOffset);
            long total = commentDao.countTopLevel(tt, targetId.trim(), k, trimToNull(solved));

            List<Long> topIds = ids(top);
            List<Map<String, Object>> replies = commentDao.selectReplies(topIds);
            Map<Long, List<Map<String, Object>>> repliesByParent = groupByParent(replies);

            // 图片与「我的态度」一次查齐（顶层 + 回复**一起**）—— 逐行查会变成 N+1 次往返。
            List<Long> allIds = new ArrayList<>(topIds);
            allIds.addAll(ids(replies));
            Map<Long, List<Map<String, Object>>> imagesByComment = groupBy(allIds, "CommentId",
                    commentDao.selectImages(allIds));
            Map<Long, String> myLikeByComment = myLikes(allIds, commentDao.selectMyLikes(allIds, viewer));

            List<Map<String, Object>> list = new ArrayList<>(top.size());
            for (Map<String, Object> row : top) {
                Map<String, Object> item = decorate(row, viewer, imagesByComment, myLikeByComment);
                Long acceptedId = longOrNull(row.get("AcceptedReplyId"));
                List<Map<String, Object>> children = new ArrayList<>();
                for (Map<String, Object> reply : repliesByParent.getOrDefault(id(row), List.of())) {
                    Map<String, Object> child = decorate(reply, viewer, imagesByComment, myLikeByComment);
                    child.put("Replies", List.of());   // 两层扁平：回复下不再有第三层
                    child.put("Accepted", acceptedId != null && acceptedId.equals(id(reply)));
                    children.add(child);
                }
                // 方案 §4.2「采纳 → 置顶」：把它排在回复区最前。⚠️ 用**稳定排序**（同键保序），
                // 故未被采纳的那些仍守着 selectReplies 的「商家首复置顶 + 时间正序」。
                // 放在这里而不是 SQL 的 ORDER BY：置顶是**呈现**次序，SQL 那一层已经按楼内自然序出好了
                // （且那条排序键还带着 ParentId，插一个相关子查询进去会更难读）。
                children.sort((a, b) -> Boolean.compare(!flag(a.get("Accepted")), !flag(b.get("Accepted"))));
                item.put("Replies", children);
                list.add(item);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", list);
            result.put("total", total);
            result.put("limit", effectiveLimit);
            result.put("offset", effectiveOffset);
            // 统计随类型走：问答区要「已解决/未解决」，评价区要星分布。⚠️ 两份统计**不混在**同一个响应里
            // —— 前端拿 stats 时已经知道自己在哪个 tab，多回一份只会让人猜哪份算数。
            result.put("stats", (CommentKinds.QUESTION.equals(k))
                    ? commentDao.selectQuestionStats(tt, targetId.trim())
                    : commentDao.selectReviewStats(tt, targetId.trim()));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("查询失败：" + e.getMessage());
        }
    }

    // ==================== 写：发表 / 回复 / 提问 / 回答 ====================

    /**
     * 落一条评论 / 回复 / 提问 / 回答（四种形态共用一个端点，见方案 §三）。
     *
     * <p>校验顺序 = 「先拒掉不用查库的，再拒掉要查库的，最后才写」：任何一条不过都**没写库**，
     * 故不存在「半条评论」。
     *
     * @param account    调用者**登录名**（与 {@code comment.AuthorId} 同一个键）
     * @param parentIdRaw 非空 ⇒ 这是一条**回复**（{@code Kind} / {@code TargetType} / {@code TargetId}
     *                    一律以父行为准，入参里那两个被忽略）
     * @param replyToIdRaw 非空 ⇒ 回的是**回复**（楼中楼），必须与 {@code ParentId} 同楼
     * @param files      评论图片（可为空 / {@code null}）；落盘后一律 {@code pending}
     */
    @Transactional
    public Map<String, Object> add(String account, String targetType, String targetId, String parentIdRaw,
                                   String replyToIdRaw, String kindRaw, String content, String ratingRaw,
                                   String anonymousRaw, String productCode, List<MultipartFile> files) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        String author = account.trim();
        if (isBlank(content)) {
            return fail("评论内容不能为空");
        }
        String text = content.trim();
        if (text.length() > MAX_CONTENT) {
            return fail("评论内容最多 " + MAX_CONTENT + " 字（当前 " + text.length() + " 字）");
        }
        if (!isBlank(productCode) && productCode.trim().length() > MAX_PRODUCT_CODE) {
            return fail("ProductCode 最多 " + MAX_PRODUCT_CODE + " 字符");
        }
        List<MultipartFile> images = nonEmptyFiles(files);
        if (images.size() > MAX_IMAGES) {
            return fail("最多上传 " + MAX_IMAGES + " 张图片（当前 " + images.size() + " 张）");
        }
        Long parentId = parseLong(parentIdRaw);
        if (!isBlank(parentIdRaw) && parentId == null) {
            return fail("ParentId 必须是数字：" + parentIdRaw);
        }
        Long replyToId = parseLong(replyToIdRaw);
        if (!isBlank(replyToIdRaw) && replyToId == null) {
            return fail("ReplyToId 必须是数字：" + replyToIdRaw);
        }

        try {
            Integer role = commentDao.selectRoleByUsername(author);
            if (role == null) {
                return fail("账号不存在：" + author);
            }

            String tt;
            String tid;
            String kind;
            boolean merchantFirst = false;
            if (parentId == null) {
                tt = isBlank(targetType) ? CommentKinds.TARGET_GOODS : targetType.trim();
                if (!CommentKinds.knownTargetType(tt)) {
                    return fail("未知的评论对象类型：" + targetType + "（只能是 goods / user）");
                }
                if (isBlank(targetId)) {
                    return fail("缺少参数：TargetId");
                }
                tid = targetId.trim();
                kind = CommentKinds.normalizeKind(kindRaw);
                if (!CommentKinds.knownKind(kind)) {
                    return fail("未知的评论类型：" + kindRaw + "（只能是 review / question / answer）");
                }
                if (CommentKinds.ANSWER.equals(kind)) {
                    // 回答只能挂在提问下（ParentId 非空那条路）—— 顶层「回答」是一个没有问题的答案。
                    return fail("顶层评论的类型只能是 review（评价）或 question（提问）");
                }
                if (CommentKinds.REVIEW.equals(kind)) {
                    Map<String, Object> bad = checkReviewEligible(tt, tid, author);
                    if (bad != null) {
                        return bad;
                    }
                }
            } else {
                Map<String, Object> parent = commentDao.selectComment(parentId);
                if (parent == null) {
                    return fail("被回复的评论不存在");
                }
                if (CommentKinds.STATUS_DELETED.equals(str(parent.get("Status")))) {
                    return fail("被回复的评论已删除");
                }
                if (parent.get("ParentId") != null) {
                    // 两层扁平（方案 §4.1 的回复区只有一层缩进）⇒ 不能回复一条回复；
                    // 「回复回复」表达成**同层的另一条回复** + ReplyToId 指向它（下面那段）。
                    return fail("只能回复顶层评论（回复对回复请用 ReplyToId 指向它）");
                }
                tt = str(parent.get("TargetType"));
                tid = str(parent.get("TargetId"));
                kind = CommentKinds.QUESTION.equals(str(parent.get("Kind")))
                        ? CommentKinds.ANSWER : CommentKinds.REVIEW;
                if (replyToId != null) {
                    Map<String, Object> target = commentDao.selectComment(replyToId);
                    if (target == null) {
                        return fail("被回复的回复不存在");
                    }
                    if (!String.valueOf(parentId).equals(str(target.get("ParentId")))) {
                        return fail("ReplyToId 与被回复的楼层不匹配");
                    }
                }
                // 方案 §4.1「商家首次回复 → 置顶；商家后续回复 → 混排」—— 进楼层前先数一次。
                merchantFirst = role == CommentKinds.ROLE_MERCHANT
                        && commentDao.countMerchantFirstUnder(parentId) == 0;
            }

            boolean anonymous = resolveAnonymous(kind, role, anonymousRaw);
            int rating = resolveRating(kind, anonymous, ratingRaw);

            if (commentDao.insertComment(tt, tid, parentId, replyToId, kind, author, role,
                    anonymous, rating, text, isBlank(productCode) ? null : productCode.trim(),
                    merchantFirst) == 0) {
                return fail("评论发表失败，请重试");
            }
            // ⚠️ 与上面那条 INSERT 同事务（类上 @Transactional）才认得住：无事务时
            //    LAST_INSERT_ID() 可能落在**另一条连接**上，拿到的是别人的行号。
            long id = commentDao.lastInsertId();

            int stored = 0;
            int failed = 0;
            if (!images.isEmpty()) {
                Path dir;
                try {
                    dir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(IMG_SUBDIR);
                    Files.createDirectories(dir);
                } catch (Exception e) {
                    e.printStackTrace();
                    // 目录不可用 ⇒ 一张都存不了，但**评论已经落库**（文本是主体）。如实回报。
                    return okAdded(id, kind, 0, images.size());
                }
                for (MultipartFile file : images) {
                    try {
                        String name = UploadStorage.store(dir, file);
                        // ⚠️ 必须用 store 的**返回值**拼 URL（改名时入参名与落盘名不一致，
                        //    用入参名拼出来的 URL 会指向别人的图 —— 那是 UploadStorage 类注释里
                        //    「缺陷病 B」本身）。
                        commentDao.insertImage(id, IMG_URL_PREFIX + name, stored);
                        stored++;
                    } catch (Exception e) {
                        e.printStackTrace();
                        failed++;
                    }
                }
            }
            return okAdded(id, kind, stored, failed);
        } catch (Exception e) {
            e.printStackTrace();
            return fail("评论发表失败：" + e.getMessage());
        }
    }

    /**
     * 评价资格两道闸（方案 §4.1）：**买过** + **一商品一用户只能评一条**。
     * 可评 ⇒ {@code null}；否则回一个 {@code success:false}。
     *
     * <p>⚠️ 两道都只对**顶层评价**（{@code ParentId IS NULL} 且 {@code kind='review'}）生效
     * —— 回复不限条数、也不要求买过（方案 §4.1「回复权限：任何登录用户」）。
     */
    private Map<String, Object> checkReviewEligible(String targetType, String targetId, String author) {
        if (!CommentKinds.TARGET_GOODS.equals(targetType)) {
            // 用户留言板（TargetType='user'）没有「买过」可言 —— 方案 §4.1 的资格规则挂在商品上。
            return null;
        }
        long goodsId;
        try {
            goodsId = Long.parseLong(targetId);
        } catch (NumberFormatException e) {
            return fail("商品评论的 TargetId 必须是商品 Id（数字），收到：" + targetId);
        }
        String customerId = commentDao.selectCustomerIdByUsername(author);
        if (customerId == null) {
            return fail("无法识别客户身份，暂时不能评价（商家账号没有购买记录）");
        }
        if (commentDao.countPurchasedGoods(customerId, goodsId) == 0) {
            return fail("购买并确认收货后才能评价");
        }
        if (commentDao.countTopLevelByAuthor(targetType, targetId, CommentKinds.REVIEW, author) > 0) {
            return fail("您已评价过该商品，不能重复评价");
        }
        return null;
    }

    // ==================== 写：删除 ====================

    /**
     * 删一条评论（逻辑删）。作者删顶层 ⇒ **连带**该楼全部回复；作者删回复 ⇒ **只删那条**
     * （方案 §4.1 那两句话是一对，见 {@code CommentDao.softDeleteRepliesUnder} 的 ⚠️）。
     *
     * <p>⚠️ 管理员能删任何人的（第 2 档），**但普通用户删别人的**在这里被判失败而不是 403：
     * 调用者已经**有**身份、{@code Account} 也已验明是本人（{@link com.minke.mallmanagement.shared.web.IdentityGuard}），
     * 剩下的纯粹是「你不是这条评论的作者」这条业务判断 ⇒ {@code success:false} 比 403 更贴
     * 前端的话术出口（403 会被 {@code api/http.js} 当登录态问题处理）。
     */
    public Map<String, Object> delete(long id, String account, boolean admin) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        try {
            Map<String, Object> row = commentDao.selectComment(id);
            if (row == null) {
                return fail("评论不存在");
            }
            if (CommentKinds.STATUS_DELETED.equals(str(row.get("Status")))) {
                return fail("该评论已删除");
            }
            if (!admin && !account.trim().equals(str(row.get("AuthorId")))) {
                return fail("只能删除自己发表的评论");
            }
            boolean topLevel = row.get("ParentId") == null;
            int cascaded = topLevel ? commentDao.softDeleteRepliesUnder(id) : 0;
            commentDao.softDelete(id);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("msg", topLevel && cascaded > 0
                    ? "已删除（连同 " + cascaded + " 条回复）" : "已删除");
            result.put("replies", cascaded);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("删除失败：" + e.getMessage());
        }
    }

    // ==================== 写：采纳答案（问答，块 6 commit 2） ====================

    /**
     * 采纳一条答案（方案 §4.2）。提问者（或管理员）在**顶层提问**下选一条回复 ⇒ 该回复置顶、
     * 提问标记已解决、**回答者收到一条通知**。
     *
     * <p>五道闸，顺序 = 先不用查库的、后要查库的（同 {@link #add}）：
     * <ol>
     *   <li>提问存在且未删；</li>
     *   <li>它是**顶层提问**（{@code Kind='question'} 且 {@code ParentId IS NULL}）——
     *       「评价」没有可采纳的答案，回复更不行；</li>
     *   <li>调用者是提问者本人（管理员除外，与 {@link #delete} 同一档）；</li>
     *   <li>回答存在、未删、且**确实挂在这个提问下**；</li>
     *   <li>该提问**还没被采纳过** —— 方案 §4.2「采纳后不可改」。</li>
     * </ol>
     *
     * <p>⚠️ 第 5 条**说了两遍**（这里读一次 + {@link CommentDao#acceptAnswer} 的 {@code WHERE} 里再闸一次），
     * 这不是重复：这里的读是为了给出**准确的话术**，那里的 {@code WHERE} 才是**并发下的真闸**
     * （见那个方法的 ⚠️）。少了 SQL 那一道，两条同刻的采纳请求会双双通过。
     *
     * <p>⚠️ 通知是 **fail-soft**（{@code NotificationService.send} 自己不抛）—— 采纳已经写进库了，
     * 一条通知发不出去不该把它翻成失败。口径同 {@code CouponExpiryJob} 等所有调用点。
     */
    public Map<String, Object> accept(long questionId, long replyId, String account, boolean admin) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        String operator = account.trim();
        try {
            Map<String, Object> question = commentDao.selectComment(questionId);
            if (question == null) {
                return fail("提问不存在");
            }
            if (CommentKinds.STATUS_DELETED.equals(str(question.get("Status")))) {
                return fail("该提问已删除");
            }
            if (question.get("ParentId") != null
                    || !CommentKinds.QUESTION.equals(str(question.get("Kind")))) {
                return fail("只能对顶层提问采纳答案");
            }
            if (!admin && !operator.equals(str(question.get("AuthorId")))) {
                // ⚠️ 与 delete 同款：调用者身份已验明（IdentityGuard），剩下的纯是业务判断 ⇒
                //    success:false 比 403 更贴前端话术出口（403 会被 http.js 当登录态问题处理）。
                return fail("只有提问者本人可以采纳答案");
            }
            if (flag(question.get("IsSolved")) || question.get("AcceptedReplyId") != null) {
                return fail("该提问已采纳过答案，不能更改");
            }

            Map<String, Object> reply = commentDao.selectComment(replyId);
            if (reply == null || CommentKinds.STATUS_DELETED.equals(str(reply.get("Status")))) {
                return fail("该回答不存在");
            }
            Object parentOfReply = reply.get("ParentId");
            if (!(parentOfReply instanceof Number n) || n.longValue() != questionId) {
                return fail("这条回答不属于该提问");
            }

            if (commentDao.acceptAnswer(questionId, replyId) == 0) {
                // 走到这里 = 上面那次读与这次写之间被人抢了先（并发）⇒ 与第 5 道闸同一句话术。
                return fail("该提问已采纳过答案，不能更改");
            }
            notifyAccepted(question, reply);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("Id", questionId);
            result.put("AcceptedReplyId", replyId);
            result.put("msg", "已采纳该答案");
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("采纳失败：" + e.getMessage());
        }
    }

    /**
     * 「你的回答被采纳了」—— 收件人是**回答者**（不是提问者：这个动作是他自己做的，
     * 通知他自己没有意义）。
     *
     * <p>{@code ToType} 随回答者的 {@code AuthorRole} 走（{@code customer} / {@code merchant}）：
     * 收件箱是按 {@code (ToId, ToType)} 两个键查的，跟角色不同的那一边发，商家在自己的消息中心
     * **永远看不到**这条（通知照样落库，只是落在了一个没人读的抽屉里）。
     */
    private void notifyAccepted(Map<String, Object> question, Map<String, Object> reply) {
        String toId = str(reply.get("AuthorId"));
        if (isBlank(toId)) {
            return;
        }
        int role = parseIntOr(str(reply.get("AuthorRole")), CommentKinds.ROLE_CUSTOMER);
        String toType = role == CommentKinds.ROLE_MERCHANT
                ? NotificationService.TO_TYPE_MERCHANT : NotificationService.TO_TYPE_CUSTOMER;
        notificationService.send(UUID.randomUUID().toString(), toId, toType,
                NotificationEvents.COMMENT_ACCEPTED,
                "你的回答被采纳",
                "你在提问「" + brief(question.get("Content")) + "」下的回答已被采纳。",
                NOTIFY_TARGET_COMMENT, String.valueOf(question.get("Id")));
    }

    // ==================== 写：赞 / 踩 ====================

    /**
     * 给一条评论（顶层或回复，方案 §4.1「顶层评论和回复都适用」）点赞 / 踩。
     *
     * <p>语义 = **一人一条态度**（{@code uk_like_comment_user}）：
     * <ul>
     *   <li>第一次点 ⇒ 落一条；</li>
     *   <li>点**另一种** ⇒ 覆盖（旧的那一列 −1、新的一列 +1）；</li>
     *   <li>再点**同一种** ⇒ 取消（删行、那一列 −1）。<b>这条不是额外功能，是「一人一条」的必然推论</b>
     *       —— 没有它就变成「点过的按钮关不掉」。</li>
     * </ul>
     *
     * <p>⚠️ 冗余计数（{@code LikeCount} / {@code DislikeCount}）按**旧值**算增减量，
     * 且两个列分开原子累加（见 {@code CommentDao.upsertLike} 的 ⚠️）。真值始终是
     * {@code comment_like} 的行数，计数随时可重建。
     */
    public Map<String, Object> like(long id, String account, String type) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        String user = account.trim();
        String t = isBlank(type) ? null : type.trim();
        if (!CommentKinds.LIKE.equals(t) && !CommentKinds.DISLIKE.equals(t)) {
            return fail("缺少参数：Type（只能是 like / dislike）");
        }
        try {
            Map<String, Object> row = commentDao.selectComment(id);
            if (row == null || CommentKinds.STATUS_DELETED.equals(str(row.get("Status")))) {
                return fail("评论不存在");
            }
            String old = commentDao.selectLikeType(id, user);
            String now;
            if (t.equals(old)) {
                commentDao.deleteLike(id, user);
                bump(id, t, -1);
                now = null;
            } else {
                commentDao.upsertLike(id, user, t);
                if (old != null) {
                    bump(id, old, -1);
                }
                bump(id, t, 1);
                now = t;
            }
            // 计数回读：不在这里自己加减（那会和库里的真值漂开，尤其是并发下）。
            Map<String, Object> fresh = commentDao.selectComment(id);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("MyLike", now);
            result.put("Id", id);
            result.put("LikeCount", fresh == null ? null : fresh.get("LikeCount"));
            result.put("DislikeCount", fresh == null ? null : fresh.get("DislikeCount"));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("操作失败：" + e.getMessage());
        }
    }

    // ==================== 读：我的历史订单 ====================

    /**
     * 「评论时选对应商品」那个下拉的数据源（方案 §4.1）—— 该客户**已结单**订单里的商品。
     *
     * <p>⚠️ 只对**客户**有意义：{@code user.customerid} 为空的账号（商家 / 管理员）翻不出
     * 客户号 ⇒ 如实回一句失败，而不是回一个空列表让前端以为「买过但列表空了」。
     */
    public Map<String, Object> myOrders(String account) {
        if (isBlank(account)) {
            return fail("缺少参数：Account");
        }
        try {
            String customerId = commentDao.selectCustomerIdByUsername(account.trim());
            if (customerId == null) {
                return fail("该账号没有历史订单（只有客户有购买记录）");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", commentDao.selectCommentableGoods(customerId, MY_ORDERS_LIMIT));
            result.put("limit", MY_ORDERS_LIMIT);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("查询失败：" + e.getMessage());
        }
    }

    // ==================== 内部：规则 ====================

    /**
     * 匿名与否 —— 三种形态各有各的规矩（方案 §4.1 / §4.2）：
     * <ul>
     *   <li><b>提问</b>（{@code question}）：「发问人不允许匿名（实名）」⇒ 恒 {@code false}，
     *       入参说什么都不算；</li>
     *   <li><b>回答</b>（{@code answer}）：「回复人只能匿名（商家除外 —— 商家实名）」
     *       ⇒ 商家 {@code false}、其余恒 {@code true}；</li>
     *   <li><b>评价 / 评价的回复</b>：「匿名：可选」⇒ 吃入参。</li>
     * </ul>
     */
    private static boolean resolveAnonymous(String kind, int role, String anonymousRaw) {
        if (CommentKinds.QUESTION.equals(kind)) {
            return false;
        }
        if (CommentKinds.ANSWER.equals(kind)) {
            return role != CommentKinds.ROLE_MERCHANT;
        }
        return flag(anonymousRaw);
    }

    /**
     * 星级 —— 两条覆盖规则（方案 §4.1「匿名 → {@code Rating} 强制 5」+ §一.1 列注释「问答=0」）：
     * 问答两态一律 0（它们没有星级），匿名一律 5，其余吃入参（缺省 5）、越界收敛到 1..5。
     */
    private static int resolveRating(String kind, boolean anonymous, String ratingRaw) {
        if (CommentKinds.QUESTION.equals(kind) || CommentKinds.ANSWER.equals(kind)) {
            return 0;
        }
        if (anonymous) {
            return CommentKinds.ANONYMOUS_RATING;
        }
        Integer v = parseInt(ratingRaw);
        if (v == null) {
            return CommentKinds.ANONYMOUS_RATING;   // 默认 5（列默认值同款）
        }
        return Math.max(1, Math.min(5, v));
    }

    // ==================== 内部：响应组装 ====================

    /**
     * 给一行原始评论补上四样**展示态**字段：{@code Author}（显示名）、{@code Images}、
     * {@code MyLike}、{@code Mine}，并把匿名行的 {@code AuthorId} 抹掉（见类注释）。
     */
    private static Map<String, Object> decorate(Map<String, Object> row, String viewer,
                                                Map<Long, List<Map<String, Object>>> imagesByComment,
                                                Map<Long, String> myLikeByComment) {
        Map<String, Object> item = new HashMap<>(row);
        long cid = id(row);
        boolean anonymous = flag(row.get("IsAnonymous"));
        String author = str(row.get("AuthorId"));
        item.put("Author", anonymous ? maskAnonymous(author) : author);
        item.put("Images", imagesFor(imagesByComment.get(cid)));
        item.put("MyLike", myLikeByComment.get(cid));
        item.put("Mine", viewer != null && viewer.equals(author));
        if (anonymous) {
            item.put("AuthorId", null);   // 防反查：显示名打码而 Id 原样回等于没打（见类注释）
        }
        return item;
    }

    /** 匿名显示名 = 首字 + {@code *****}（方案 §4.1 逐字）。空 / null ⇒ 兜一个不泄露信息的占位。 */
    private static String maskAnonymous(String author) {
        if (author == null || author.isEmpty()) {
            return "匿名用户";
        }
        return author.substring(0, 1) + "*****";
    }

    /**
     * 图片的对外形状：{@code {Id, Url, Status}}。
     *
     * <p>⚠️ <b>{@code Url} 只在 {@code approved} 时给</b>（{@code pending} / {@code rejected} 一律
     * {@code null}）—— 两条理由，**口径是同一条「先审后显」**：
     * <ol>
     *   <li>{@code rejected}：方案 §4.3 第 5 步「不合规 → {@code rejected} + **删仓库文件**」
     *       ⇒ 那个 URL 此刻**已经 404**。回它，页面显示的是一个破图框（{@code onerror} 没接住的那种），
     *       而方案要的是「图片违规已删除」这句话；</li>
     *   <li>{@code pending}：**未过审的图不该有任何客户端看得到**（方案 §4.3 第 2 步「图显『审核中』」——
     *       要显示的是那四个字，**不是**那张图）。回 URL 就把「先审后显」退化成「每个消费方都记得
     *       再看一眼 {@code Status}」—— 一个判据散在 N 处，漏一处就是一次事故。
     *       ⇒ 判据留在**出口**这一处：没给 URL，就没有「忘了看 Status」这回事。</li>
     * </ol>
     * 故本方法只回 {@code Status} + （过审时）{@code Url}，让前端按状态渲染。
     */
    private static List<Map<String, Object>> imagesFor(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            String status = str(row.get("Status"));
            Map<String, Object> img = new LinkedHashMap<>();
            img.put("Id", row.get("Id"));
            img.put("Status", status);
            img.put("Url", CommentKinds.IMAGE_APPROVED.equals(status) ? str(row.get("ImageUrl")) : null);
            out.add(img);
        }
        return out;
    }

    /** 成功落库后的统一回体 —— 图片那两笔如实计入（见类注释「失败不回滚评论」）。 */
    private static Map<String, Object> okAdded(long id, String kind, int stored, int failed) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("Id", id);
        result.put("Kind", kind);
        result.put("images", stored);
        result.put("imageFailed", failed);
        String msg = CommentKinds.QUESTION.equals(kind) ? "提问已发布"
                : CommentKinds.ANSWER.equals(kind) ? "回答已发布" : "评论已发表";
        if (failed > 0) {
            msg += "（" + failed + " 张图片保存失败）";
        } else if (stored > 0) {
            msg += "，图片待审核后显示";
        }
        result.put("msg", msg);
        return result;
    }

    /** 计数字段的增减 —— 两个列分开（{@code like} / {@code dislike} 各有各的列）。 */
    private void bump(long commentId, String type, int delta) {
        if (CommentKinds.LIKE.equals(type)) {
            commentDao.incrLike(commentId, delta);
        } else if (CommentKinds.DISLIKE.equals(type)) {
            commentDao.incrDislike(commentId, delta);
        }
    }

    // ==================== 内部：小工具 ====================

    private static List<Long> ids(List<Map<String, Object>> rows) {
        List<Long> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long v = id(row);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static Long id(Map<String, Object> row) {
        Object v = row.get("Id");
        return (v instanceof Number n) ? n.longValue() : null;
    }

    /** 某列当 {@code Long} 读（{@code AcceptedReplyId} 这类**可空**的外键列）—— 非数字 ⇒ {@code null}。 */
    private static Long longOrNull(Object v) {
        return (v instanceof Number n) ? n.longValue() : null;
    }

    /** 数字串转 {@code int}，转不动 / 空 ⇒ 用兜底值（回角色号那一处：读不到就按客户走）。 */
    private static int parseIntOr(String s, int fallback) {
        Integer v = parseInt(s);
        return v == null ? fallback : v;
    }

    /**
     * 通知正文里引一句用户写的东西 —— 截到 30 字。
     *
     * <p>⚠️ 通知的标题/正文是**给别人看的摘要**，不是这条评论的第二份存储：不截的话一条 1000 字的
     * 提问会把整句「你在提问「…」下的回答已被采纳」淹掉。
     * <p>⚠️ 而且它会**真的写不进去**：{@code notification.Content} 是 {@code varchar(1024)}、
     * {@code Title} 是 {@code varchar(128)}（{@code mall_management_sql.sql} 的建表注释逐字），
     * 而 {@code comment.Content} 是 {@code varchar(1000)} —— 正文 1000 字再加两侧引号与那句话，
     * 正好顶穿 1024，strict mode 下 MySQL 直接拒插 ⇒ 回答者**收不到这条通知**（而且因为
     * {@code NotificationService.send} 是 fail-soft，外面连异常都看不见）。截到 30 字把这个缺口堵死。
     */
    private static String brief(Object content) {
        String s = str(content);
        if (s == null) {
            return "";
        }
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }

    /** 按某一列分组（{@code CommentId} / {@code ParentId}），值收成 list。 */
    private static Map<Long, List<Map<String, Object>>> groupBy(List<Long> ignored, String column,
                                                                List<Map<String, Object>> rows) {
        Map<Long, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(column);
            if (key instanceof Number n) {
                out.computeIfAbsent(n.longValue(), k -> new ArrayList<>()).add(row);
            }
        }
        return out;
    }

    private static Map<Long, List<Map<String, Object>>> groupByParent(List<Map<String, Object>> rows) {
        return groupBy(null, "ParentId", rows);
    }

    /** {@code comment_like} 读回的 {@code (CommentId, Type)} 对 → {@code CommentId → Type}。 */
    private static Map<Long, String> myLikes(List<Long> ignored, List<Map<String, Object>> rows) {
        Map<Long, String> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("CommentId");
            if (key instanceof Number n) {
                out.put(n.longValue(), str(row.get("Type")));
            }
        }
        return out;
    }

    /** 去掉空文件（表单里空的 {@code <input type=file>} 会以 0 字节的 part 出现，别当一张图）。 */
    private static List<MultipartFile> nonEmptyFiles(List<MultipartFile> files) {
        List<MultipartFile> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                out.add(file);
            }
        }
        return out;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Long parseLong(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 宽容判据 —— 同一个字段可能是 {@code Boolean} / {@code 1} / {@code "1"}（同 {@code CouponService.flag}）。 */
    private static boolean flag(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(o).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }
}
