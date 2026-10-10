package com.minke.mallmanagement.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 笔 2（统一响应体 {@link Result}）的**回归闸** —— 验收判据 (a-1)。
 *
 * <h2>【它钉的是什么】</h2>
 * 本笔把 28 个 Controller 端点的返回类型从 {@code Map<String, Object>} 换成
 * {@code Result<Map<String, Object>>}（Service 层不动，Controller 侧用
 * {@link Result#of(Map)} 包一层）。<b>这笔交易的风险全在一处</b>：
 * 换完之后响应体的**序列化字节**是否与改动前逐字节相同。
 * 判据不是「看起来等价」，是**取字节**——因为前端 {@code res.json()} 按键取值、
 * A3 全是 {@code body.contains(...)} 子串匹配，两者都**对键序免疫**，
 * 「看起来等价」无法证伪；而键序本身会随 Map 实现变化（见
 * {@link #mapKeyOrderIsObservableSoTheEqualityAssertionIsNotVacuous}）。
 *
 * <h2>【为什么解成 byte[] 而不是 String】</h2>
 * 要钉的是 HTTP 响应体那一串字节。转成 {@code String} 会多一次「解码再比较」，把编码这一步
 * 引进来；{@code writeValueAsBytes} 直接给字节，而 Jackson 固定按 UTF-8 编码
 * （{@code JsonEncoding.UTF8}），不需要我们再选一次。
 *
 * <h2>【为什么用 {@code Jackson2ObjectMapperBuilder} 而不是 {@code new ObjectMapper()}】</h2>
 * 线上用的是 Spring Boot 自动配置的那个 {@code ObjectMapper}（全仓无任何
 * {@code ObjectMapper} / {@code Jackson2ObjectMapperBuilder} 自定义 bean——已 grep 核实）。
 * Boot 的 {@code JacksonAutoConfiguration} 就是拿这个 builder 建的，
 * 故此处与线上同源。补一句：<b>Map 的键序由 Map 实现本身决定，与 ObjectMapper 的开关无关</b>
 * （既没开 {@code ORDER_MAP_ENTRIES_BY_KEYS}，也没开 {@code SORT_PROPERTIES_ALPHABETICALLY}），
 * 所以这一层选择不影响结论。
 *
 * <h2>【它不覆盖什么】</h2>
 * 端到端 golden 比对（判据 (a-2)）在**本测试之外**：它要起 Redis + 后端、打全 33 个端点、
 * 与改动前的落盘 JSON 逐字节 diff。本测试覆盖的是「类型 / 顺序」这个**唯一变量**——
 * 即：把同一份内容从 {@code HashMap} 搬进 {@code Result}，字节会不会变。
 * 至于「内容本身有没有被改」，靠 (a-2) 与既有 27 门禁。
 *
 * <p>⚠️ 这些断言变红 = 有人在改线上响应字节，**不是**测试需要更新。
 */
class ResultByteEquivalenceTest {

    /** 与线上同源：Boot 自动配置 ObjectMapper 用的就是它。 */
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    // ===================== 一、9 组真实响应形状：旧 HashMap vs 新 Result =====================

    @Test
    @DisplayName("9 组真实响应形状：HashMap（旧）与 Result.of(同内容)（新）序列化**逐字节相同**")
    void legacyHashMapAndResultOfSerializeToIdenticalBytes() {
        // 键名与嵌套结构抄自 Service 层的真实响应组装（grep `result.put("` 逐条核过），
        // 不是编的。注意 goodImg 与 GoodImg 是**两个不同**的键，别合并。
        assertSameBytes("① 单键 success（productCodePrice 失败分支：只有 success 没有 msg）",
                map("success", true));

        assertSameBytes("② success + msg（A-S3-2 逐字文案）",
                map("success", false, "msg", "订单不存在或已付款"));

        assertSameBytes("③ success + list（goodsSelect / orderSelect 等 13 处）",
                map("success", true, "list", rows()));

        assertSameBytes("④ success + price + goodImg（productCodePrice 成功分支）",
                map("success", true, "price", 19.9f, "goodImg", "http://localhost:8088/img/g1.png"));

        assertSameBytes("⑤ success + user（嵌套 Map，且内层 image 为 null —— MessageService:303）",
                map("success", true, "user", nestedUserWithNullImage()));

        assertSameBytes("⑥ success + otherUser + messages（getMessageHistory 成功分支）",
                map("success", true, "otherUser", nestedUserWithNullImage(), "messages", rows()));

        // ⚠️ C3 块 3：goodsAdd 改提审制后文案随之为「已提交审核」，且成功分支**多了一个 batchId 键**。
        //    本条钉的是 Result.of 的**字节等价性**（键形状 success+msg+GoodImg 不变），
        //    不是 goodsAdd 的完整键集 —— batchId 是第四个键，另有形状可加，但不影响本断言。
        assertSameBytes("⑦ success + msg + GoodImg（goodsAdd 提审成功分支，首字母大写的 GoodImg）",
                map("success", true, "msg", "已提交审核", "GoodImg", "http://localhost:8088/img/g1.png"));

        assertSameBytes("⑦b success + msg + GoodImg + batchId（goodsAdd 提审成功分支的完整键集）",
                map("success", true, "msg", "已提交审核", "GoodImg", "http://localhost:8088/img/g1.png",
                        "batchId", "0b1e7b1a-0000-4000-8000-000000000000"));

        assertSameBytes("⑧ 空 Map（无任何键）", new HashMap<String, Object>());

        assertSameBytes("⑨ 顶层含 null 值（updateAvatar 未设 imageUrl 的路径）",
                map("success", true, "imageUrl", null));
    }

    // ===================== 二、工厂方法与手写 HashMap 等价 =====================

    @Test
    @DisplayName("Result.ok() / ok(payload) / fail(msg) 与等价手写 HashMap **逐字节相同**")
    void factoriesMatchTheirHandBuiltHashMapEquivalent() {
        assertThat(bytes(Result.ok()))
                .as("Result.ok() 应等价于手写 {success:true}")
                .isEqualTo(bytes(map("success", true)));

        Map<String, Object> payload = map("list", rows());
        assertThat(bytes(Result.ok(payload)))
                .as("Result.ok(payload) 应等价于手写 {success:true, list:[...]}")
                .isEqualTo(bytes(map("success", true, "list", rows())));

        assertThat(bytes(Result.fail("角色错误")))
                .as("Result.fail(msg) 应等价于手写 {success:false, msg:...}")
                .isEqualTo(bytes(map("success", false, "msg", "角色错误")));
    }

    // ===================== 三、非空性对照：证明上面的等式不是废命题 =====================

    @Test
    @DisplayName("对照：Map 键序**确实**可观测 —— 上面的逐字节断言不是废命题")
    void mapKeyOrderIsObservableSoTheEqualityAssertionIsNotVacuous() {
        // 这一条防的是「测试看起来在测，其实两边必然相等」。
        // 若 Jackson 恰好在序列化 Map 前把键排了序，那么无论 HashMap 还是 LinkedHashMap
        // 输出都相同，上面 9 组断言就成了废命题 —— 换任何 Map 实现都不会红。
        //
        // 实测（本机 Temurin 17.0.18）：把同样内容装进 LinkedHashMap（插入序），
        // 与 HashMap（桶序）比对，**8 组非空形状里有 4 组字节不同**。故键序确实可观测。
        // 实测基线：8 组形状里 4 组 HashMap / LinkedHashMap 字节不同 —— 见上条 javadoc 的实证。
        // ⚠️ 跨 JDK 可能变，故**不断言**这个数字 —— 后人换 JDK 时，变化是允许的，不是回归。
        //    这里只断言「至少有一组可观测」这个性质，不锁具体条数。
        List<String> differing = new ArrayList<>();
        for (Shape s : shapes()) {
            if (!Arrays.equals(bytes(s.asHashMap()), bytes(s.asLinkedHashMap()))) {
                differing.add(s.name);
            }
        }

        assertThat(differing)
                .as("至少要有一组形状的键序可观测，否则上面的逐字节断言是废命题")
                .isNotEmpty();

        // 更贴近本笔风险的一条：存在「源的迭代序 ≠ 插入序」的形状（HashMap 是桶序，不是插入序）。
        // 于是上面那条等式实际在约束：Result.of() 必须**原样保留源的迭代序**，不许按任何别的
        // 规则（插入序 / 字母序 / 排序）重排。
        //
        // ⚠️ 本闸的判别力有**实证**，不是纸面推演：Result.of 的初版用 HashMap.putAll，
        //    而 putAll 的预置容量把桶数从 16 变成 4 —— 上面形状 ③ 当场红：
        //      expected {"success":true,"list":[…]}  /  actual {"list":[…],"success":true}
        //    根因与 Jackson 无关（两侧序列化器都是同一个 MapSerializer），纯是容量差。
        //    修法即现在的「逐条 put」。
        List<String> iterationDiffersFromInsertion = new ArrayList<>();
        for (Shape s : shapes()) {
            // asLinkedHashMap() 是同一份内容的**插入序**副本，可作对照基准
            if (!new ArrayList<>(s.asHashMap().keySet())
                    .equals(new ArrayList<>(s.asLinkedHashMap().keySet()))) {
                iterationDiffersFromInsertion.add(s.name);
            }
        }
        assertThat(iterationDiffersFromInsertion)
                .as("要有一组形状的「HashMap 迭代序 ≠ 插入序」，否则 of() 是否保序根本测不出来")
                .isNotEmpty();
    }

    // ===================== 夹具 =====================

    /**
     * 一组「同内容、不同 Map 实现」的形状，用于非空性对照。
     * {@code name} 只进断言描述，不参与比对。
     */
    private record Shape(String name, List<KV> entries) {

        /** 装入默认容量的 HashMap —— 与今日 33 处响应 Map 的构造方式一致。 */
        Map<String, Object> asHashMap() {
            Map<String, Object> m = new HashMap<>();
            putInto(m);
            return m;
        }

        /** 同样的内容、同样的插入顺序，但装进 LinkedHashMap（保留插入序）。 */
        Map<String, Object> asLinkedHashMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            putInto(m);
            return m;
        }

        private void putInto(Map<String, Object> m) {
            for (KV e : entries) {
                m.put(e.key(), e.value());
            }
        }
    }

    /** 单个键值对。用它而不是 {@code Object[]} —— {@code List.of(new Object[]{a,b})} 会踩变参歧义。 */
    private record KV(String key, Object value) {
    }

    private static KV kv(String key, Object value) {
        return new KV(key, value);
    }

    @SafeVarargs
    private static List<KV> kvs(KV... entries) {
        return Arrays.asList(entries);
    }

    private List<Shape> shapes() {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Shape("单键 success",
                kvs(kv("success", true))));
        shapes.add(new Shape("success+msg",
                kvs(kv("success", false), kv("msg", "订单不存在或已付款"))));
        shapes.add(new Shape("success+list",
                kvs(kv("success", true), kv("list", rows()))));
        shapes.add(new Shape("success+price+goodImg",
                kvs(kv("success", true), kv("price", 19.9f),
                        kv("goodImg", "http://localhost:8088/img/g1.png"))));
        shapes.add(new Shape("success+user",
                kvs(kv("success", true), kv("user", nestedUserWithNullImage()))));
        shapes.add(new Shape("success+otherUser+messages",
                kvs(kv("success", true), kv("otherUser", nestedUserWithNullImage()),
                        kv("messages", rows()))));
        shapes.add(new Shape("success+msg+GoodImg",
                kvs(kv("success", true), kv("msg", "已提交审核"),
                        kv("GoodImg", "http://localhost:8088/img/g1.png"))));
        shapes.add(new Shape("success+msg+GoodImg+batchId",
                kvs(kv("success", true), kv("msg", "已提交审核"),
                        kv("GoodImg", "http://localhost:8088/img/g1.png"),
                        kv("batchId", "0b1e7b1a-0000-4000-8000-000000000000"))));
        shapes.add(new Shape("success+imageUrl",
                kvs(kv("success", true), kv("imageUrl", null))));
        return shapes;
    }

    // ===================== 工具 =====================

    /** 断言：把 {@code legacy} 原样包进 {@code Result.of(...)} 后，序列化字节不变。 */
    private void assertSameBytes(String shape, Map<String, Object> legacy) {
        Result<Map<String, Object>> wrapped = Result.of(legacy);

        assertThat(wrapped.keySet())
                .as("%s —— 键集合必须一字不差（既不丢键也不增键）", shape)
                .isEqualTo(legacy.keySet());

        assertThat(bytes(wrapped))
                .as("%s —— 旧 HashMap 与新 Result 的响应体字节必须逐字节相同", shape)
                .isEqualTo(bytes(legacy));
    }

    private byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败：" + value, e);
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** MessageService 里 {@code otherUser} / {@code user} 的内层结构（image 显式为 null）。 */
    private static Map<String, Object> nestedUserWithNullImage() {
        Map<String, Object> u = new HashMap<>();
        u.put("id", "1001");
        u.put("username", "zhangsan");
        u.put("usertype", "1");
        u.put("gender", "男");
        u.put("image", null);
        return u;
    }

    /** 13 处 {@code list} 的典型载荷：一行行的 Map。 */
    private static List<Map<String, Object>> rows() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("ProductCode", "P001");
        r1.put("GoodName", "测试商品");
        Map<String, Object> r2 = new HashMap<>();
        r2.put("ProductCode", "P002");
        r2.put("GoodName", "测试商品2");
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(r1);
        list.add(r2);
        return list;
    }
}
