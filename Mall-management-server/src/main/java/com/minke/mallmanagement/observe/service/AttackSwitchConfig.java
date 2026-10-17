package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.protect.ProtectionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 攻击演练的**开关配置**（乙案，3a.1）—— 防护 5 键 + 注入 3 键，**进程内一份**，
 * 攻击模拟器页与实时日志页**共读共写**。
 *
 * <h2>【乙案 vs 甲案：开关从「入参」变成「配置」】</h2>
 * 甲案（块 7 收尾 ~ 阶段 2b）把这两个开关挂在 {@code /attack/run} 的**入参**上
 * （{@code protections} / {@code injections} 两个 JSON 数组串）。它的代价是：开关**只存在于
 * 一次请求里** —— 另一个页面（实时日志页）既看不到、也改不了；想把某一轮改一个开关，只能
 * 重发一次 {@code /run}（而那会**再跑一轮**演练）。
 * <p>乙案把它们提到**一个独立的配置面**（{@code POST /admin/observe/attack/config} +
 * {@code GET} 同路径）：两个页面读的是同一份、写的是同一份，日志页在演练**跑的过程中**也能改
 * （改动对本轮**立即生效**，见下面「实时读」一节）。{@code /attack/run} 不再收这两个参数。
 * <p>⚠️ 这一条**推翻了 D 段那份收笔报告里的一句旧话**（{@code reports/块7-D段-死锁侦查与补报-报告.txt}
 * 的「{@code /attack/config} 端点做了吗」一栏当时答的是「**不存在，也不该存在** —— 它是被否的
 * 乙案」）。旧话在写下时是真的（那一轮裁定确实是甲案），本笔起它**不再成立**；改动登记见本笔
 * 收笔报告与 {@code tech-debt} 的口径表。留着这句是为了让后来的人**查得到翻转**，而不是
 * 以为两份文件里有一份写错了。
 *
 * <h2>【「一份」是怎么落成的：防护的位不复制】</h2>
 * 防护 5 键的真值**只有一处** —— {@link ProtectionGate} 里那五个 {@code AtomicBoolean}。
 * 本类**不**另存一份 protections 的副本，而是**代理**它（写走 {@link ProtectionGate#apply}、
 * 读走 {@link ProtectionGate#isOn}）。理由：那些位是在**热路径**上被读的
 * （入站限流闸 / 熔断 / 分池 / 重试），而热路径读的必须是真值 —— 一旦这里存第二份，就会出现
 * 「配置面显示已关、闸却还在拦」这种**两边都自认为对**的分歧，且没有任何地方会报错。
 * ⇒ 注入 3 键的位在本类（没有热路径读它们），防护 5 键的位在 {@code ProtectionGate}；
 * **两者合起来就是「那一份配置」**，{@link #state()} 是它的唯一快照出口。
 *
 * <h2>【⚠️ 演练跑完<b>不复位</b>（用户 2026-10-16 裁定），代价与兜底】</h2>
 * 阶段 2b 之前的口径是「演练收尾必须复位成全开」，理由逐字写在 {@code ProtectionGate} 类注释
 * 末节：不复位就等于「上一轮把限流关掉」**越过那一轮**留在进程里，而页面上看不到任何迹象。
 * <p>乙案把这一条**改掉了**：配置是**共享的、长期的**（这正是「两个页面共读共写」的前提），
 * 所以跑完**不复位**。⇒ 上面那条风险必须由**别的东西**接住，本类是它的一半：
 * <ol>
 *   <li>{@link State#isDefault()} / {@code GET /config} 的 {@code IsDefault} —— 让「当前不是
 *       默认配置」成为一个**可读的状态**，而不是一个推论；</li>
 *   <li>两个页面据此**常驻提示**「⚠️ 当前非默认配置：真实流量不受这些防护保护」+ 一键
 *       「恢复默认」（{@link #resetToDefault()}）—— 前端落地在 3a.6；</li>
 *   <li>{@link #update} 在配置**非默认**时额外记一行 WARN（默认时不记，避免正常演示刷屏）。</li>
 * </ol>
 * ⚠️ 兜底方案的**边界如实写在这里**：它把「静默」变成了「可见」，但**不阻止**有人把开关拨成
 * 非默认之后就不管了 —— 重启进程是唯一的自动恢复点（{@code 重启回默认} 是这条裁定的另一半）。
 *
 * <h2>【实时读：改动对<b>正在跑</b>的那一轮立即生效】</h2>
 * 两个开关的读点都在 {@link AttackEngine}：
 * <ul>
 *   <li>防护 —— 走 {@code ProtectionGate} 的位，而闸是**每一发请求都读一次**的 ⇒ 天然实时；</li>
 *   <li>注入 —— 在「阶段 B 跑不跑」那个判据处读（阶段 A 跑完之后）⇒ 阶段 A 期间拨动
 *       {@code redisDown} 会改变本轮阶段 B 是否注入。</li>
 * </ul>
 * ⚠️ 而演练结果里记的 {@code Protections} / {@code Injections} 是**起跑那一刻**的快照
 * （{@code AttackEngine.parse} 里取，说明也写在那里）—— 「这一轮开跑时是什么配置」与
 * 「这一轮实际读到了什么」是两件事，别把前者当成后者的证据。
 *
 * <h2>【写入语义：{@code null} = 不动它，{@code []} = 关光】</h2>
 * {@link #update} 的两个入参都可以是 {@code null}：{@code null} 表示「这次不改这一项」
 * （日志页只拨一枚防护开关时，不必把注入那一行也发过来）。而**空清单是一个有意义的状态**：
 * 「这一轮一个防护都不开」是演示要用的对照轮 —— 与「没给」必须分开（甲案那条口径逐字搬过来）。
 * ⚠️ 表单控件不发空值，故 {@link #updateFromJson} 把空白串按「没给」处理（同 {@code decodeScenarios}）。
 *
 * <h2>【不持久化】</h2>
 * 一份内存状态，不落库、不写配置文件。⇒ **重启回默认**（{@code KEYS} 全开 + 注入全关），
 * 这也是「拨完忘了拨回来」的最终恢复手段。证据链上它的代价是：审计/演练记录里查不到
 * 「某一刻的配置」这件历史事实 —— 记录里只有**每一轮起跑那一刻**的快照
 * （{@code drill_record.Result.Engine.Protections}），轮与轮之间的拨动不留痕。
 *
 * <h2>【调用点不变量 —— 台账条目（用户 2026-10-16 裁定「(b) 静态不变量 —— 写进台账」）】</h2>
 * 上面那条「跑完不复位」的口径，在测试里只被守住了两半的一半：{@code AttackSwitchConfigTest}
 * 守的是「配置面**自己不**自动复位」（**自我属性**），{@code smoke/AttackConfigNoResetSmokeTest}
 * 守的是「跑一轮之后**逐字不变**」（**流程属性**）。两半都盖不到的第三种失效是
 * **「多了一个写回调用点」**（例如将来给日志页加个定时复位、或在某条冷路径上顺手写回）——
 * 它未必让任何一条现有用例变红，所以只能靠一条**可 grep 的静态不变量**兜：
 * <ul>
 *   <li>{@link #updateFromJson} 的调用点 —— **只允许**
 *       {@code com.minke.mallmanagement.observe.controller.AttackController}
 *       （{@code POST /admin/observe/attack/config}）；</li>
 *   <li>{@link #update} 的调用点 —— {@code main} 里应**为 0**（现在只被测试直调）；</li>
 *   <li>{@link #resetToDefault()} 的调用点 —— {@code main} 里应**为 0**
 *       （本方法**留着**，调用者是 3a.6 的「恢复默认」按钮，尚未接线）；</li>
 *   <li>{@link ProtectionGate#resetToDefault()} 的调用点 —— **只允许本类**（代理，不是第二份真值）。</li>
 * </ul>
 * ⚠️ 它钉的是**调用点数量**、不是行为（所以它是台账条目、不是测试）。复核命令与逐行对账见
 * {@code docs/agent/api-contract.md} §I.3 末「调用点不变量 · 台账条目」。
 */
@Component
public class AttackSwitchConfig {

    private static final Logger log = LoggerFactory.getLogger(AttackSwitchConfig.class);

    /**
     * {@code /attack/run} 的旧入参被拒时的**唯一**一句话（引擎的报文校验与控制器共用这一份文案）。
     *
     * <p>⚠️ 要求是「拒收**并指路**」，不是静默忽略：静默忽略会让「我拨了开关、怎么没反应」成为一个
     * 查不出来的现象 —— 调用方以为改了这一轮的开关，而实际改的是一个没人读的字符串。
     */
    public static final String PARAMS_MOVED =
            "protections / injections 已改为共享配置，不再作为 /attack/run 的入参 —— "
                    + "请改用 POST /admin/observe/attack/config 修改（GET 同路径读当前配置）";

    /**
     * 注入键的**声明序**。⚠️ 与 {@link AttackEngine#INJECTION_KEYS} **同一份清单**
     * （刻意引用它而不是各写一份：两份清单一旦分叉，「配置面认得的键」与「引擎跑的键」就会
     * 不一致，而那种不一致的表现是「拨了没反应」—— 本仓最不想留的那一类）。
     * 前端的 {@code observe-labels.js} 是**第三份**，跨语言、Java 侧读不到，由探针钉住。
     */
    static final List<String> INJECTION_KEYS = AttackEngine.INJECTION_KEYS;

    // 防护键的声明序 = ProtectionGate.KEYS（不另写一份，理由同上）。⚠️ 本类**不持有**它们的位，
    // 只代理它（写走 apply / 读走 isOn）—— 见类注释第二节目。

    private final ProtectionGate gate;
    private final ObjectMapper objectMapper;

    /** 注入开关的位（本类自己的那一半；{@code volatile} 让派发线程里的读点看得到写入）。 */
    private volatile List<String> injections = List.of();

    /**
     * 单调的「第几次写入」。⚠️ 它**不是**版本号语义的审计线索（不落库、重启归零），
     * 用途只有一个：让同一次演示里「这份配置和我上次看到的是不是同一份」**可比**——
     * 页面轮询 {@code GET /config} 时靠它决定要不要重绘，而不必逐字段比。
     */
    private final AtomicLong version = new AtomicLong();

    public AttackSwitchConfig(ProtectionGate gate, ObjectMapper objectMapper) {
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    // ==================== 读 ====================

    /** 当前的**开着的**防护键（{@link ProtectionGate#KEYS} 声明序）。 */
    public List<String> protections() {
        List<String> on = new ArrayList<>();
        for (String key : ProtectionGate.KEYS) {
            if (gate.isOn(key)) {
                on.add(key);
            }
        }
        return on;
    }

    /** 当前的**开着的**注入键（声明序）；{@code volatile} 字段的读，返回不可变快照。 */
    public List<String> injections() {
        return injections;
    }

    /**
     * 当前配置的完整快照（两个页面 + 报告 + 测试的唯一读出口）。
     *
     * @return {@link State}：{@code protections} / {@code injections} 都只列**开着的**键
     *         （与甲案入参同形 —— 「缺省 = 全开」这种事在这里已经落成具体清单，读的人不必再知道
     *         缺省规则）；{@code isDefault} 是那两个清单与默认值逐项相等；{@code version} 见字段注释
     */
    public State state() {
        List<String> on = protections();
        List<String> inj = injections;
        boolean isDefault = inj.isEmpty() && on.size() == ProtectionGate.KEYS.size();
        return new State(on, inj, isDefault, version.get());
    }

    /**
     * 键目录：**每个键认不认得、有没有真闸** —— 给前端与探针用。
     *
     * <p>⚠️ {@code Wired=false} 的键（防护里的 {@code degrade}、注入里的 {@code mqDown} /
     * {@code dbSlow}）在面板上**必须画成不可开关**（标灰 + tooltip 说明它是记录位），
     * 而不是画成一个能拨却什么也不发生的死控件（用户 2026-10-15 裁定逐字；实现在
     * {@link ProtectionGate#wired(String)} 与 {@link AttackEngine#INJECTIONS_NOT_WIRED}）。
     */
    public Map<String, Object> catalog() {
        List<Map<String, Object>> protections = new ArrayList<>();
        for (String key : ProtectionGate.KEYS) {
            protections.add(one(key, ProtectionGate.wired(key)));
        }
        List<Map<String, Object>> injections = new ArrayList<>();
        for (String key : INJECTION_KEYS) {
            injections.add(one(key, !AttackEngine.INJECTIONS_NOT_WIRED.contains(key)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Protections", protections);
        out.put("Injections", injections);
        return out;
    }

    private static Map<String, Object> one(String key, boolean wired) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Key", key);
        m.put("Wired", wired);
        return m;
    }

    // ==================== 写 ====================

    /**
     * 传输层：两个 **JSON 数组串**（{@code @RequestParam} 的形状，同 {@code scenarios}）→
     * 结构 → 交给 {@link #update}。空白串 = **没给**（= 不动这一项，见类注释「写入语义」）。
     *
     * <p>⚠️ 解析失败**直接抛**（控制器转 400），**不**回落到「不动它」：回落等于替调用者做了
     * 另一个决定 —— 他明明想关掉某几个防护，而页面/日志上显示的是他没关（同 {@code decodeScenarios}
     * 那条取舍差异）。
     *
     * <p>⚠️ 解码在本类而不在控制器：与本仓其余 JSON 数组串入参**同一条分层**（传输形状在服务层解、
     * 语义校验在唯一的那个出口），{@code DrillRecordService.decodeScenarios} 就是那么摆的。
     */
    public State updateFromJson(String protectionsJson, String injectionsJson) {
        return update(decodeKeys(protectionsJson, "protections"),
                decodeKeys(injectionsJson, "injections"));
    }

    /**
     * 局部或整体写入。两个入参都可以是 {@code null}（= 这次不改这一项），
     * 非 {@code null} 则**整体替换**该项（{@code []} = 关光，见类注释）。
     *
     * @throws IllegalArgumentException 出现不认识的键（控制器转 400）。⚠️ 校验在**写入之前**
     *         全部走完：反向写（边校验边 set）会留下**半套配置**——五个位照样各有一个值，
     *         而页面上、日志里都看不出「这次写入只落了一半」（同 {@link ProtectionGate#apply}
     *         那条注释）。
     */
    public State update(List<String> protections, List<String> injections) {
        String problem = null;
        if (protections != null) {
            for (String key : protections) {
                if (!ProtectionGate.KEYS.contains(key)) {
                    problem = "未知的防护开关 key：" + key + "（本仓认这五个：" + ProtectionGate.KEYS + "）";
                }
            }
        }
        if (problem == null && injections != null) {
            for (String key : injections) {
                if (!INJECTION_KEYS.contains(key)) {
                    problem = "未知的注入开关 key：" + key + "（本仓认这三个：" + INJECTION_KEYS + "）";
                }
            }
        }
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }

        if (protections != null) {
            gate.apply(protections);
        }
        if (injections != null) {
            this.injections = List.copyOf(dedupOrdered(injections));
        }
        version.incrementAndGet();
        State now = state();
        if (now.isDefault()) {
            log.info("[ATTACK-CONFIG] 开关配置已更新（默认配置）：{}", now);
        } else {
            log.warn("[ATTACK-CONFIG] 开关配置已更新（**非默认**：真实流量不受关掉的那些防护保护；"
                    + "重启后端或 POST 全开可恢复）：{}", now);
        }
        return now;
    }

    /**
     * 恢复默认：防护**全开** + 注入**全关**（与 {@code AttackEngine} 的缺省逐字一致）。
     *
     * <p>⚠️ 它是「拨完忘了拨回来」的**唯一**手工恢复点（重启是另一个）。前端在两页的开关栏
     * 各放一枚 [恢复默认]，且只在非默认时可用（{@link State#isDefault()}）。
     *
     * <p>⚠️ {@link ProtectionGate#resetToDefault()} **方法本身留着**（本方法就是它的调用者）——
     * 阶段 2b 删掉的是 {@code DrillRecordService} 收尾处那次**自动**调用，不是这个方法。
     */
    public State resetToDefault() {
        gate.resetToDefault();
        this.injections = List.of();
        version.incrementAndGet();
        State now = state();
        log.info("[ATTACK-CONFIG] 开关配置已恢复默认：{}", now);
        return now;
    }

    /**
     * 开关参数（JSON 数组串）→ 字符串列表。空白 ⇒ {@code null}（= 没给 ⇒ 不改这一项）。
     *
     * <p>（这个方法从 {@code DrillRecordService} 搬过来 —— 那边删掉两个入参之后它就没有调用者了，
     * 而**语义一字未改**：空白 = 没给、解析失败 = 抛。）
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

    /** 去掉重复项，保留**首次出现**的顺序（同一个键出现两次只算一个；顺序稳定，便于比对）。 */
    private static List<String> dedupOrdered(List<String> keys) {
        List<String> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (!out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * 配置快照。
     *
     * @param protections 开着的防护键（声明序）
     * @param injections  开着的注入键（声明序）
     * @param isDefault   是否等于默认值（防护全开 + 注入全关）
     * @param version     写入次数（见 {@code version} 字段注释）
     */
    public record State(List<String> protections, List<String> injections, boolean isDefault,
                        long version) {
    }
}
