package com.minke.mallmanagement.shared.web;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一响应体 —— **顶层平铺**的 {@code {"success": <bool>, "msg": <string>, <载荷键>: …}}。
 *
 * <h2>【为什么是「顶层平铺」而不是 {@code {code, message, data}}】</h2>
 * 全项目 33 个端点里 30 个返回 {@code Map<String,Object>}，前端按**顶层键**取值：
 * {@code data.success}（34 处真实判定）、{@code data.msg}（17 处）、以及
 * {@code list} / {@code price} / {@code goodImg} / {@code user} / {@code imageUrl} 等。
 * 一旦把这些键挪进 {@code data.*}，前端的 {@code data.list} 会变 {@code undefined} ——
 * 20+ 个调用点静默失效。故本类**不做嵌套**，只把「响应体长什么样」这件事收到一处。
 * 逐条证据见 {@code docs/agent/progress.md §6-A}（P2.4 / P1.3 / P1.4）。
 *
 * <h2>【为什么继承 {@code HashMap} 而不是 {@code LinkedHashMap}】</h2>
 * 这是**有意的**，别改。今日 33 处响应 Map 全是 {@code new HashMap<>()}
 * （唯一例外 {@code HealthController.db} 用 {@code LinkedHashMap}，见下方例外 5）。
 * {@code HashMap} 的键序由 hash 桶决定（与插入序无关），{@code LinkedHashMap} 则是插入序 ——
 * 两者**不同**，实测：同内容分别装入两者，8 组代表形状里 4 组的序列化字节不同。
 * <p><b>选 {@code HashMap} 的收益是「同类 + 不变量最少」，而不是「字节相同全靠它」</b> —— 这两件事
 * 别混。分两层说清楚：
 * <ol>
 *   <li><b>同类</b>：今日响应体就是 {@code HashMap}。同类容器意味着「直接往 {@code Result} 里
 *       {@code put} 几个键」的行为与遗留一致（桶序），不会因为换了容器类型而悄悄变序。</li>
 *   <li><b>但字节相同不靠基类</b>：{@link #of(Map)} 是**按源的迭代序逐条 {@code put}** 的，
 *       所以哪怕基类换成 {@code LinkedHashMap}，只要走 {@code of()}，字节**同样**相同。
 *       真正的字节风险不在基类，而在**包装方式** —— {@code putAll} 的预置容量会把桶数从 16
 *       改成 4，实测当场打破逐字节相同（详见 {@link #of(Map)}）。</li>
 * </ol>
 * <p>⚠️ 别把下面这句当反例：直接序列化「同内容、同插入序」的 {@code LinkedHashMap}，与
 * {@code HashMap} 确实不同（实测 8 组代表形状里 4 组字节不同）。但那说的是**绕过 {@code of()}
 * 直接构造**的情形，不是本类的包装路径。**逐字节相同这件事由
 * {@code ResultByteEquivalenceTest} 兜底，且它已实证抓过一次真缺陷**（{@code putAll} 那次）。
 *
 * <h2>【不做什么】</h2>
 * <ul>
 *   <li><b>不用 {@code ResponseBodyAdvice}</b> —— 它会无差别包住所有响应，直接打断下面 5 个
 *       例外端点（尤其 {@code A-S1-3} 的逐字断言与前端登录链）。</li>
 *   <li><b>不改 Service 层</b> —— Service 返回 {@code Map} 照旧；本类是 Controller 这一层的
 *       声明（「Controller 只做 HTTP 适配」）。包装用 {@link #of(Map)}。</li>
 * </ul>
 *
 * <h2>【例外清单 —— 这 5 个端点**不用**本类，签名一字不动】</h2>
 * <ol>
 *   <li><b>{@code UserController.loginSelect}</b>（{@code int}）—— A3 {@code A-S1-3} 断言
 *       {@code "1".equals(body.trim())} 逐字；前端 {@code res.text()} 后比 {@code "0"/"1"/"2"}。
 *       包成 JSON ⇒ 门禁红 + 前端断。</li>
 *   <li><b>{@code UserController.loginAdd}</b>（{@code String}）—— 前端 {@code text === "添加成功！"}
 *       / {@code === "0"} 逐字；A3 断言 {@code body.contains("添加成功")}。</li>
 *   <li><b>{@code UserController.getUserInfo}</b>（{@code Map} 失败为 {@code null}）—— 失败时是
 *       HTTP 200 **空体**；三个前端调用点 {@code await res.json()} **无判空**，靠外层 {@code catch}
 *       兜 {@code SyntaxError}。包成可解析 JSON ⇒ 前端静默不写 sessionStorage（登录链断）。</li>
 *   <li><b>{@code GoodsController.goodstype}</b>（{@code Object}）—— GET/POST **双形态**：
 *       {@code GoodType == null} → {@code {success,list}}；否则 → {@code {success,msg}}。</li>
 *   <li><b>{@code HealthController.db}</b>（{@code Map}，今日是 {@code LinkedHashMap}）—— 唯一一处
 *       插入序响应；换成 {@code HashMap} 基类会改它的字节，而 A3 的 P5/P6/P7 读它 ⇒ 留例外。</li>
 * </ol>
 *
 * @param <T> 载荷类型占位（本类顶层平铺，{@code T} 不参与序列化；Controller 声明为
 *            {@code Result<Map<String, Object>>} 即可）
 */
public class Result<T> extends HashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    /** 空体。仅在调用方要自行 {@code put} 时使用（正常路径请用 {@link #of}）。 */
    public Result() {
        super();
    }

    /**
     * 逐键搬运 {@code src} 的全部键值。
     *
     * <p>⚠️ <b>必须用逐条 {@code put}，不能用 {@code putAll}</b> —— 这条实测踩过，
     * 别改回去。理由：{@code HashMap.putAll} 走 {@code putMapEntries}，它有一句
     * 「目标表为空则按 {@code size / 0.75 + 1} 预置容量」的优化；对 2 个键算出
     * {@code tableSizeFor(3) = 4}，于是容量是 <b>4</b>。而今日 33 处响应 Map 是
     * {@code new HashMap<>()} 后逐个 {@code put}，首次 put 触发 resize 得容量 <b>16</b>。
     * 容量不同 ⇒ 桶数不同 ⇒ {@code keySet()} 迭代序不同 ⇒ <b>序列化字节就不同</b>。
     * 实测：{@code {success, list}} 经 {@code putAll} 后输出
     * {@code {"list":…,"success":…}}，而改动前是 {@code {"success":…,"list":…}} —— 判据 (a) 当场红。
     * （序列化器两侧都是同一个 {@code MapSerializer}，差异全在 Map 内部，与 Jackson 无关。）
     *
     * <p>逐条 {@code put} 复刻的是「{@code new HashMap<>()} 后按同样次数 put」的扩容路径，
     * 故容量、桶数、迭代序、字节全一致（回归闸见 {@code ResultByteEquivalenceTest}）。
     *
     * <p>前提：{@code src} 是 {@code new HashMap<>()}（无初值容量）且只经 {@code put} 填充、
     * 未删过键。今日 33 处响应 Map 全部满足（已 grep 核实：无 {@code new HashMap<>(n)}、
     * 响应 Map 上无 {@code putAll}／{@code remove}——唯一的 {@code putAll} 在
     * {@code HealthController.db}，而那正是本类的<b>例外 5</b>，不走这里）。
     * 该前提若破，回归闸会红。
     */
    public static <T> Result<T> of(Map<String, Object> src) {
        Result<T> r = new Result<>();
        copyInto(r, src);
        return r;
    }

    /** 逐条 {@code put} 搬运 —— 见 {@link #of(Map)} 里为什么不能用 {@code putAll}。 */
    private static void copyInto(Map<String, Object> dst, Map<String, Object> src) {
        if (src == null) {
            return;
        }
        for (Map.Entry<String, Object> e : src.entrySet()) {
            dst.put(e.getKey(), e.getValue());
        }
    }

    /** {@code {"success": true}}。 */
    public static <T> Result<T> ok() {
        Result<T> r = new Result<>();
        r.put("success", true);
        return r;
    }

    /** {@code {"success": true, <载荷键>: …}}。 */
    public static <T> Result<T> ok(Map<String, Object> payload) {
        Result<T> r = new Result<>();
        r.put("success", true);
        copyInto(r, payload);
        return r;
    }

    /** {@code {"success": false, "msg": <msg>}}。 */
    public static <T> Result<T> fail(String msg) {
        Result<T> r = new Result<>();
        r.put("success", false);
        r.put("msg", msg);
        return r;
    }
}
