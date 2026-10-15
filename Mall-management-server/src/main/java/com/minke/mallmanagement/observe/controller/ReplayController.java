package com.minke.mallmanagement.observe.controller;

import com.minke.mallmanagement.observe.service.ReplayService;
import com.minke.mallmanagement.shared.web.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 演练回放出口（块 7 收尾）—— <b>一条</b>端点：把一个批次的事件按发生时刻读回来。
 *
 * <h2>【为什么是「新端点」而不是塞进 {@code /attack} 那六条】</h2>
 * 用户 2026-10-15 在「甲（新端点）/ 乙（塞进现有面）」之间选了**甲**，两条理由：
 * <ol>
 *   <li><b>语义干净</b>：本端点读的是 {@code observed_event}（事件台账），**不是**
 *       {@code drill_record}（演练记录）—— 两者只是「同一次演练跑出来的两样东西」，
 *       回放本身不是演练记录的一个字段。</li>
 *   <li><b>不引第二条长连接</b>：另一条候选路是「再开一条 SSE 把回放推下去」，那要多一条
 *       常驻流（块 7 的三条流已经够多），还要回答「回放流断了怎么办」。改成一次性 JSON +
 *       前端自己按时间戳撒点，这两个问题都不存在。</li>
 * </ol>
 * ⇒ 路径是 {@code /admin/observe/replay}（用户 2026-10-15 给的端点规格），**不是**
 * {@code /admin/observe/attack/replay}。故本类不复用 {@link AttackController} 的类级
 * {@code @RequestMapping}，而是自带一个 —— 顺带绕开了那处登记过十九次的构造器地雷
 * （回放要读 DAO，把 DAO 塞进 {@code AttackController} 会让它的构造参数再涨一个）。
 *
 * <h2>【端点口径：与本域其余各条逐条相同】</h2>
 * 全 {@code POST}（{@code api/observe.js} 一律 {@code http.post} + 表单串，见 {@link AttackController}
 * 类注释第 2 节）、入参 {@code @RequestParam}（**不用** {@code @RequestBody}）、不加角色闸、
 * 不在 {@code AUTH_WHITELIST}（靠 {@code AuthInterceptor} 拦）。
 *
 * <h2>【入参非法走 400，不是 200 + {@code success:false}】</h2>
 * {@code batchId} 空白 / {@code limit} 不是整数都抛 {@link IllegalArgumentException} ⇒
 * 本类显式构 {@link ResponseEntity#badRequest()}（**不抛异常**：{@code GlobalExceptionHandler}
 * 的类级白名单不覆盖本类，抛出去会变成默认错误页 —— 同 {@link AttackController#run} 的处置）。
 */
@RestController
@RequestMapping("/admin/observe")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    /**
     * 读一个批次的事件（按 {@code OccurredAt} 升序，同毫秒内按 {@code Id} 升序）。
     *
     * @param batchId 批次 UUID（**必填**；演练记录那一行的 {@code BatchId}，
     *                与引擎写入 {@code observed_event.BatchId} 的是同一串值）
     * @param limit   最多几条（可选，缺省 {@value ReplayService#DEFAULT_LIMIT}、
     *                上限 {@value ReplayService#MAX_LIMIT}）
     * @return {@code {"success":true, "batchId":"…", "list":[…], "total":N, "limit":M}}。
     *         ⚠️ {@code total} 是该批次**一共有多少条**（不是返回了几条）⇒ 前端才能写出
     *         「共 N 条，本次回放前 M 条」；只在 {@code total > list.length} 时才提这一句。
     *         ⚠️ 批次不存在 / 已被清理任务删掉 ⇒ {@code list} 空表 + {@code total=0}，
     *         **仍然是 200**（「这个批次没有事件」是一个正常答案，不是错误）。
     */
    @PostMapping("/replay")
    public ResponseEntity<Object> replay(
            @RequestParam(value = "batchId", required = false) String batchId,
            @RequestParam(value = "limit", required = false) String limit) {
        try {
            return ResponseEntity.ok(Result.of(replayService.replay(batchId, limit)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
        }
    }
}
