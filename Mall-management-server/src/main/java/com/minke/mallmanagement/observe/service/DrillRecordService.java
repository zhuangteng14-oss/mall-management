package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.observe.dao.DrillRecordDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演练记录服务（块 7 · commit 4）—— <b>跑一次演练 + 把那一行读回来</b>。三条端点各对应一个方法。
 *
 * <h2>【它做的三件事，以及它<b>不</b>做的事】</h2>
 * <ol>
 *   <li>{@link #scenarios()} —— 场景清单（8 条，硬编码在 {@link AttackEngine}）；</li>
 *   <li>{@link #run} —— 交给 {@link AttackEngine} 真跑，然后把结果落一行 {@code drill_record}；</li>
 *   <li>{@link #records} —— 读回来，并把 {@code Result} 那个**长文本 JSON 拆成字段**。</li>
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

    public DrillRecordService(DrillRecordDao drillRecordDao, AttackEngine attackEngine,
                              ObjectMapper objectMapper) {
        this.drillRecordDao = drillRecordDao;
        this.attackEngine = attackEngine;
        this.objectMapper = objectMapper;
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
     * @param scenarios       勾选与权重的 JSON 数组串，见 {@link #decodeScenarios}；空白 ⇒ 全 8 个场景
     * @param operatorId      发起者业务号（**取会话**，认不出则 {@code ""}）
     * @param operatorRole    1/2/3（认不出则 {@code 0}）
     * @param baseUrl         打哪个地址（由 {@code AttackController} 从**当前请求**推出来，见那里的说明）
     */
    public Map<String, Object> run(String name, String concurrency, String durationSeconds,
                                   String scenarios, String operatorId, Integer operatorRole,
                                   String baseUrl) {
        // ⚠️ 汇成一个报文交给引擎：引擎的 `parse` 是**唯一的校验出口**（单测钉住的就是它），
        //    本类只负责把「表单串」翻译成它认的报文形状（数值保持字符串 —— clampInt 两种都吃，
        //    让它去说「必须是整数」那句话，而不是在 Spring 的参数绑定阶段抛一个形状不同的异常）。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("concurrency", concurrency);
        body.put("durationSeconds", durationSeconds);
        body.put("scenarios", decodeScenarios(scenarios));

        // ⚠️ 这里**不**转发调用者的 Authorization / Cookie：引擎自己铸票，转发发起人（管理员）的凭据
        //    会让身份守卫走管理员豁免（场景 7 的 403 永远出不来）、转发 Cookie 更会让「故意不带票」
        //    的那一路被会话悄悄认成已登录。理由全文在 `AttackEngine` 类注释第二节。
        AttackEngine.DrillRun run = attackEngine.run(body, baseUrl);

        Map<String, Object> ok = new HashMap<>();
        ok.put("success", true);
        ok.put("Id", drillRecordDao.insert(operatorId, operatorRole, run.name(), run.scenarioJson(),
                run.total(), run.blocked(), run.failed(), run.durationMs(), toJson(run.result())));
        ok.put("Name", run.name());
        ok.put("TotalRequests", run.total());
        ok.put("BlockedRequests", run.blocked());
        ok.put("FailedRequests", run.failed());
        ok.put("DurationMs", run.durationMs());
        // ⚠️ 这一份是**刚跑完的那一轮**的字段化结果（不是从库里读回来的）⇒ 前端跑完即可直接入表，
        //    不必再拉一次列表。「记录里那一行」与「这里这一份」同源（run.result()）。
        ok.put("Result", run.result());
        return ok;
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
