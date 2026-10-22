package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
import com.minke.mallmanagement.chat.tool.AgentParam;
import com.minke.mallmanagement.chat.tool.AgentTool;
import com.minke.mallmanagement.chat.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内置业务工具的注解声明层：一个方法 = 一个工具，注册表启动时自动发现。
 *
 * <h2>【为什么不把注解放进 ChatAgentService】</h2>
 * 编排层（{@link ChatAgentService}）要注入 {@code ToolRegistry}；若注解也打在编排层的方法上，
 * 注册表扫描时又会把编排层 Bean 拉进来 —— 读起来像一个环。把「工具形状」单独放在本类，
 * 编排层只负责「选哪个工具」，本类负责「工具长什么样」，两者用包内可见的查询方法连接。
 *
 * <h2>【角色切分是有意做细的】</h2>
 * 订单 / 售后 / 评论各按买家（1）与商家（2）拆成两个工具：模型的 System Prompt 里只会出现
 * 当前角色那一份 ⇒ 商家永远看不到「查我买过的订单」，买家也看不到「店铺待处理售后」。
 * 数据隔离的最后一跳仍在 DAO 的 {@code CustomerId = ?} / {@code MerchantId = ?} 行级条件，
 * 这里的角色过滤只是**减少误导**，不是唯一防线。
 *
 * <h2>【新增工具的成本】</h2>
 * 在本类（或任意 Bean）里加一个带 {@link AgentTool} 的方法即可：不用改
 * {@link LlmIntentClassifier} 的 Prompt、不用改 {@code ChatAgentService} 的分发、
 * 不用维护字典清单。{@link com.minke.mallmanagement.chat.tool.ToolRegistry} 会反射出
 * 参数 JSON Schema 并注入到下一轮 Prompt。
 */
@Component
public class ChatToolSet {

    private final ChatAgentService agent;

    public ChatToolSet(ChatAgentService agent) {
        this.agent = agent;
    }

    // ==================== 订单：买家 / 商家两份 ====================

    @AgentTool(name = "buyer_orders",
            description = "查「我」作为买家买过的订单：最近 3 笔的商品、单号、金额、付款/发货/收货状态与下单时间；"
                    + "用户给了单号就按单号查那一笔（查不到会明确说「可能不是你的单号」）。"
                    + "用户问「我的订单 / 那一单 / 物流到哪 / 最近买了什么」时用它。",
            targetRole = {1},
            intent = ChatIntent.ORDER_QUERY)
    public Map<String, Object> buyerOrders(
            @AgentParam(name = "orderId", description = "订单号；用户没提就留空", required = false)
            String orderId,
            ToolContext ctx) {
        return agent.answerOrders(ctx.message(), ctx.userId(), ctx.role(), orderId);
    }

    @AgentTool(name = "merchant_orders",
            description = "查「我」作为商家收到的店铺订单：最近 3 笔的商品、单号、金额、状态与下单时间；"
                    + "用户给了单号就按单号查那一笔。商家问「店里订单 / 有没有新订单 / 发货了没」时用它。",
            targetRole = {2},
            intent = ChatIntent.ORDER_QUERY)
    public Map<String, Object> merchantOrders(
            @AgentParam(name = "orderId", description = "订单号；用户没提就留空", required = false)
            String orderId,
            ToolContext ctx) {
        return agent.answerOrders(ctx.message(), ctx.userId(), ctx.role(), orderId);
    }

    // ==================== 售后：买家进度 / 商家工作台 ====================

    @AgentTool(name = "buyer_refunds",
            description = "查「我」作为买家的售后 / 退款进度：订单号、退款状态（如待商家处理 / 已退款）、"
                    + "退款金额、原因、申请与处理时间、第几次申请。用户问「我的退款到哪了 / 售后结果」时用它。",
            targetRole = {1},
            intent = ChatIntent.REFUND_QUERY)
    public Map<String, Object> buyerRefunds(ToolContext ctx) {
        return agent.answerRefund(ctx.message(), ctx.userId(), ctx.role());
    }

