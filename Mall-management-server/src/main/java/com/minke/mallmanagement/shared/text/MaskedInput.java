package com.minke.mallmanagement.shared.text;

import java.util.List;

/**
 * 「掩码符号被当成真值提交」的入参闸（块 7 收尾 · A5）。
 *
 * <p>背景：商城端的展示口径是**掩码**（如 {@code ab***@x.com} / {@code 138****1234}）。
 * 用户从页面上把掩码后的字符串复制进编辑框、再原样提交，数据库里就会存下一串带
 * {@code *} 的假数据 —— 之后任何「掩码」都只会得到更多的星号，原值再也找不回来。
 * 故所有**写**邮箱 / 地址 / 电话的入口，都在最外层拒掉含 {@code *} 的值。
 *
 * <h2>【为什么是 {@code *}，不是更宽的正则】</h2>
 * <ul>
 *   <li>只拦**一个确定的坏形态**：{@code *} 是本站掩码唯一使用的符号（见前端展示层）。
 *       不收窄成「必须匹配邮箱格式」—— 那会把 {@code 13800138000} 之类合法的历史数据挡在门外。</li>
 *   <li><b>不碰密码 / 自我介绍</b>：密码里出现 {@code *} 完全合法；自我介绍是自由文本。
 *       本类只给「展示时会被掩码的字段」用。</li>
 * </ul>
 *
 * <p>⚠️ 本类不做 I/O、不记日志；纯判定。
 */
public final class MaskedInput {

    /** 掩码符号。与前端展示层的掩码字符**逐字相同**。 */
    public static final char MASK_CHAR = '*';

    private MaskedInput() {
    }

    /** {@code value} 里是否出现了掩码符号（{@code null} ⇒ false）。 */
    public static boolean isMasked(String value) {
        return value != null && value.indexOf(MASK_CHAR) >= 0;
    }

    /**
     * 在「字段名, 值」两两成对的入参里找出**第一个**带掩码符号的字段。
     *
     * @param fieldValuePairs 成对的 {@code ("email", email, "address", address)}；
     *                        长度为奇数属调用方笔误，多出的那一项忽略
     * @return 命中的字段名；都没有命中 ⇒ {@code null}
     */
    public static String firstMasked(String... fieldValuePairs) {
        if (fieldValuePairs == null) {
            return null;
        }
        for (int i = 0; i + 1 < fieldValuePairs.length; i += 2) {
            if (isMasked(fieldValuePairs[i + 1])) {
                return fieldValuePairs[i];
            }
        }
        return null;
    }

    /**
     * 统一的拒绝文案。
     *
     * <p>写清「为什么」与「该怎么做」—— 只说「非法输入」的话，用户看不出是掩码惹的祸，
     * 会以为是网站坏了。
     */
    public static String rejectMessage(String field) {
        return "「" + displayName(field) + "」不能包含 * 号：页面上的 * 是掩码，"
                + "不是真实内容，请填写完整值。";
    }

    private static String displayName(String field) {
        return switch (field) {
            case "email" -> "邮箱";
            case "address" -> "地址";
            case "contactPhone" -> "联系电话";
            default -> field;
        };
    }

    /** 供测试/日志断言使用：本类视为「掩码字段」的名字清单。 */
    public static List<String> guardedFields() {
        return List.of("email", "address", "contactPhone");
    }
}
