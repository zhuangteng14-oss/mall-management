package com.minke.mallmanagement.comment.service;

import com.minke.mallmanagement.comment.CommentKinds;
import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.comment.job.QuestionTimeoutJob;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.upload.UploadStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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
 * 内容域的服务层（C3 块 6 · commit 1「评论」/ commit 2「问答」/ commit 3「@ 自动生成」）
 * —— <b>业务规则 + 响应组装</b>。
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
 *
 * <h2>【敏感词：发表前过一道闸（方案 §4.5，整改 commit 5）】</h2>
 * {@link #add} 在 {@code INSERT} 之前调 {@link SensitiveWordService#filter}：
 * 命中占比 {@code < 40%} ⇒ 正文**就地打码**（{@code ***}）后照常入库；
 * {@code >= 40%} ⇒ **拒收**（只回一句不点名词的话 —— 方案 §4.5「不提示用户」）。
 * ⚠️ 判定与占比口径**只在 {@code SensitiveWordService} 一处**，本类只做「拒 or 换」，
 * 别在这里再算一遍（两处算会出现「打码了但没拒 / 拒了但没打码」的分叉）。
 * ⚠️ 打码发生在**通知与正文落库之前**：{@code recordMention} / {@code notifyOnPost} /
 * {@code notifyReply} 拿到的 {@code text} 也是打码后的 —— 通知正文里不该出现原文。
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

    /**
     * {@code comment.Source} 列是 {@code varchar(16)}（Migration ⑰-4 加的；块 5 给 11 张表
     * 加的那一列同宽）。C3 块 7 · commit B 起由 {@code /comment/add} 的**可选**入参写入。
     */
    private static final int MAX_SOURCE = 16;

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

    /**
     * 「一商品一用户只能评一条」的**用户可见话术** —— 两条闸共用这一份。
     *
     * <p>两条闸 = ① {@code checkReviewEligible} 的预检（先查后插，能给得出话术）；
     * ② Migration ⑯ 的 {@code uk_comment_review_once}（DB 层不变量，**只在并发**下才会先于 ① 拦到）。
     * ⚠️ 两处各写一遍字面量必然漂开（改一处漏一处，且两边都不报错）⇒ 收成常量。
     */
    private static final String MSG_DUPLICATE_REVIEW = "您已评价过该商品，不能重复评价";

    private final CommentDao commentDao;
    private final NotificationService notificationService;
    private final SensitiveWordService sensitiveWordService;
    private final String uploadDir;

    public CommentService(CommentDao commentDao,
                          NotificationService notificationService,
                          SensitiveWordService sensitiveWordService,
                          @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}") String uploadDir) {
        this.commentDao = commentDao;
        this.notificationService = notificationService;
        this.sensitiveWordService = sensitiveWordService;
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

    // ==================== 读：管理端全站列表（第 14 条端点） ====================

    /**
     * 管理端**全站**列表（{@code /comment/adminList}）。用户 2026-10-13 批的甲案 ——
     * 管理端「评论管理 / 问答管理」两栏此前只能**按对象下钻**（公开面 {@code /comment/list}
     * 强制要 {@code TargetId}，那条约束**不动**），缺的正是这张全站表。
     *
     * <h2>【与公开面 {@link #list} 的三条口径差别】</h2>
     * <ol>
     *   <li><b>没有观看者</b>：不收 {@code viewerUserId}、不回 {@code Mine} / {@code MyLike}
     *       （管理端列表里没有「我」这个视角）。⚠️ 但**匿名打码照旧** —— 见下面那条 ⚠️；</li>
     *   <li><b>顶层与回复都回</b>（公开面只要顶层）：回复同样会违规，管理端得看得见它。
     *       故 {@code Kind=answer} 在本方法里是有意义的（回答全是回复）；</li>
     *   <li><b>多一列 {@code IsTimeout}</b>：只对「顶层 + 未解决 + 超时」的提问为 {@code true}，
     *       分钟数取自 {@link QuestionTimeoutJob#TIMEOUT_MINUTES}（**不是**本类另写的数），
     *       判据在 SQL 里与 {@code QuestionTimeoutJob} 的扫描同源。</li>
     * </ol>
     *
     * <p>⚠️ <b>匿名行在这里同样不泄露 {@code AuthorId}</b>：{@link #decorate} 把匿名行的
     * {@code AuthorId} 置 {@code null}、显示名打码。这是**本地裁定**（方案只写了公开面那条）：
     * 「管理端要治谁一目了然」是个**新能力**，没人批过；而匿名是用户在商品页上被承诺过的口径 ——
     * 治理需要身份的话，那是一次独立的需求变更（要改就改在这里，别顺手把 decorate 绕过去）。
     *
     * @param kind   **必填**：{@code review} / {@code question} / {@code answer}（未知值拒，不静默当「全筛」）
     * @param targetType / targetId  可选**成对**收窄到某个对象下（只给一个 ⇒ 拒：半个键是拼错的调用，
     *               静默当「全站」会让管理员以为筛过了）
     * @param solved  仅 {@code kind=question} 有意义：{@code "1"} 已解决 / {@code "0"} 未解决 ——
     *               ⚠️ 给别的 kind 传它 ⇒ 拒（不是「忽略」：忽略会让前端以为筛了）
     */
    public Map<String, Object> adminList(String kind, String targetType, String targetId, String solved,
                                         Integer limit, Integer offset) {
        if (isBlank(kind)) {
            return fail("缺少参数：Kind（只能是 review / question / answer）");
        }
        String k = kind.trim();
        if (!CommentKinds.knownKind(k)) {
            return fail("未知的评论类型：" + kind + "（只能是 review / question / answer）");
        }
        String tt = null;
        String tid = null;
        if (!isBlank(targetType) || !isBlank(targetId)) {
            if (isBlank(targetType) || isBlank(targetId)) {
                return fail("TargetType 与 TargetId 要么都给、要么都不给（都不给 = 全站）");
            }
            tt = targetType.trim();
            if (!CommentKinds.knownTargetType(tt)) {
                return fail("未知的评论对象类型：" + targetType + "（只能是 goods / user）");
            }
            tid = targetId.trim();
        }
        String solvedKey = isBlank(solved) ? null : solved.trim();
        if (solvedKey != null && !"0".equals(solvedKey) && !"1".equals(solvedKey)) {
            return fail("未知的解决状态：" + solved + "（只能是 0 / 1）");
        }
        if (solvedKey != null && !CommentKinds.QUESTION.equals(k)) {
            return fail("解决状态只对提问有意义（Kind=question），当前 Kind=" + k);
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = (offset == null || offset < 0) ? 0 : offset;
        try {
            List<Map<String, Object>> rows = commentDao.selectForAdmin(tt, tid, k, solvedKey,
                    QuestionTimeoutJob.TIMEOUT_MINUTES, effectiveLimit, effectiveOffset);
            long total = commentDao.countForAdmin(tt, tid, k, solvedKey);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                // viewer=null + 空图 / 空赞表：管理端列表不掺「我的」视角，也不展开图与赞
                //（下钻到对象那条路上有）。⚠️ 匿名打码在这条路上**照样生效**，理由见方法注释。
                Map<String, Object> item = decorate(row, null, Map.of(), Map.of());
                // ⚠️ 超时标记**后端算、前端只渲染**（用户 2026-10-13 裁定）：前端自己拿数字比对
                //    就是第二处口径 —— 它跟 job 漂开时两边都不报错，只是「标红的」与「报过的」
                //    悄悄变成两批记录。分钟数的唯一出处是 QuestionTimeoutJob.TIMEOUT_MINUTES。
                item.put("IsTimeout", flag(row.get("IsTimeout")));
                out.add(item);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", out);
            result.put("total", total);
            result.put("limit", effectiveLimit);
            result.put("offset", effectiveOffset);
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
     * @param source     **可选**的写入来源（C3 块 7 · commit B 新增，加在**末尾**）——
     *                   写进 {@code comment.Source}（{@code varchar(16)}）。空 / 缺省 ⇒ 写 {@code ''}，
     *                   与加它之前**逐字节同行为**（既有调用点一个都不改）。
     *                   ⚠️ 它的**唯一**用途是让演练写入的行可辨认（{@code 'attack'}），
     *                   而 {@code /comment/add} 是**公开面**端点 ⇒ 这**不是**一个身份声明、
     *                   也**不参与任何鉴权判断**（谁能发评论仍由 {@code IdentityGuard} 说了算）。
     */
    @Transactional
    public Map<String, Object> add(String account, String targetType, String targetId, String parentIdRaw,
                                   String replyToIdRaw, String kindRaw, String content, String ratingRaw,
                                   String anonymousRaw, String productCode, List<MultipartFile> files,
                                   String source) {
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
        // ⚠️ 超长**拒**而不是截断：Source 是写入方自己声明的标记，悄悄截断会让
        //    `attack` 变成 `attack` 之外的别的东西而**不报错**（标错来源比没有来源坏）。
        //    位置在**碰库之前** —— 同 ProductCode 那条，白跑一趟没有意义。
        if (!isBlank(source) && source.trim().length() > MAX_SOURCE) {
            return fail("Source 最多 " + MAX_SOURCE + " 字符");
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

            // ── 敏感词（方案 §4.5；用户 2026-10-13 整改指令 §六）──────────────────────────
            // 命中占比 < 40% ⇒ 正文就地打码（`text` 换成打码版，后面 INSERT / 通知 / 正文落库全用它）；
            // 命中占比 >= 40% ⇒ **拒收**。判定与口径全在 `SensitiveWordService.filter` 那一处，
            // 本类只做「拒 or 换」这一个决定 —— 别在这里再实现一遍占比。
            // ⚠️ 位置在**碰库之后、INSERT 之前**：`selectRoleByUsername` 已经证明这条连接是通的，
            //    词表读不到会在这里抛出、落进本方法既有的 catch ⇒ fail-closed（不带着未检正文入库）。
            // ⚠️ 「不提示用户」（§4.5）：拒收只回一句不点名词的 `REJECT_MSG`；打码路径**回成功**，
            //    用户自己在列表里看见 `***`。
            Map<String, Object> sensitive = sensitiveWordService.filter(text);
            if (Boolean.TRUE.equals(sensitive.get("rejected"))) {
                return fail(SensitiveWordService.REJECT_MSG);
            }
            text = String.valueOf(sensitive.get("masked"));

            String tt;
            String tid;
            String kind;
            boolean merchantFirst = false;
            // @ 自动生成（方案 §4.1）的被 @ 人 —— 登录名。三条规则各在下面两个分支里解析出来
            // （顶层 → 商品商家 / 留言板主；回复 → 顶层作者；回复回复 → 被回复人）。
            // 解析不出来（商品不存在 / 留言板主查无）时为 null ⇒ {@link #recordMention} 不 @ 任何人。
            String mentionTo = null;
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
                mentionTo = resolveTopLevelMention(tt, tid);
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
                // 「回复顶层 → @ 顶层作者」——父行的作者（已经是登录名）。
                mentionTo = str(parent.get("AuthorId"));
                if (replyToId != null) {
                    Map<String, Object> target = commentDao.selectComment(replyToId);
                    if (target == null) {
                        return fail("被回复的回复不存在");
                    }
                    if (!String.valueOf(parentId).equals(str(target.get("ParentId")))) {
                        return fail("ReplyToId 与被回复的楼层不匹配");
                    }
                    // 「回复回复 → @ 被回复人」——覆盖上面那句（被 @ 的是**被回复那条**的作者）。
                    mentionTo = str(target.get("AuthorId"));
                }
                // 方案 §4.1「商家首次回复 → 置顶；商家后续回复 → 混排」—— 进楼层前先数一次。
                merchantFirst = role == CommentKinds.ROLE_MERCHANT
                        && commentDao.countMerchantFirstUnder(parentId) == 0;
            }

            boolean anonymous = resolveAnonymous(kind, role, anonymousRaw);
            int rating = resolveRating(kind, anonymous, ratingRaw);

            int inserted;
            try {
                inserted = commentDao.insertComment(tt, tid, parentId, replyToId, kind, author, role,
                        anonymous, rating, text, isBlank(productCode) ? null : productCode.trim(),
                        merchantFirst, source);
            } catch (DuplicateKeyException e) {
                // Migration ⑯ 的 uk_comment_review_once —— **只在并发**下才会先于上面那道预检拦到
                // （两个请求同刻过了 `checkReviewEligible`、双双走到这里）。
                // ⚠️ 必须在这里翻成业务话术：`add` 外层那个 `catch (Exception)` 会把它渲染成
                //    「评论发表失败：Duplicate entry 'goods:76:1111' for key …」—— 那是把 DB 内部
                //    标识（键名、拼出来的键值）端到用户面前，且与预检那句话说不到一块去。
                // ⚠️ 这句 catch 刻意**只包 INSERT 这一句**：它能抛 DuplicateKeyException 的唯一
                //    出处就是本键（comment 表其余 4 个 KEY 都不唯一），但把包子范围收窄成「就这一句」
                //    之后，将来若有人给 comment 加第二个唯一键，这里不会替它撒谎。
                return fail(MSG_DUPLICATE_REVIEW);
            }
            if (inserted == 0) {
                return fail("评论发表失败，请重试");
            }
            // ⚠️ 与上面那条 INSERT 同事务（类上 @Transactional）才认得住：无事务时
            //    LAST_INSERT_ID() 可能落在**另一条连接**上，拿到的是别人的行号。
            long id = commentDao.lastInsertId();

            // @ 自动生成（方案 §4.1）—— 落 comment_mention + 发 comment.mention 通知。
            // ⚠️ 放在图片那段**之前**：图片目录不可用会 `return okAdded(...)`（见那段），
            //    放后面的话那条早退会把 @ 一起跳过。
            recordMention(id, mentionTo, author, text, anonymous);
            // 通知矩阵其余两条 code（块 6 基准文档 §二 A1；用户 2026-10-13 裁定的 6 code 全程）：
            //   ① `comment.on.post` —— 商品被**评价**（Kind=review、TargetType=goods、ParentId 空）→ 商家；
            //   ② `comment.reply`   —— 评论被**回复**（ParentId 非空）→ 被回复人。
            // ⚠️ 上述三条 code（mention / on.post / reply）是**彼此独立**的事件，同一次发表可能同时命中
            //    （如某客户评价商品、另一客户在其下回复）⇒ 按用户口径各发各的，**不因语义重叠合并**
            //    （用户 2026-10-13：「不得再以『语义重叠』为由摘除契约 code」）。
            notifyOnPost(id, parentId, tt, tid, kind, author, text, anonymous);
            notifyReply(id, parentId, mentionTo, author, text, anonymous);

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
            return fail(MSG_DUPLICATE_REVIEW);
        }
        return null;
    }

    // ==================== 写：@ 自动生成（块 6 commit 3） ====================

    /**
     * 顶层评论该 @ 谁（方案 §4.1「顶层评论 → @ 该商品商家」）。回**登录名** / {@code null}。
     *
     * <ul>
     *   <li>{@code TargetType='goods'} ⇒ 该商品的**商家** —— 经
     *       {@code goods.Id → MerchantId（业务号）→ user.merchantid → 登录名} 两跳解析
     *       （{@link CommentDao#selectMerchantUsernameByGoodsId}）；</li>
     *   <li>{@code TargetType='user'}（用户留言板）⇒ **板主本人** —— 方案 §4.1 只写了商品那一条，
     *       这是**本地裁定**：留言板没有「商家」，与「被 @ 的那个人 = 这个对象的主人」同一语义，
     *       故取 {@code TargetId}（业务号）翻成登录名。不补这一条的话，留言板上的评论会
     *       **静默地一个 @ 都不产生**（不报错、没人看得出漏了）。</li>
     * </ul>
     *
     * <p>⚠️ 解析不出（商品不存在 / 板主查无 / {@code TargetId} 非数字）⇒ {@code null}，
     * **不**退化成 @ 一个空串（那会落一行 {@code MentionedId=''} 的脏关系）。
     */
    private String resolveTopLevelMention(String targetType, String targetId) {
        if (CommentKinds.TARGET_GOODS.equals(targetType)) {
            Long goodsId = parseLong(targetId);
            return goodsId == null ? null : commentDao.selectMerchantUsernameByGoodsId(goodsId);
        }
        if (CommentKinds.TARGET_USER.equals(targetType)) {
            return commentDao.selectUsernameByUserId(targetId);
        }
        return null;
    }

    /**
     * 落 @ 关系 + 发 {@code comment.mention} 通知（方案 §4.1「@ 由后端自动生成」）。
     *
     * <h2>【⚠️ 自 @ 不落、也不发】</h2>
     * 被 @ 人与发表人是同一个人时（作者回复自己的顶层评论 / 商家评论自家商品 —— 后者被
     * 「买过才评」挡住，前者真实可达）**直接跳过**：@ 的唯一用途是**通知对方**，
     * 通知自己是一条纯噪声。方案没写这一条，是**本地裁定**（同 {@code MAX_IMAGES} 的处置）。
     * ⇒ {@code comment_mention} 表里因此**不会**出现「作者 @ 自己」的行。（改这条前先想清楚
     * 下游有没有靠「行数 = 提及次数」做统计的——目前没有。）
     *
     * <h2>【⚠️ 关系没落住就不发通知】</h2>
     * {@code comment_mention} 是「被 @ 」这件事的**凭据**，通知是它的**派生物**。若关系插入失败而
     * 通知照发，收件人会读到一条「你被 @ 了」、点进去却找不到任何 @ 关系（下游按 {@code comment_mention}
     * 反查谁会看到这个不一致）。故插入失败即 return。
     *
     * <h2>【⚠️ 全程 fail-soft —— 绝不把评论翻成失败】</h2>
     * 本方法在评论**已经落库**之后调用（同 {@link #notifyAccepted} / 图片那段）。@ 是**派生物**，
     * 它出问题不该让用户以为评论没发出去（那他会重发，于是多一条评论）。故整段 catch —— 但
     * **如实记日志**，不静默。
     *
     * <h2>【⚠️ 匿名不得经通知反查】</h2>
     * 回答（{@code answer}）恒匿名、评价可勾匿名 ⇒ 正文里**不写发表人**，写「有人」。
     * 否则一条「匿名」的回复会经由发给对方的通知**实名暴露**作者（与 {@link #decorate} 抹
     * {@code AuthorId} 是同一个理由）。
     *
     * @param mentioned 被 @ 人（登录名）；{@code null} / 空白 ⇒ 无事发生
     * @param author    发表人（登录名）—— 用于判自 @ 与（非匿名时）填进正文
     */
    private void recordMention(long commentId, String mentioned, String author, String content, boolean anonymous) {
        if (isBlank(mentioned)) {
            return;
        }
        String to = mentioned.trim();
        if (to.equals(author)) {
            return;   // 自 @ —— 见方法注释
        }
        try {
            commentDao.insertMention(commentId, to);
        } catch (Exception e) {
            e.printStackTrace();
            return;   // 关系没落住就不发通知 —— 见方法注释
        }
        try {
            Integer role = commentDao.selectRoleByUsername(to);
            String toType = (role != null && role == CommentKinds.ROLE_MERCHANT)
                    ? NotificationService.TO_TYPE_MERCHANT : NotificationService.TO_TYPE_CUSTOMER;
            // ⚠️ **自 @ 判据已经在上面比完了（登录名域），这里才开始换算** —— 顺序不能反：
            //    换完域再比，比的就成了「两个人的业务号」，而上面 `to.equals(author)` 那条
            //    是「同一个登录名」，两件事。见【一列两种号】。
            // ⚠️ 【一列两种号】`comment_mention.MentionedId` 要**登录名**（上面 insertMention 传的就是 `to`），
            //    `notification.ToId` 要**业务号**（见 NotificationController 的 javadoc）。
            //    同一次发言往两张表写两种号 ⇒ 这里是唯一一处换算。
            String toBizId = commentDao.selectUserIdByUsername(to);
            if (isBlank(toBizId)) {
                return;   // 换算不出业务号 ⇒ 收件箱按业务号拉，写了也是一条谁都读不到的死行
            }
            String who = anonymous ? "有人" : "「" + author + "」";
            notificationService.send(UUID.randomUUID().toString(), toBizId, toType,
                    NotificationEvents.COMMENT_MENTION,
                    "你被 @ 提及",
                    who + "在评论中 @ 了你：" + brief(content),
                    NOTIFY_TARGET_COMMENT, String.valueOf(commentId));
        } catch (Exception e) {
            e.printStackTrace();   // 通知已落库还是没落库 —— send 自己 fail-soft，这里兜解析角色那一步
        }
    }

    // ==================== 写：通知矩阵其余两条 code（块 6 commit 3 契约纠偏） ====================

    /**
     * {@code comment.on.post} —— **商品被评价**（方案 A1「被评论」）→ 触达该商品的**商家**。
     *
     * <p>触发条件三合一（缺一不可）：{@code ParentId IS NULL}（顶层）+ {@code TargetType='goods'}
     * + {@code Kind='review'}（评价）。**提问**（{@code question}）不在此列 —— 顶层提问 @ 商家
     * 走的是 {@link #recordMention} 的 {@code comment.mention}（方案 A1 把「评价商品」与
     * 「提问/被 @」分成两个 code，用户 2026-10-13 裁定不得因语义重叠合并）。
     *
     * <p>收件人解析不出的（商品查无 / 无商家）或**就是发表人自己**（商家评自家商品，本被
     * {@code checkReviewEligible} 的「买过才评」挡住，此处仍设一道防）⇒ 跳过。
     *
     * <p>⚠️ 全程 fail-soft（同 {@link #recordMention}）：本条在评论**已经落库**之后调用，
     * 派生通知出问题不该把评论翻成失败（翻了用户会重发、多一条评论）。
     */
    private void notifyOnPost(long commentId, Long parentId, String targetType, String targetId,
                              String kind, String author, String content, boolean anonymous) {
        if (parentId != null
                || !CommentKinds.TARGET_GOODS.equals(targetType)
                || !CommentKinds.REVIEW.equals(kind)) {
            return;
        }
        try {
            Long goodsId = parseLong(targetId);
            if (goodsId == null) {
                return;
            }
            // ⚠️ 【一列两种号】收件人只要**业务号**（`notification.ToId` 的契约）
            //    ⇒ 直接取 `goods.MerchantId`，**不必**绕 `user.merchantid → user.username` 那一跳
            //    （那一跳是给 `comment_mention.MentionedId` 用的，见 resolveTopLevelMention）。
            String merchantBizId = commentDao.selectMerchantIdByGoodsId(goodsId);
            if (isBlank(merchantBizId)) {
                return;
            }
            // 自评（商家评自家商品，本被 checkReviewEligible「买过才评」挡住，此处仍设一道防）⇒ 跳过。
            // ⚠️ 判据两侧必须**同域**：上面取的是商家的业务号，故这里要比发表人的**业务号**
            //    （比登录名 = 拿两种号相比，恒不相等，这道防等于没有）。
            if (merchantBizId.equals(commentDao.selectUserIdByUsername(author))) {
                return;
            }
            String who = anonymous ? "有人" : "「" + author + "」";
            notificationService.send(UUID.randomUUID().toString(), merchantBizId,
                    NotificationService.TO_TYPE_MERCHANT,
                    NotificationEvents.COMMENT_ON_POST,
                    "您的商品收到新评价",
                    who + "评价了您的商品：" + brief(content),
                    NOTIFY_TARGET_COMMENT, String.valueOf(commentId));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * {@code comment.reply} —— **评论被回复**（方案 A1「被回复」）→ 触达**被回复人**。
     *
     * <p>{@code ParentId} 非空即触发；被回复人取 {@code add} 里三层 @ 规则的解析结果
     * （{@code mentionTo}：回顶层 → 顶层作者；回复回复 → {@code ReplyToId} 那条的作者），
     * 本方法直接复用、不重复解析 —— 那两条规则给出的「被 @ 人」与「被回复人」在回复路径上
     * 是**同一个人**。
     *
     * <p>自回复（被回复人就是发表人，如作者在自己的楼层下追问）跳过 —— 与
     * {@link #recordMention} 的自 @ 跳过同一判据（通知自己是一条纯噪声）。方案未写此边界，
     * 属**本地裁定**（先例：{@code MAX_IMAGES}）。
     *
     * <p>⚠️ 全程 fail-soft（同 {@link #recordMention}）。
     */
    private void notifyReply(long commentId, Long parentId, String repliedTo, String author,
                             String content, boolean anonymous) {
        if (parentId == null || isBlank(repliedTo) || repliedTo.trim().equals(author)) {
            return;
        }
        String to = repliedTo.trim();
        try {
            Integer role = commentDao.selectRoleByUsername(to);
            String toType = (role != null && role == CommentKinds.ROLE_MERCHANT)
                    ? NotificationService.TO_TYPE_MERCHANT : NotificationService.TO_TYPE_CUSTOMER;
            // ⚠️ 【一列两种号】`repliedTo` 全程是**登录名**（自回复那条判据在上面就比完了，
            //    同理不许换到这儿再比）；`notification.ToId` 要业务号 ⇒ 这里换算一次。
            String toBizId = commentDao.selectUserIdByUsername(to);
            if (isBlank(toBizId)) {
                return;   // 同 recordMention：换算不出就别写死行
            }
            String who = anonymous ? "有人" : "「" + author + "」";
            notificationService.send(UUID.randomUUID().toString(), toBizId, toType,
                    NotificationEvents.COMMENT_REPLY,
                    "您的评论收到回复",
                    who + "回复了你：" + brief(content),
                    NOTIFY_TARGET_COMMENT, String.valueOf(commentId));
        } catch (Exception e) {
            e.printStackTrace();
        }
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
     *
     * <p>⚠️ 一条**额外**的闸（方案 §4.2）：**顶层提问不可被提问者删**，管理员除外。见方法体内注释。
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
            // 方案 §4.2「删除：提问者不能删；商家可提删 → 管理端裁判」。
            // ⚠️ **顶层提问**（`Kind='question'` 且 `ParentId` 空）不在「作者可删」那一条里 ——
            // 提问一旦被删，下面全部回答（含已被采纳的那条）就都失去了宿主 ⇒ 只有管理端能直接删。
            // 非管理员（含提问者本人，也含商家）走到这里一律拒；商家的诉求表达走**提删**
            // （= 走举报通道，`TargetType='comment'`，见 ReportService），由管理端裁决。
            if (!admin && row.get("ParentId") == null
                    && CommentKinds.QUESTION.equals(str(row.get("Kind")))) {
                return fail("提问不能自行删除，如有需要可请商家提删、由管理端裁决");
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

    // ==================== 图片审核（管理端，块 6 · commit 4，方案 §4.3） ====================

    /** {@code imageReview} 的三个动作（写侧收紧，别把入参原样当动作使）。 */
    public static final String ACTION_LIST = "list";
    public static final String ACTION_APPROVE = "approve";
    public static final String ACTION_REJECT = "reject";

    /**
     * 图片审核（方案 §4.3）—— <b>一条端点、三个动作</b>，靠 {@code action} 分派：
     * <pre>
     *   action=list（默认）→ 按 {@code status} 拉一批（默认 pending），供管理端「待审图片」；
     *   action=approve     → 过审：pending → approved，前端随之正常显示；
     *   action=reject      → 驳回：pending → rejected **+ 删仓库文件**，前端显「图片违规已删除」。
     * </pre>
     *
     * <p>⚠️ 为什么是**一条**端点而不是三条：方案 §三 把端点总数钉死在 13 个，且这三个动作
     * 天然是「图片审核」一件事（同 {@link #add} 用 {@code Kind}/{@code ParentId} 分四态的做法）。
     *
     * <p>⚠️ 驳回删文件用的是 {@code ImageUrl} 的**尾段**（= 落盘名）—— 见 {@code UploadStorage.store}
     * 那条「必须用返回值拼 URL，不能拿原始上传名」；此处反过来，从 URL 剥前缀还原落盘名。
     *
     * @param action {@code list / approve / reject}；空白 = {@code list}
     * @param id     单张图 Id（{@code approve} / {@code reject} 必填）
     * @param status {@code list} 的筛子：{@code pending / approved / rejected}；空白 = {@code pending}
     */
    public Map<String, Object> imageReview(String action, Long id, String status,
                                           Integer limit, Integer offset) {
        String act = isBlank(action) ? ACTION_LIST : action.trim().toLowerCase();
        if (ACTION_LIST.equals(act)) {
            String st = isBlank(status) ? CommentKinds.IMAGE_PENDING : status.trim();
            if (!isImageStatus(st)) {
                return fail("未知的图片状态：" + status + "（只能是 pending / approved / rejected）");
            }
            int effectiveLimit = normalizeLimit(limit);
            int effectiveOffset = (offset == null || offset < 0) ? 0 : offset;
            try {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("list", commentDao.selectImagesByStatus(st, effectiveLimit, effectiveOffset));
                result.put("total", commentDao.countImagesByStatus(st));
                result.put("limit", effectiveLimit);
                result.put("offset", effectiveOffset);
                return result;
            } catch (Exception e) {
                e.printStackTrace();
                return fail("图片列表加载失败：" + e.getMessage());
            }
        }
        if (!ACTION_APPROVE.equals(act) && !ACTION_REJECT.equals(act)) {
            return fail("未知的动作：" + action + "（只能是 list / approve / reject）");
        }
        if (id == null) {
            return fail("缺少参数：Id");
        }
        boolean approve = ACTION_APPROVE.equals(act);
        try {
            Map<String, Object> image = commentDao.selectImage(id);
            if (image == null) {
                return fail("图片不存在");
            }
            String next = approve ? CommentKinds.IMAGE_APPROVED : CommentKinds.IMAGE_REJECTED;
            // ⚠️ CAS：`UPDATE … WHERE Id = ? AND Status = 'pending'` ⇒ 并发下第二个人的 affected=0
            //    ⇒「删仓库文件」这一记不可逆动作**只发生一次**。
            if (commentDao.updateImageStatus(id, next) == 0) {
                return fail("该图片已被审核");
            }
            boolean fileDeleted = !approve && deleteImageFile(str(image.get("ImageUrl")));
            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            ok.put("Id", id);
            ok.put("Status", next);
            ok.put("fileDeleted", fileDeleted);
            // ⚠️ `before` = 审核**之前**那张图的整行快照 —— 给调用方挂钩审计用
            //    （`CommentController` 的 `comment.image.approve` / `comment.image.reject`）。
            //    口径同 `AdminCatalogController` / `AdminTemplateController` 的 `result.get("before")`。
            //    审核**不可逆**（驳回真删文件），这一列就是「当时那张图是什么」的唯一凭据。
            //    ⚠️ 它的 `Status` 恒为 `pending` —— 上面那条 CAS 的 `WHERE` 已经保证了这一点。
            ok.put("before", image);
            ok.put("msg", approve ? "已通过" : "已驳回并删除图片文件");
            return ok;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("图片审核失败：" + e.getMessage());
        }
    }

    /**
     * 删一张已落盘的评论图（方案 §4.3 第 5 步）。
     *
     * <p>入参是 {@code comment_image.ImageUrl}（{@link #IMG_URL_PREFIX} + 落盘名）⇒
     * 先确认它确是本前缀下的 URL，再取**尾段**当文件名，拒绝任何带路径分隔符的尾段
     * （防 {@code ..} 之类越出上传目录；落盘名由 {@code UploadStorage} 生成、本不含分隔符，
     * 这一条是**纵深防御**而非必要步骤）。文件不在 ⇒ 也算成功（幂等）。
     *
     * <p>⚠️ 已知局限（如实记）：{@code UploadStorage} 对「同名同内容」的多份上传**复用同一个文件**
     * ⇒ 若两条 {@code comment_image} 行指向同一个 URL，驳回其中一条会连带让另一条 404。
     * 方案 §4.3 要的就是「驳回即删文件」，这里不改那条策略，只把代价写清楚。
     *
     * @return 是否确实删掉了一个文件（不存在 / 非法 URL ⇒ {@code false}，但**不算失败**）
     */
    private boolean deleteImageFile(String imageUrl) {
        if (isBlank(imageUrl) || !imageUrl.startsWith(IMG_URL_PREFIX)) {
            return false;
        }
        String name = imageUrl.substring(IMG_URL_PREFIX.length());
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
            return false;
        }
        try {
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(IMG_SUBDIR);
            return Files.deleteIfExists(dir.resolve(name));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 图片三态的白名单判据（写侧收紧，同 {@code CommentKinds} 的取值域）。 */
    private static boolean isImageStatus(String s) {
        return CommentKinds.IMAGE_PENDING.equals(s)
                || CommentKinds.IMAGE_APPROVED.equals(s)
                || CommentKinds.IMAGE_REJECTED.equals(s);
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
        // ⚠️ 【一列两种号】`toId` 取自 `reply.AuthorId` —— 那是**登录名**；
        //    `notification.ToId` 的契约是**业务号** ⇒ 这里换算（本方法只发通知，不动 comment_mention）。
        String toBizId = commentDao.selectUserIdByUsername(toId);
        if (isBlank(toBizId)) {
            return;   // 同 recordMention：换算不出就别写死行
        }
        notificationService.send(UUID.randomUUID().toString(), toBizId, toType,
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
