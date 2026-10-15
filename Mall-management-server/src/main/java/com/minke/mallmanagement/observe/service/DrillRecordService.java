package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.observe.dao.DrillRecordDao;
import com.minke.mallmanagement.protect.ProtectionGate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演练记录服务（块 7 · commit 4）—— <b>跑一次演练 + 把那一行读回来</b>。三条端点各对应一个方法。
 *
 * <h2>【它做的四件事，以及它<b>不</b>做的事】</h2>
 * <ol>
 *   <li>{@link #scenarios()} —— 场景清单（8 条，硬编码在 {@link AttackEngine}）；</li>
 *   <li>{@link #run} —— 交给 {@link AttackEngine} 真跑，然后把结果落一行 {@code drill_record}；</li>
 *   <li>{@link #records} —— 读回来，并把 {@code Result} 那个**长文本 JSON 拆成字段**；</li>
 *   <li>{@link #stop} —— 叫停正在跑的那一轮（块 7 · commit 10 的 [暂停]；一层转发，见该方法的注释）。</li>
 * </ol>
 * ⚠️ <b>发流量、汇总、判定「拦下 / 失败」全在 {@link AttackEngine} 里</b>，本类不含任何请求逻辑
 * —— 分开的理由是前者可以纯单测（不碰库、不起 Spring），后者只做「编排 + 落库 + 反序列化」。
 *
 * <h2>【{@code Result} 为什么必须拆字段（方案 §1.5 逐字）】</h2>
 * 方案原文：「后端解析后分内容给前端用于显示。**不是为了展示原始长文本，是为了分字段显示**」。
 * ⇒ 列表端点**不回** {@code Result} 原文（不回 = 前端不可能顺手把它 dump 到页面上）。
 * 唯一保留原文的场合是**解析失败**：那时把原串挂在 {@code ResultRaw} 下并附 {@code ResultParseError}，
 * 让「记录坏了」这件事**看得见**（TD-20 的口径：不静默）。
 */
@Service
public class DrillRecordService {

    /** 列表默认 20 条（方案没定；同域其它列表用 100，但演练记录一行就带一段长 JSON ⇒ 取小）。 */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 200;

    private final DrillRecordDao drillRecordDao;
    private final AttackEngine attackEngine;
    private final ObjectMapper objectMapper;

    /**
     * 防护开关的运行时状态（D 段 · commit 2）。⚠️ 它是本类**唯一**的写点：
     * {@link #run} 开跑前写、收尾（{@code finally}）复位。理由见那里的注释与
     * {@code ProtectionGate} 类注释末节。
     */
    private final ProtectionGate gate;

    public DrillRecordService(DrillRecordDao drillRecordDao, AttackEngine attackEngine,
                              ObjectMapper objectMapper, ProtectionGate gate) {
        this.drillRecordDao = drillRecordDao;
        this.attackEngine = attackEngine;
        this.objectMapper = objectMapper;
        this.gate = gate;
    }

    // ==================== 面 1：场景清单 ====================

    /** 8 个预置场景（`POST /admin/observe/attack/scenarios`）。 */
    public Map<String, Object> scenarios() {
        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        ok.put("list", attackEngine.scenarios());
        return ok;
    }

    // ==================== 面 2：跑一轮 ====================

    /**
     * 跑一轮演练并落一行记录。
     *
     * <p>⚠️ <b>跑在落库之前</b>，且**不**开事务包住：一轮演练是数十秒的 HTTP 流量，
     * 拿一个数据库连接去陪跑整段时长是浪费（连接池会被这种「长事务」拖住）。
     *
     * <p>⚠️ <b>形参一律 {@code String}</b>（与端点逐字一致，见 {@code AttackController} 类注释第 2 节）：
     * 控制器把表单串原样递过来，**校验集中在 <b>一处</b></b> —— 数值走 {@link AttackEngine#parse} 的
     * {@code clampInt}（缺省 / 上下限 / 非整数），{@code scenarios} 这个 JSON **数组串**在本类先解成结构
     * （传输形状）再交给引擎（语义）。全部非法输入抛 {@link IllegalArgumentException} ⇒ 控制器转 400。
     *
     * @param name            场景名（空白 ⇒ 引擎给「自定义」）
     * @param concurrency     总并发数（空白 ⇒ 缺省；上下限见引擎常量）
     * @param durationSeconds 持续秒数（同上）
     * @param targetQps       目标 QPS（甲；空白 ⇒ {@code 0} = 不限速 = 加这个参数之前的行为）
     * @param scenarios       勾选与权重的 JSON 数组串，见 {@link #decodeScenarios}；空白 ⇒ 全 8 个场景
     * @param protections     防护开关的 JSON 数组串（丁）；空白 ⇒ 全开（面板缺省）
     * @param injections      注入开关的 JSON 数组串（丁）；空白 ⇒ 全关
     * @param operatorId      发起者业务号（**取会话**，认不出则 {@code ""}）
     * @param operatorRole    1/2/3（认不出则 {@code 0}）
     * @param baseUrl         打哪个地址（由 {@code AttackController} 从**当前请求**推出来，见那里的说明）
     */
    public Map<String, Object> run(String name, String concurrency, String durationSeconds,
                                   String targetQps, String scenarios, String protections,
                                   String injections, String operatorId, Integer operatorRole,
                                   String baseUrl) {
        // ⚠️ 汇成一个报文交给引擎：引擎的 `parse` 是**唯一的校验出口**（单测钉住的就是它），
        //    本类只负责把「表单串」翻译成它认的报文形状（数值保持字符串 —— clampInt 两种都吃，
        //    让它去说「必须是整数」那句话，而不是在 Spring 的参数绑定阶段抛一个形状不同的异常）。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("concurrency", concurrency);
        body.put("durationSeconds", durationSeconds);
        body.put("targetQps", targetQps);
        body.put("scenarios", decodeScenarios(scenarios));
        // 丁的两个开关与 `scenarios` **同一种传输形状**（JSON 数组串 ⇒ 结构）—— 理由同
        // decodeScenarios：自造分隔符要么错位、要么得再造一套转义规则。
        List<String> onProtections = decodeKeys(protections, "protections");
        body.put("protections", onProtections);
        body.put("injections", decodeKeys(injections, "injections"));

        // ⚠️ 这里**不**转发调用者的 Authorization / Cookie：引擎自己铸票，转发发起人（管理员）的凭据
        //    会让身份守卫走管理员豁免（场景 7 的 403 永远出不来）、转发 Cookie 更会让「故意不带票」
        //    的那一路被会话悄悄认成已登录。理由全文在 `AttackEngine` 类注释第二节。
        //
        // ── D 段（commit 2）：防护门在**引擎开跑之前**写入，在**这一轮收尾之后**复位 ──
        // ⚠️ 顺序不能反：闸读的是 `ProtectionGate` 的运行时位，而这一轮的第一发请求在
        //    `attackEngine.run` 内部就发出去了 —— 写入放在 run 之后等于本轮全程读的是**上一轮的**开关。
        // ⚠️ 缺省（`protections` 没给 ⇒ 解码得 null）在这一层必须翻成「全开」：面板缺省就是全开，
        //    而 `apply(null)` 的语义是「全关」（它按「勾了哪些」写，null = 一个都没勾）。
        //    两个 null 含义相反，这里是**唯一**的换算点；不换算的后果是「不传这个参数 ⇒ 把防护全关了」，
        //    恰是最坏的那种默认值。
        // ⚠️ 复位必须放 `finally`：`run` 会抛（参数非法、下游全挂、被叫停……），
        //    不复位就等于「上一轮把限流关掉」越过那一轮留在进程里（逐字理由见 ProtectionGate 类注释）。
        AttackEngine.DrillRun run;
        gate.apply(onProtections == null ? ProtectionGate.KEYS : onProtections);
        try {
            run = attackEngine.run(body, baseUrl);
        } finally {
            gate.resetToDefault();
        }

        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        ok.put("Id", drillRecordDao.insert(operatorId, operatorRole, run.name(), run.scenarioJson(),
                run.total(), run.blocked(), run.failed(), run.durationMs(), toJson(run.result()),
                run.batchId()));   // 块 7 · commit 8c：这一轮的批次（与 observed_event.BatchId 同源）
        ok.put("Name", run.name());
        ok.put("TotalRequests", run.total());
        ok.put("BlockedRequests", run.blocked());
        ok.put("FailedRequests", run.failed());
        // 甲的两个数（背压弃投 / 取消）**单列**：它们不并进上面三档（理由见 AttackEngine.Meter）
        ok.put("ShedRequests", run.shed());
        ok.put("AbandonedRequests", run.abandoned());
        ok.put("DurationMs", run.durationMs());
        // ⚠️ 这一份是**刚跑完的那一轮**的字段化结果（不是从库里读回来的）⇒ 前端跑完即可直接入表，
        //    不必再拉一次列表。「记录里那一行」与「这里这一份」同源（run.result()）。
        ok.put("Result", run.result());
        return ok;
    }

    // ==================== 面 4：叫停（块 7 · commit 10）====================

    /**
     * 叫停正在跑的那一轮（{@code POST /admin/observe/attack/stop}）。
     *
     * <p>⚠️ <b>为什么本笔必须新增这个出口</b>（用户 2026-10-14 实测踩到）：{@code /run} 是
     * <b>同步跑到结束</b>的（几十秒到几分钟），而在它之前全仓**没有任何停止手段** ——
     * 上一轮探针崩了但 {@code /run} 已经服务端起飞，只能干等到它自己跑完。
     *
     * <p>⚠️ 语义是「**停止派发 + 排空在途**」，不是「立即掐断」：在途的请求走
     * {@code AttackEngine} 的宽限排空（{@code GRACE_SECONDS}），宽限用尽才取消并记
     * {@code Abandoned}。理由与旧代码同源 —— 硬掐已经真发出去的请求会记成假失败。
     *
     * <p>⚠️ 本类只是**转发**（一层薄壳）：真正的令旗在 {@code AttackEngine} 里。放在这里而不是
     * 让 {@code AttackController} 直接注 {@code AttackEngine}，是为了不给控制器添第四个构造依赖
     * （本仓在那处登记过「第十九次」地雷：控制器多一个依赖，{@code AbstractSmokeTest} 的
     * {@code @Import} 就得跟着加）。
     *
     * @return {@code true} = 确实叫停了一轮正在跑的；{@code false} = 当时没有在跑的轮次
     *         （跑完了 / 从没跑过）。⚠️ 这个布尔**如实回给前端**：它决定按钮该不该提示
     *         「没有在跑的演练」，不编造成一个 success:true 了事。
     */
    public boolean stop() {
        return attackEngine.stop();
    }

    // ==================== 面 5：实时进度（块 7 收尾）====================

    /**
     * 正在跑的那一轮的实时读数（{@code POST /admin/observe/attack/status}）。
     *
     * <p>⚠️ 与 {@link #stop} 同一条理由：本类只是**转发**（一层薄壳），真正读的是
     * {@code AttackEngine.progress()} —— 不给 {@code AttackController} 添第四个构造依赖
     * （本仓在那处登记过「第十九次」地雷，控制器每多一个依赖，{@code AbstractSmokeTest}
     * 的 {@code @Import} 就得跟着加）。
     *
     * <p>⚠️ <b>为什么这个数必须由后端给</b>：{@code /run} 是**同步跑到结束**的，页面在它返回
     * 之前拿不到任何后端读数 —— 旧版那条进度条因此只能按前端墙钟估（页面注释自己写着
     * 「没有后端进度可查……装作知道就是编数据」）。本出口把「已发多少条 / 拦下多少条」
     * 变成**真实读数**，前端不再需要估。
     *
     * @return 永不为 {@code null}；没有在跑时 {@code running=false} + 全零（见
     *         {@code AttackEngine.progress()} 的三条边界）
     */
    public AttackEngine.Progress status() {
        return attackEngine.progress();
    }

    // ==================== 面 3：记录列表（拆字段） ====================

    /**
     * 演练记录列表（时间倒序）。{@code limit} 缺省 {@value #DEFAULT_LIMIT}、上限 {@value #MAX_LIMIT}。
     *
     * <p>每一行的 {@code Result} 长文本在**这里**被解析成对象（{@code Result} 键：解析成功 = 对象、
     * 失败 = 原串 {@code null} 并把原串放 {@code ResultRaw}）—— 前端拿到的永远是分字段的形状。
     *
     * <p>⚠️ 两个数值参数收 {@code String}（同 {@link #run}）：<b>空白用缺省、非整数报 400、
     * 数值一律收进区间</b>（{@code 0} / 负数当「没给」看 —— 分页控件不会发这种值，但 curl 会）。
     */
    public Map<String, Object> records(String limit, String offset) {
        int wanted = intOr(limit, 0, "limit");
        int effectiveLimit = wanted <= 0 ? DEFAULT_LIMIT : Math.min(wanted, MAX_LIMIT);
        int effectiveOffset = Math.max(0, intOr(offset, 0, "offset"));

        List<Map<String, Object>> rows = drillRecordDao.selectRecords(effectiveLimit, effectiveOffset);
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(split(row));
        }

        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        ok.put("list", list);
        ok.put("total", drillRecordDao.countRecords());
        ok.put("limit", effectiveLimit);
        ok.put("offset", effectiveOffset);
        return ok;
    }

    // ==================== 内部 ====================

    /**
     * {@code scenarios} 参数（**JSON 数组串**）→ 结构。空白 ⇒ {@code null}（= 引擎按全 8 个场景跑）。
     *
     * <p>⚠️ 为什么用 JSON 数组串而不是 {@code key:权重,key:权重} 这种自造分隔符串：同
     * {@code OrderService.parseExplicitItems} 那条论证 —— 自造分隔符要么错位、要么得再造一套转义
     * 规则，而错位的结果是**跑错场景**（读着一份数字演示另一件事，比报错坏得多）。JSON 的转义是现成的，
     * Jackson 又是本类已有的注入式 bean。
     *
     * <p>⚠️ <b>这里与 {@code OrderService} 的取舍**不同**</b>：那边 {@code Items} 解析失败 ⇒ 记 WARN +
     * 回落（订单照常落库），因为落单是主业务；这边解析失败 ⇒ <b>直接抛</b>（400）。理由：本端点的
     * **全部语义**就是「按勾选的场景跑」，回落到「全 8 个场景」等于**替调用者做了另一个决定**，
     * 而页面上会显示一串他没勾的场景 —— 那不是降级，是失真。
     */
    private List<Map<String, Object>> decodeScenarios(String scenarios) {
        if (scenarios == null || scenarios.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(scenarios, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            // 把正确形状写进消息里：这是可以被 curl 直接打的一条端点，报错要能照着改。
            throw new IllegalArgumentException(
                    "scenarios 必须是 JSON 数组，如 [{\"key\":\"seckill\",\"weight\":80}]");
        }
    }

    /**
     * 开关参数（**JSON 数组串**）→ 字符串列表。空白 ⇒ {@code null}（= 没给 ⇒ 引擎用缺省）。
     *
     * <p>⚠️ 与 {@link #decodeScenarios} 同一条口径：解析失败**直接抛**（400），不回落。
     * 回落成「全部开关按缺省」等于替调用者做了另一个决定，而结果页上会显示一套他没勾的开关
     * —— 那不是降级，是失真（同 {@code OrderService.parseExplicitItems} 那边的取舍差异）。
     *
     * <p>⚠️ 「没给」与「给了 {@code []}」是**两件事**：前者是缺省（防护全开 / 注入全关），
     * 后者是「这一轮一个都不开」这个有意义的状态。空串按「没给」处理（表单控件不发空值）。
     */
    private List<String> decodeKeys(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    what + " 必须是 JSON 字符串数组，如 [\"redisDown\"]");
        }
    }

    /**
     * 字符串参数 → 整数。空白 ⇒ {@code fallback}（= 没给）；**不是整数 ⇒ 抛**（控制器转 400）。
     *
     * <p>⚠️ 数值范围**由调用方收**（本方法不管上下限）：这两个参数的口径是「收进区间」而不是
     * 「越界即拒」—— 分页参数越界回一页正常数据，比回一个 400 有用。
     */
    private static int intOr(String raw, int fallback, String what) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + "必须是整数，收到：" + raw.trim());
        }
    }

    /**
     * 一行 → 前端形状：{@code Result} 拆成对象、{@code Scenario} 也拆（它是配置 JSON，不是给人读的串）。
     *
     * <p>⚠️ 用 {@link LinkedHashMap} 保序：JSON 文本是按 `列清单` 的顺序读出来的，
     * 打散成 {@code HashMap} 会让同一行的字段顺序在不同次请求里漂 —— 表格列顺序靠它稳定。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> split(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if ("Result".equals(e.getKey())) {
                continue;
            }
            if ("Scenario".equals(e.getKey())) {
                out.put("Scenario", parseOrRaw(e.getValue(), out, "Scenario"));
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        out.put("Result", parseOrRaw(row.get("Result"), out, "Result"));
        return out;
    }

    /**
     * 解析一个 JSON 列：成功 ⇒ 对象 / 数组；失败 ⇒ {@code null} + 原串挂 {@code <key>Raw} + 标错。
     *
     * <p>⚠️ 「解析失败」不吞：{@code <key>ParseError} 让前端能把那一行画成「记录损坏」，
     * 而不是画成一个没有结果的成功演练（TD-20）。
     */
    private Object parseOrRaw(Object value, Map<String, Object> out, String key) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Map.class);
        } catch (Exception e) {
            out.put(key + "Raw", text);
            out.put(key + "ParseError", e.getMessage());
            return null;
        }
    }

    /**
     * 结构化结果 → JSON 文本。
     *
     * <p>⚠️ 序列化失败**不放弃这一行**：演练真的跑过了（流量的后果已经落在库里 / 指标上），
     * 因为「结果写成文本」这一步失败就把整条记录丢掉，等于把发生过的事删掉 —— 那比记一条
     * 残缺记录坏得多。故失败时落一个**带 {@code error} 的**合法 JSON，让那一行仍然可读、可追。
     */
    private String toJson(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace('"', '\'') + "\"}";
        }
    }
}