    @AgentTool(name = "merchant_refunds",
            description = "查「我」店铺的售后工作：最近一条退款申请的状态、金额、买家原因、申请与处理时间；"
                    + "商家问「有没有待处理的退款 / 售后进度 / 怎么处理这笔退款的结果」时用它。"
                    + "⚠️ 用户问的是「去哪个页面处理退款」时不要用它，改用 navigate。",
            targetRole = {2},
            intent = ChatIntent.REFUND_QUERY)
    public Map<String, Object> merchantRefunds(ToolContext ctx) {
        return agent.answerRefund(ctx.message(), ctx.userId(), ctx.role());
    }

    // ==================== 评论 / 评价 ====================

    @AgentTool(name = "buyer_comments",
            description = "查「我」作为买家发表过的评论 / 评价：总数、最近几条的正文、评分、商品名，"
                    + "以及每条是否已被商家回复、回复条数。用户问「我发过的评论 / 我的评价 / 商家回复我了吗」时用它。"
                    + "平台没有独立的评价管理页。",
            targetRole = {1},
            intent = ChatIntent.COMMENT_QUERY)
    public Map<String, Object> buyerComments(ToolContext ctx) {
        return agent.answerComments(ctx.message(), ctx.userId(), ctx.role());
    }

    @AgentTool(name = "merchant_comments",
            description = "查「我」店铺商品收到的评论 / 提问：总数、其中还没有商家回复的条数、最近几条的正文与评分、"
                    + "以及是否已被商家回复。商家问「有没有人评论我的商品 / 有没有差评 / 有评论没回」时用它。"
                    + "平台没有独立的评价管理页，评论随商品详情展示。",
            targetRole = {2},
            intent = ChatIntent.COMMENT_QUERY)
    public Map<String, Object> merchantComments(ToolContext ctx) {
        return agent.answerComments(ctx.message(), ctx.userId(), ctx.role());
    }

    // ==================== 开店 / 秒杀（身份分流） ====================

    @AgentTool(name = "open_store",
            description = "开店 / 商家入驻：先报系统解析出的真实身份（客户 / 审核中 / 未通过 / 商家 / 品牌商家 / 管理员），"
                    + "再给出对应下一步；需要时附带「一键带我去开店」动作（前端会启动两步高亮漫游）。"
                    + "用户问「怎么开店 / 我要入驻 / 我是商家」时用它。",
            targetRole = {1, 2, 3},
            intent = ChatIntent.OPEN_STORE)
    public Map<String, Object> openStore(ToolContext ctx) {
        return agent.answerOpenStore(ctx.userId(), ctx.role());
    }

    @AgentTool(name = "seckill_signup",
            description = "报名秒杀 / 参加平台活动：只做身份分流与页面导航，不猜商品、价格与库存。"
                    + "商家会拿到「去秒杀报名」入口；未入驻 / 审核中 / 被拒 / 未登录各有对应指引。",
            targetRole = {1, 2},
            intent = ChatIntent.ADD_TO_SECKILL)
    public Map<String, Object> seckillSignup(
            @AgentParam(name = "need_publish",
                    description = "用户明说商品「还没上架 / 还没发布」时为 true，否则 false",
                    required = false)
            boolean needPublish,
            ToolContext ctx) {
        return agent.answerAddToSeckill(ctx.userId(), ctx.role(), needPublish);
    }

    // ==================== 页面导航 ====================

    @AgentTool(name = "navigate",
            description = "按目标页面带用户过去（前端从当前页 BFS 算路并逐步高亮）。"
                    + "target_path 必须从 System Prompt 里的「可选页面」字典逐字取，不能自己拼。"
                    + "用户问「怎么做 / 如何办 / 去哪办 / 在哪看」某个页面时用它。",
            targetRole = {},
            intent = ChatIntent.NAVIGATE)
    public Map<String, Object> navigate(
            @AgentParam(name = "target_path", description = "目标页面路径，取自「可选页面」字典") String targetPath,
            @AgentParam(name = "need_publish", description = "仅秒杀目标有意义，其余给 false", required = false)
            boolean needPublish,
            ToolContext ctx) {
        return agent.answerNavigate(targetPath, ctx.userId(), ctx.role(), needPublish);
    }
}
