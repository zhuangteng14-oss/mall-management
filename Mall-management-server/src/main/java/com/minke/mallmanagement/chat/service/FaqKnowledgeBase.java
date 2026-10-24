package com.minke.mallmanagement.chat.service;

import java.util.List;

/**
 * 轻量 FAQ 知识库（2026-10-22 增量：挂在 UNKNOWN 分支的兜底能力）。
 *
 * <p>本轮**不建表**：条目是类内的静态清单，改文案只需改这里；没有向量、没有相似度模型，
 * 只有「规范化文本里包含关键词」这一条确定性判据。理由与本仓不接 LLM 同源：条目只有个位数，
 * 关键词包含已足够，检索打分再复杂也只是给 {@code contains} 包一层不可审计的外壳。
 *
 * <p>匹配分 = 命中关键词的长度和，取最高分；同分取清单里靠前的一条。至少命中一个关键词
 * 才算命中 —— 空输入、无关输入一律回 {@code null}，由调用方走原兜底话术。
 *
 * <p>⚠️ {@link Answer#policy()} 的两个条目（拆封退货 / 运费）要在**任务意图之前**匹配：
 * 用户给的示例「拆封了能退货吗」含「退货」，会先被 {@code REFUND_QUERY} 正则截走；
 * 若只在 UNKNOWN 分支查，这条示例永远答不到。其余条目（人工客服）仍走 UNKNOWN 分支。
 */
public final class FaqKnowledgeBase {

    /**
     * @param text   标准答案（原样返回，不做二次拼接）
     * @param policy {@code true} = 政策类问题，先于订单/售后意图匹配
     */
    public record Answer(String text, boolean policy) {
    }

    private record Entry(List<String> keywords, String text, boolean policy) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry(List.of("拆封", "能退", "七天无理由", "7天无理由"),
                    "已拆封的商品，若不影响二次销售（吊牌、包装完整）可在 7 天内无理由退货；"
                            + "如果是质量问题（比如东西坏了），拆封也可以退换，运费由商家承担。", true),
            new Entry(List.of("运费", "包邮", "邮费"),
                    "本店满99包邮，偏远地区除外。", true),
            new Entry(List.of("人工客服", "真人客服", "转人工"),
                    "目前是自助客服；订单和售后问题可以直接问我，复杂问题请联系店铺客服。", false));

    private FaqKnowledgeBase() {
    }

    /**
     * @param normalized {@link ChatIntentRecognizer#normalize(String)} 之后的文本
     * @return 命中条目；未命中 {@code null}
     */
    public static Answer match(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        Entry best = null;
        int bestScore = 0;
        for (Entry entry : ENTRIES) {
            int score = 0;
            for (String keyword : entry.keywords()) {
                if (normalized.contains(keyword)) {
                    score += keyword.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        return best == null ? null : new Answer(best.text(), best.policy());
    }
}
