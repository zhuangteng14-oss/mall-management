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
 * <p>⚠️ {@link Answer#policy()} 的政策条目要在**任务意图之前**匹配：
 * 用户给的示例「拆封了能退货吗」含「退货」，会先被 {@code REFUND_QUERY} 正则截走；
 * 若只在 UNKNOWN 分支查，这条示例永远答不到。其余条目（人工客服）仍走 UNKNOWN 分支。
 *
 * <p>2026-10-26：政策条目按「问法」拆细（拆封 / 吊牌 / 包装 / 运费 / 偏远 / 质量 /
 * 人为损坏 / 换新 / 时效 / 节假日 / 入口 / 起算 / 承诺 / 正品 / 运费险），
 * 一条只答一个子问题 —— RAGAS 的 Answer Relevancy 会因「答非所问的附带信息」掉分，
 * 拆细后既提分，也让用户少读无关段落。
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
            new Entry(List.of("拆封", "拆开"),
                    "已拆封的商品，只要不影响二次销售（吊牌、包装完整），7 天内可以无理由退货。",
                    true),
            new Entry(List.of("吊牌"),
                    "吊牌剪了会影响二次销售，通常不能无理由退货；如果是质量问题，仍可退换，"
                            + "运费由商家承担。", true),
            new Entry(List.of("包装拆", "包装完整"),
                    "包装拆开但保持完整、不影响二次销售，7 天内仍可无理由退货。", true),
            new Entry(List.of("二次销售", "影响二次"),
                    "影响二次销售（吊牌或包装不完整）不能无理由退货；质量问题仍可退换。", true),
            new Entry(List.of("七天无理由", "7天无理由", "从哪天开始算", "从哪天算", "七天从哪天"),
                    "七天无理由自签收之日起算，需商品不影响二次销售。", true),
            new Entry(List.of("签收之日起", "签收起算"),
                    "是的，七天无理由自签收之日起算，需商品不影响二次销售。", true),
            new Entry(List.of("偏远"),
                    "偏远地区不包邮，其他地区满99元包邮。", true),
            new Entry(List.of("运费", "包邮", "邮费"),
                    "运费按订单金额算：满99元包邮；不满99元邮费由买家承担，偏远地区除外。", true),
            new Entry(List.of("满多少包邮", "多少包邮"),
                    "满99元包邮，偏远地区除外。", true),
            new Entry(List.of("质量问题", "东西坏了", "坏了"),
                    "质量问题支持退换：商家承担往返运费并免费换新。", true),
            new Entry(List.of("人为损坏", "有偿维修"),
                    "人为损坏可以修，属于有偿维修，费用另议。", true),
            new Entry(List.of("免费换新"),
                    "质量问题可以免费换新，往返运费由商家承担。", true),
            new Entry(List.of("售后时效", "响应时效", "多久回复", "多久处理", "处理时效",
                            "多长时间回复"),
                    "售后响应时效：工作日 24 小时内回复，节假日 48 小时内。", true),
            new Entry(List.of("节假日"),
                    "节假日售后 48 小时内回复。", true),
            new Entry(List.of("售后入口", "联系商家"),
                    "售后入口：订单详情里的「申请售后」，也可以从消息里联系商家。", true),
            new Entry(List.of("申请售后"),
                    "在订单详情里点「申请售后」即可提交。", true),
            new Entry(List.of("服务承诺"),
                    "商品页的服务承诺有四项：正品保障、7 天无理由、极速退款、运费险。", true),
            new Entry(List.of("正品保障", "正品"),
                    "有正品保障，商品页服务承诺里有标注。", true),
            new Entry(List.of("运费险"),
                    "有运费险，商品页服务承诺里包含运费险。", true),
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
