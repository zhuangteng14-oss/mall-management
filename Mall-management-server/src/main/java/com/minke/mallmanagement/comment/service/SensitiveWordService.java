package com.minke.mallmanagement.comment.service;

import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.comment.dao.SensitiveWordDao;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感词域的服务层（C3 块 6 · 整改 commit 5）—— <b>词表维护 + 命中判定 + 历史评论扫描</b>。
 * 契约与规则见 {@code docs/agent/context-packs/块6参考.md} §4.5（下称「方案」）；
 * 路由与入参绑定在 {@code SensitiveWordController}，词表访问在
 * {@link SensitiveWordDao}，评论侧的扫描取数 / 覆写在 {@link CommentDao}。
 *
 * <h2>【两条规则（方案 §4.5 逐字）】</h2>
 * <ul>
 *   <li>命中但占比 {@code < 40%} ⇒ 屏蔽 {@code ***} + 落库；</li>
 *   <li>命中占比 {@code >= 40%} ⇒ <b>拒收</b>。</li>
 * </ul>
 *
 * <h2>【⚠️「占比」是本笔**唯一**要裁的口径 —— 定成「命中字符数 ÷ 正文长度」】</h2>
 * 方案只写了「占比」两个字，没写分子分母。三种可能读法里：
 * <ol>
 *   <li><b>命中字符数 ÷ 正文长度</b>（<b>采此</b>）—— 「这条正文里有多少比例的字是敏感内容」。
 *       与「一条全是敏感词的评论（{@code 100%}）该拒、一条顺带提了一嘴的（{@code 10%}）
 *       该打码」这个直觉一致；可手工复算（字数得出来）。</li>
 *   <li>命中词数 ÷ 词表大小 —— 与正文长度无关，正文再长也不会变，显然不是本意。</li>
 *   <li>命中词数 ÷ 正文词数 —— 中文没有词边界，要先分词；本仓没有分词器，引一个
 *       为一句 40% 的数字不值。</li>
 * </ol>
 * ⇒ 取第一种，且分母是**原始正文长度**（打码后 {@code ***} 三字符会改变长度，
 * 用打码后的长度算会让「占比」随打码长度漂移）。
 *
 * <h2>【⚠️「同一段文字被两个词覆盖」不重复计数】</h2>
 * 扫描是**从左到右、不重叠**的（{@link #filter} 里那个 while）：命中一次就跳过整段。
 * 词表按**长度倒序**排（{@link #sortedWords}）⇒ 长词优先 —— {@code '广告'} 与 {@code '广'}
 * 同时在表里时，{@code '广告'} 命中，不会先被 {@code '广'} 吃掉一半。
 * 这条不做的后果是可复算性丢失：{@code hits} 可能大于正文长度，占比算出 {@code >100%}。
 *
 * <h2>【⚠️「不提示用户」= 不告诉他哪个词、也不告诉他被打了码】</h2>
 * 方案 §4.5「不提示用户（发出后自己看 {@code ***}）」。故：
 * <ul>
 *   <li>打码路径**回成功**（{@code success:true}）—— 用户从列表里看到 {@code ***} 自己明白；</li>
 *   <li>拒收路径只回一句 {@link #REJECT_MSG}，<b>不回命中词</b>（回了就是把词表交给用户去试探）。</li>
 * </ul>
 *
 * <h2>【⚠️ 删词不回填历史评论】</h2>
 * 打码是**就地覆写** {@code comment.Content}（没有第二份原文）⇒ 把词删掉，已经变成
 * {@code ***} 的评论**不会**自动还原。方案也只写了「加词后扫老评论」，没写反过来这一步。
 * 这是本笔**有意**留下的单向性，写在 {@link #delete} 的注释里。
 */
@Service
public class SensitiveWordService {

    /** 打码的替身（方案 §4.5 逐字三个星号）。 */
    public static final String MASK = "***";

    /** 拒收门槛：命中字符占比 **≥** 这个数就拒（方案 §4.5 的 40%）。 */
    public static final double REJECT_RATIO = 0.40d;

    /** {@code Word} 列是 {@code varchar(64)}（方案 §一.6）—— 超了 strict mode 下 ERROR 1406。 */
    public static final int MAX_WORD = 64;

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    /**
     * 拒收时回的**唯一**一句话 —— 刻意不提是哪个词、也不提「敏感词」三个字里的细节
     * （方案 §4.5「不提示用户」；回了命中词就等于把词表交给用户二分试探）。
     */
    public static final String REJECT_MSG = "内容包含违规信息，请修改后重新发表";

    /** 重复添加的原话（业务规则，不是故障 —— 前端别弹「系统错误」）。 */
    public static final String DUPLICATE_MSG = "该敏感词已存在";

    private final SensitiveWordDao sensitiveWordDao;
    private final CommentDao commentDao;

    /** 观测出口（块 7 · commit 2b）—— 「拦住了多少」只有本环读得到（拒收那条早退不抛异常）。 */
    private final SensitiveMetrics sensitiveMetrics;

    public SensitiveWordService(SensitiveWordDao sensitiveWordDao, CommentDao commentDao,
                                SensitiveMetrics sensitiveMetrics) {
        this.sensitiveWordDao = sensitiveWordDao;
        this.commentDao = commentDao;
        this.sensitiveMetrics = sensitiveMetrics;
    }

    // ==================== 读：词表（管理端） ====================

    /** 词表列表。{@code limit} 缺省 100、上限 500（同 {@code ReportService.list} 的口径）。 */
    public Map<String, Object> list(Integer limit, Integer offset) {
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = (offset == null || offset < 0) ? 0 : offset;
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("list", sensitiveWordDao.selectWords(effectiveLimit, effectiveOffset));
            result.put("total", sensitiveWordDao.countWords());
            result.put("limit", effectiveLimit);
            result.put("offset", effectiveOffset);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("敏感词列表加载失败：" + e.getMessage());
        }
    }

    // ==================== 写：加词（+ 扫老评论） ====================

    /**
     * 加一个词，**并立刻扫一遍历史评论**（方案 §4.5「加词后扫老评论」）。
     *
     * <p>⚠️ 扫描做的是**打码**，不是删评论 —— 理由见 {@link #maskOldComments}。
     *
     * <p>⚠️ {@link #MAX_WORD} / 判重都在**碰库之前**或**插之前**：{@code Word} 上有唯一键，
     * 重复插入是**抛异常**而不是回 0（见 {@link SensitiveWordDao} 类注释）。
     *
     * <p>⚠️ {@code @Transactional}：{@link SensitiveWordDao#lastInsertId} 只在事务里可信
     * （同 {@code CommentService.add} 的理由）。扫描与覆写也一并落在这个事务里 ——
     * 「词进库了但一条老评论都没打码」这种半成品状态不该被外部看见。
     *
     * @param word 词面（会 {@code trim}；空 ⇒ 拒）
     */
    @Transactional
    public Map<String, Object> add(String word) {
        String w = trimToNull(word);
        if (w == null) {
            return fail("缺少参数：Word");
        }
        if (w.length() > MAX_WORD) {
            return fail("敏感词最多 " + MAX_WORD + " 字符（当前 " + w.length() + " 字符）");
        }
        try {
            if (sensitiveWordDao.selectByWord(w) != null) {
                return fail(DUPLICATE_MSG);
            }
            if (sensitiveWordDao.insertWord(w) == 0) {
                return fail("敏感词添加失败，请重试");
            }
            long id = sensitiveWordDao.lastInsertId();
            int masked = maskOldComments(w);

            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            ok.put("Id", id);
            ok.put("Word", w);
            ok.put("masked", masked);
            ok.put("msg", masked > 0
                    ? "已添加，并屏蔽了 " + masked + " 条历史评论"
                    : "已添加（历史评论无命中）");
            return ok;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("敏感词添加失败：" + e.getMessage());
        }
    }

    // ==================== 写：删词 ====================

    /**
     * 删一个词。
     *
     * <p>⚠️ <b>不回填历史评论</b>：打码是就地覆写 {@code comment.Content}，原文没有第二份副本
     * ⇒ 已经变成 {@code ***} 的那些**不会**因为删词而还原（方案 §4.5 只写了「加词后扫老评论」
     * 这一个方向）。要可逆就得另存一份原文列，那是**改表**、超出本笔边界；
     * 这里把它写成一句明确的话，而不是留给下一个人去猜「为什么删了词评论还是 ***」。
     *
     * @param id 词条 Id（管理端列表里那个）
     */
    public Map<String, Object> delete(Long id) {
        if (id == null) {
            return fail("缺少参数：Id");
        }
        try {
            // 改前态**先取下来**：`deleteById` 之后这一行就没了，`Word` 也就无从查起
            // —— 而审计（`admin_audit`）要的就是「删的是哪个词」，详见回体那两个键。
            Map<String, Object> row = sensitiveWordDao.selectById(id);
            if (row == null) {
                return fail("敏感词不存在");
            }
            if (sensitiveWordDao.deleteById(id) == 0) {
                return fail("该敏感词已被删除");
            }
            Map<String, Object> ok = new HashMap<>();
            ok.put("success", true);
            ok.put("Id", id);
            // ⚠️ `Word` / `before` 是给**调用方挂钩审计**用的（`SensitiveWordController` 的
            //    `AuditActions.SENSITIVE_WORD_DELETE`）：删词是不可逆的，词面是这行的业务键
            //    ⇒ 审计的 `TargetId` 得是它，而不是那个删完就没人对得上的自增 Id。
            //    口径同 `AdminCatalogController` / `AdminTemplateController` 的 `result.get("before")`。
            ok.put("Word", row.get("Word"));
            ok.put("before", row);
            ok.put("msg", "已删除（历史评论里的 *** 不回填）");
            return ok;
        } catch (Exception e) {
            e.printStackTrace();
            return fail("敏感词删除失败：" + e.getMessage());
        }
    }

    // ==================== 核心：命中判定 ====================

    /**
     * 跑一次命中判定 —— <b>本域的业务核心</b>，{@code CommentService.add} 发表前调它。
     *
     * <p>回四个键，全是**当前这次判定**的真值（不是「原值 ±1」那类增量）：
     * <pre>{@code
     * {"rejected":  bool   ,  // true ⇒ 发表方应拒收（命中占比 >= 40%）
     *  "masked":    String ,  // 打码后的正文（无命中 = 原文）
     *  "hits":      int    ,  // 被命中的**字符**数（不重叠）
     *  "hitRatio":  double }  // hits / 原文长度，[0,1]
     * }</pre>
     *
     * <p>⚠️ <b>{@code rejected} 为真时 {@code masked} 仍是打码后的文本</b>（不是原文）——
     * 这是刻意的防御：调用方万一漏判 {@code rejected} 直接用了 {@code masked}，
     * 落库的也是安全的文本，而不是原始敏感内容。
     *
     * <p>⚠️ <b>词表读不到 ⇒ 抛</b>（不在本方法里吞）：调用方那一路本来就包着 try/catch
     * 并且**同库**——词表读不到时那条 {@code INSERT} 也一样发不出去，fail-closed 不额外付代价。
     * 反过来说，绝不能在这里 fail-open 放原文过去：那正是「降级审核链路」。
     *
     * @param text 待检正文（{@code null} / 空白 ⇒ 直接回「无命中」）
     */
    public Map<String, Object> filter(String text) {
        // 块 7 · 阶段 3a.3：耗时起 = **方法入口**，止 = 下面 disposed 那行。⚠️ 口径**含读词库**：
        // sortedWords() 每次都会打一次 DB（selectAllWords），它通常才是这段耗时的主项。
        // 方案的 §2 把这条留成「待读全文再定」——读完全文，定成「含」（那才是调用方实际付的代价）。
        // 三个早退分支（空白 / 空词库 / 零命中）不发事件 ⇒ 它们不计耗时，也不会在页面上出现。
        long startedNanos = System.nanoTime();
        Map<String, Object> result = new HashMap<>();
        result.put("rejected", false);
        result.put("masked", text);
        result.put("hits", 0);
        result.put("hitRatio", 0.0d);
        if (isBlank(text)) {
            return result;
        }
        List<String> words = sortedWords();
        if (words.isEmpty()) {
            return result;
        }

        StringBuilder sb = new StringBuilder(text.length());
        // 块 7 · commit 8：命中的**词**（去重 + 插入序）。以前只往事件流报「命中几个」，页面答不出
        // 「命中了什么」—— 而那恰恰是看敏感词环时最该看的一个数。⚠️ 只进事件流，绝不进 tag。
        Set<String> hitWords = new LinkedHashSet<>();
        int hit = 0;
        int i = 0;
        while (i < text.length()) {
            String matched = matchAt(text, i, words);
            if (matched == null) {
                sb.append(text.charAt(i));
                i++;
            } else {
                sb.append(MASK);
                hitWords.add(matched);
                hit += matched.length();
                i += matched.length();
            }
        }
        if (hit == 0) {
            return result;
        }

        // 分母是**原始**正文长度（见类注释：打码后的长度含 `***`，用它算会让占比漂）。
        double ratio = (double) hit / (double) text.length();
        result.put("masked", sb.toString());
        result.put("hits", hit);
        result.put("hitRatio", ratio);
        if (ratio >= REJECT_RATIO) {
            result.put("rejected", true);
        }
        // 块 7 · 观测（commit 2b）：只记**真被处置的** —— 干净文本在上面三处早退里压根到不了这行
        //（与 commit 2 建环时的口径一致）。total 取**正文字符数** = 本类 ratio 的分母 ⇒ 事件里的
        // `hit / total` 就等于被拿去比阈值的那个命中率；场景现值只有一个（唯一调用点 =
        // `CommentService.add` 发表前）。
        if (Boolean.TRUE.equals(result.get("rejected"))) {
            sensitiveMetrics.rejected("评论", hit, text.length(), joinWords(hitWords), System.nanoTime() - startedNanos);
        } else {
            sensitiveMetrics.masked("评论", hit, text.length(), joinWords(hitWords), System.nanoTime() - startedNanos);
        }
        return result;
    }

    /**
     * 「加词后扫老评论」（方案 §4.5）—— 把所有**未删除**且含 {@code word} 的评论重新打码一遍。
     *
     * <p>⚠️ <b>只打码，不删评论，也不套 40% 那条拒收规则</b>：拒收是**发表时**的动作
     * （「不接受这条」），一条已经在库里的评论没有「拒收」这个选项 —— 对老评论能做的只有
     * 打码（可读性保留）与删除（内容消失）。管理端**加一个词**就批量删掉用户的历史发言，
     * 是把一次词表维护变成一次不可逆的内容处置 ⇒ 不取。
     *
     * <p>⚠️ 扫的是**整张词表**，不是只打新加那个词：一条老评论里可能同时有别的新词旧词
     * （上一次扫描漏掉的、或者别的词这次才该生效的）⇒ 重跑一遍 {@link #filter} 是全量幂等的；
     * 只替换新词会让这条命令的结果依赖「之前跑过什么」。
     *
     * <p>⚠️ 取数走 {@code LIKE} 只回**可能命中**的行（见 {@link CommentDao#selectContentsContaining}
     * 的转义 ⚠️），最终以 {@link #filter} 的判定为准 —— {@code LIKE} 的排序规则与
     * 本类的大小写不敏感匹配**不是同一个**实现，故这里是「SQL 粗筛 + Java 精判」，不是二选一。
     *
     * @return 真正被改写的行数（**不是**命中行数：原本就已打码的行不算）
     */
    public int maskOldComments(String word) {
        List<Map<String, Object>> rows = commentDao.selectContentsContaining(word);
        int changed = 0;
        for (Map<String, Object> row : rows) {
            Object idValue = row.get("Id");
            String content = str(row.get("Content"));
            if (!(idValue instanceof Number idNum) || isBlank(content)) {
                continue;
            }
            String masked = str(filter(content).get("masked"));
            if (masked == null || masked.equals(content)) {
                continue;
            }
            if (commentDao.updateContent(idNum.longValue(), masked) > 0) {
                changed++;
            }
        }
        return changed;
    }

    // ==================== 内部 ====================

    /**
     * 读全表词、按**长度倒序**排 —— 长词优先（类注释「不重复计数」那一节）。
     * 等长时按词面排，保证同一次输入下结果稳定（{@code sort} 是稳定排序，
     * 但 DB 的行序没有保证 ⇒ 这个兜底让输出可复算）。
     */
    private List<String> sortedWords() {
        List<String> words = new ArrayList<>(sensitiveWordDao.selectAllWords());
        words.removeIf(SensitiveWordService::isBlank);
        words.sort(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder()));
        return words;
    }

    /**
     * 从 {@code at} 起能不能匹配到一个词 —— 能就回那个词（调用方据此跳过整段）。
     *
     * <p>{@code regionMatches(true, …)}：**大小写不敏感**。词表全是中文（预置 10 条）时这条
     * 不影响任何结果；但它让「管理端加了个英文词 {@code VIP}」不必再纠结用户写 {@code vip}
     * 算不算 —— 过滤器的默认方向是「宁可多命中」。
     */
    private static String matchAt(String text, int at, List<String> words) {
        for (String w : words) {
            if (at + w.length() <= text.length() && text.regionMatches(true, at, w, 0, w.length())) {
                return w;
            }
        }
        return null;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 命中的词拼成一行事件字段（块 7 · commit 8）。去重已由调用方的 {@link LinkedHashSet} 做掉，
     *  这里只管**限长** —— 词表里可以有很长的条目，而事件流是给页面看的，不该被它撑爆一条记录。
     *  一个都没命中 ⇒ 回 {@code null}（事件里「没有命中的词」与「命中的词是空串」不该长得一样）。 */
    private static String joinWords(Set<String> hitWords) {
        if (hitWords.isEmpty()) {
            return null;
        }
        String joined = String.join(",", hitWords);
        return joined.length() <= MAX_WORDS_CHARS ? joined : joined.substring(0, MAX_WORDS_CHARS) + "…";
    }

    /** 命中词字段的长度上限（字符）。够看清「命中了什么」，又不至于让一条事件变成几百字。 */
    private static final int MAX_WORDS_CHARS = 200;

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
