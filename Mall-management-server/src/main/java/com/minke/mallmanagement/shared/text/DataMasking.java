package com.minke.mallmanagement.shared.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 管理端列表的**读侧掩码**（块 7 · D3-4）。
 *
 * <h2>【只掩四个字段，且只掩「管理端列表」】</h2>
 * 用户裁决的字段域是 {@code email} / {@code address} / {@code contactPhone} /
 * {@code ShopAddress}，一个不多。调用点也只有两处：
 * <ul>
 *   <li>{@code UserGovernanceService.listUsers} —— 用户治理列表的 {@code email}；</li>
 *   <li>{@code MerchantApplicationService.listForAdmin} —— 入驻申请列表的
 *       {@code ContactPhone} / {@code ShopAddress}。</li>
 * </ul>
 *
 * <p>⚠️ <b>本人查询与审计快照一律保持明文，别顺手接上</b>：
 * <ul>
 *   <li>{@code MerchantApplicationService.myApplications} —— 商家看自己的申请，看到掩码
 *       就没法核对「我当时填的是不是这个号」；</li>
 *   <li>{@code UserGovernanceService.userRow} —— 它是封禁 / 降级的审计 {@code before} 快照，
 *       审计要能还原事实；掩码后的快照等于把证据改成摘要，事后无法复核。</li>
 * </ul>
 * 这两处**不是漏掩**，是裁决的另外一半；改动前先确认调用方。
 *
 * <h2>【返回新行，不改入参】</h2>
 * {@link #maskUserRow} / {@link #maskApplicationRow} 都先复制再改。DAO 返回的行在本方法之后
 * 仍可能被别的分支复用（如统计、审计），就地改写会让「同一次请求里两处看到的不是同一份事实」。
 *
 * <h2>【掩码形态】</h2>
 * <pre>
 *   alice@example.com        → a***@example.com       （保留域，本地部分只留首字符）
 *   13800138000              → 138****8000            （11 位手机号的标准形态）
 *   北京市朝阳区建国路 1 号    → 北京市朝***           （保留前 4 个字符）
 * </pre>
 * 短串同样保证「至少掩掉一个字符」—— 长度不足时退化为更短的保留前缀，绝不原样返回。
 * {@code null} / 空白串原样返回（库里本来就没有值，掩码不该把它变成 {@code ***}）。
 *
 * <p>⚠️ 本类**不做 I/O、不记日志、不碰 {@code *} 合法性判定**。写侧拦截在
 * {@link MaskedInput}：掩码符号一旦被当成真值提交，真正能挡住它的是入口那一道闸。
 */
public final class DataMasking {

    /** 邮箱域名的分隔符。用 {@code lastIndexOf} —— 本地部分理论上允许带引号的 {@code @}。 */
    private static final char AT = '@';

    /** 11 位手机号的保留前缀 / 后缀位数：{@code 138****8000}。 */
    private static final int PHONE_PREFIX = 3;
    private static final int PHONE_SUFFIX = 4;

    /** 地址保留的前缀字符数：{@code 北京市朝***}。 */
    private static final int ADDRESS_KEEP = 4;

    private DataMasking() {
    }

    // ==================== 单字段 ====================

    /** 邮箱：{@code alice@example.com → a***@example.com}。没有 {@code @} 时只留首字符。 */
    public static String maskEmail(String email) {
        if (isBlank(email)) {
            return email;
        }
        int at = email.lastIndexOf(AT);
        if (at <= 0 || at == email.length() - 1) {
            return keepPrefix(email, 1);
        }
        return email.charAt(0) + "***@" + email.substring(at + 1);
    }

    /**
     * 手机号 / 联系电话：11 位走 {@code 138****8000}；其余长度按「前 2 + **** + 后 2」
     * 或更短的保留前缀处理。非数字字符（{@code +86}、短横线）按字符位置计算 ——
     * 本方法要的是「看上去认不出」，不是「解析出号码」。
     */
    public static String maskPhone(String phone) {
        if (isBlank(phone)) {
            return phone;
        }
        int len = phone.length();
        if (len == PHONE_PREFIX + PHONE_SUFFIX + 4) {
            return phone.substring(0, PHONE_PREFIX) + "****" + phone.substring(len - PHONE_SUFFIX);
        }
        if (len >= 6) {
            return phone.substring(0, 2) + "****" + phone.substring(len - 2);
        }
        if (len >= 3) {
            return phone.charAt(0) + "***";
        }
        return "****";
    }

    /** 地址：保留前 4 个字符，其余以 {@code ***} 结尾（{@code 北京市朝阳区… → 北京市朝***}）。 */
    public static String maskAddress(String address) {
        if (isBlank(address)) {
            return address;
        }
        return keepPrefix(address, ADDRESS_KEEP);
    }

    /**
     * 店铺地址：掩码口径与 {@link #maskAddress} **相同**。
     *
     * <p>单独留一个方法名是为了让调用点自解释（「这里掩的是店铺地址」），也为了将来若
     * 店铺地址要改成「保留到商圈、掩掉门牌」时只改这一处，不必回头找所有 {@code maskAddress}。
     */
    public static String maskShopAddress(String shopAddress) {
        return maskAddress(shopAddress);
    }

    // ==================== 行（复制后改，不动入参） ====================

    /** 用户治理列表的一行：只掩 {@code email}。其余列（含封禁状态、降级列）原样保留。 */
    public static Map<String, Object> maskUserRow(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(row);
        maskIfString(copy, "email", DataMasking::maskEmail);
        return copy;
    }

    /** {@link #maskUserRow} 的批量版；保持原顺序，{@code null} 列表原样返回。 */
    public static List<Map<String, Object>> maskUserRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(maskUserRow(row));
        }
        return out;
    }

    /**
     * 入驻申请列表的一行：掩 {@code ContactPhone} / {@code ShopAddress}。
     *
     * <p>键名在 {@code MerchantApplicationDao.COLUMNS} 里是**首字母大写**的原始列名；
     * 这里同时接受小写写法，避免将来某处加了别名就静默漏掩。
     */
    public static Map<String, Object> maskApplicationRow(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(row);
        maskIfString(copy, "ContactPhone", DataMasking::maskPhone);
        maskIfString(copy, "ShopAddress", DataMasking::maskShopAddress);
        maskIfString(copy, "contactPhone", DataMasking::maskPhone);
        maskIfString(copy, "shopAddress", DataMasking::maskShopAddress);
        return copy;
    }

    /** {@link #maskApplicationRow} 的批量版；保持原顺序，{@code null} 列表原样返回。 */
    public static List<Map<String, Object>> maskApplicationRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(maskApplicationRow(row));
        }
        return out;
    }

    // ==================== 内部 ====================

    /** 保留前 {@code keep} 个字符，其余掩成 {@code ***}；至少掩掉一个字符。 */
    private static String keepPrefix(String value, int keep) {
        int effective = Math.min(keep, value.length() - 1);
        if (effective < 1) {
            return "***";
        }
        return value.substring(0, effective) + "***";
    }

    /**
     * 只对「键存在且值是 String」的项应用掩码。{@code computeIfPresent} 不会把 {@code null}
     * 值当成命中 —— 库里没有值就保持没有值，不要凭掩码凭空造出一个 {@code ***}。
     */
    private static void maskIfString(Map<String, Object> row, String key,
                                     UnaryOperator<String> mask) {
        row.computeIfPresent(key, (k, v) -> v instanceof String s ? mask.apply(s) : v);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
