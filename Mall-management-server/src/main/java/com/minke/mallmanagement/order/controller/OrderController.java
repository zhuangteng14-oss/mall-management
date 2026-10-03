package com.minke.mallmanagement.order.controller;

import com.minke.mallmanagement.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 订单域的 HTTP 适配器 —— 从 {@code GoodsController} 拆出的 8 个订单端点。
 *
 * <p>2026-10-02 拆分：原类一个类干两件事（商品 9 + 订单 8），是当时最重的架构债。
 * 拆分的<b>唯一目的</b>是让「类所在域」与「URL 前缀」一致 ——
 * 本笔同时把前缀从 {@code /mysqlGoods} 规范化为 {@code /order}
 * （另 8 条商品 URL 与 5 条用户 URL 同笔规范化，见 docs/agent/api-contract.md）。
 *
 * <p><b>方法体一字未动</b>：8 个方法各自仍是一行 {@code return orderService.x(...)}，
 * SQL / 分支 / 文案早在订单域三层重构（PR #25）时就下沉到 {@link OrderService} 了。
 * 本类只保留路由与入参绑定 —— ⚠️ 形参名与顺序必须逐字保留（{@code @RequestParam} 未写显式名，
 * 全靠形参名解析，{@code -parameters} 已开）。
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    // ===== 订单域（三层重构第一笔，2026-10-02）=====
    // 订单域的全部 SQL / 分支 / 文案已下沉到 OrderService（数据访问再下沉到 OrderDao）。
    // ⚠️ 本类**不再**持有 DistributedLockService / OrderStateMachine / OrderTransitionRecorder：
    // 三者的编排（取锁、生成 CAS 语句、观测投递）都已随方法体进 Service。
    // 切片侧配套见 AbstractSmokeTest 的 @Import —— OrderService / OrderDao 用**真 bean**，
    // 因为 OrderPayIfUpSmokeTest 要钉的是「rows → 文案」这段映射，mock 掉就只剩一句空命题。
    @Autowired
    private OrderService orderService;

    //创建订单时要录入数据库的信息
    // 方法体（锁编排 + 临界区内查重 + INSERT + 出生观测 + 全部文案）搬到 OrderService.orderAdd()。
    // 本方法只保留路由与入参绑定 —— ⚠️ 形参名与顺序**必须**逐字保留：@RequestParam 未写显式名，
    // 全靠形参名解析（-parameters 已开）。
    // ⚠️ 取锁、查重、SELECT/INSERT 三段现在都在 Service 侧：本类**不再**引 DistributedLockService，
    //    也**不再**直接引 JdbcTemplate 做订单 SQL —— 这是「数据出口收成一处」在本域的落地。
    @PostMapping("/orderAdd")
    public Map<String, Object> orderAdd(
            @RequestParam("CustomerId") String CustomerId,
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("OrderId") String OrderId,
            @RequestParam("Price") float Price,
            @RequestParam("ProductInformation") String ProductInformation,
            @RequestParam("Images") String Images,
            @RequestParam("PayIf") int PayIf,
            @RequestParam("DeliveryIf") int DeliveryIf,
            @RequestParam("ReceiveIf") int ReceiveIf
    ) {
        return orderService.orderAdd(
                CustomerId, MerchantId, OrderId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf);
    }

    //购物车搜索订单
    // 方法体（SQL / try-catch / 返回结构）逐字搬到 OrderService.orderSelect()，本方法只保留路由与委托
    // —— 与 goodsSelect / goodsSelectHome / GoodsProductCodeSelect 同款（「方法体搬到…本方法只保留路由与委托」）。
    @PostMapping("/orderSelect")
    public Map<String, Object> orderSelect(
            @RequestParam("customerId") String customerId
    ) {
        return orderService.orderSelect(customerId);
    }

    //订单中心搜索订单
    @PostMapping("/orderDeliveryIfSelect")
    public Map<String, Object> orderDeliveryIfSelect(@RequestParam("merchantId") String merchantId) {
        return orderService.orderDeliveryIfSelect(merchantId);
    }

    //商家端已经完成订单信息
    // ⚠️ status 的默认值 "active" 留在本方法：它是**入参绑定**（@RequestParam 的 defaultValue），
    //    不是业务规则 —— 搬进 Service 就得在 Service 里再写一遍默认值，两处会漂移。
    @PostMapping("/orderDeliverySelect")
    public Map<String, Object> orderDeliverySelect(
            @RequestParam("merchantId") String merchantId,
            @RequestParam(value = "status", defaultValue = "active") String status
    ) {
        return orderService.orderDeliverySelect(merchantId, status);
    }


    //历史订单信息页面信息
    // 方法体（含「用户ID不能为空」那条先于查库的分支）逐字搬到 OrderService.historicalOrdersSelect()。
    @PostMapping("/historicalOrdersSelect")
    public Map<String, Object> historicalOrdersSelect(@RequestParam("customerId") String customerId) {
        return orderService.historicalOrdersSelect(customerId);
    }



    //付款按钮更新信息
    // 方法体（取锁 + 状态机 SQL + 分支 + 文案 + 观测）逐字搬到 OrderService.payIfUp()。
    // ⚠️ 锁 tag = order-pay 随之进 Service：A3 的 A-S2a-1a 是按 tag 读锁指标的，不许换。
    // ⚠️ 两条文案「付款成功」/「订单不存在或已付款」也被 A-S3-2 逐字断言，且已随方法下沉
    //    （不在本类留参数）—— 理由是文案与它对应的分支不可分，拆开会让同一处改动要改两处。
    @PostMapping("/orderPayIfUp")
    public Map<String, Object> orderPayIfUp(@RequestParam("orderId") String orderId) {
        return orderService.payIfUp(orderId);
    }

    //收货按钮更新信息
    // ⚠️ B2：锁 tag = order-receive。净正确性收益为 0（理由同付款）。
    @PostMapping("/orderReceiveIfUp")
    public Map<String, Object> orderReceiveIfUp(@RequestParam("orderId") String orderId) {
        return orderService.receiveIfUp(orderId);
    }

    //发货按钮更新信息
    // ⚠️ B2：锁 tag = order-delivery。净正确性收益为 0（理由同付款）。
    @PostMapping("/orderDeliveryIfUp")
    public Map<String, Object> orderDeliveryIfUp(@RequestParam("orderId") String orderId) {
        return orderService.deliveryIfUp(orderId);
    }
}
