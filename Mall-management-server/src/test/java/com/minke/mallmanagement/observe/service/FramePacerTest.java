package com.minke.mallmanagement.observe.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「一拍只算一次」闸门（{@link FramePacer}）的边界实测。
 *
 * <p>这个类值一个测试文件，是因为它的**失效方式很安静**：窗口开大一点点，
 * 「两个页面同时开」就会变成「两条流拿到一模一样的一帧」——页面看不出问题（数字是对的，
 * 只是重复），而某条流本该看到的增量被吞了。故边界（恰好半拍）必须钉死。
 *
 * <p>⚠️ {@code intervalMs = 0} 是**测试与「抓一帧」专用**：窗口 0 ⇒ 每次调用都真算
 * （见类注释最后一节）。三个产帧服务的单测都靠它拿到确定性的结果 —— 那条行为也在本文件里钉住。
 */
class FramePacerTest {

    private static final long T0 = 1_000_000_000L;

    @Test
    @DisplayName("半拍窗口内复用、窗口外真算；**恰好**半拍算窗口外（边界取严）")
    void reusableOnlyInsideHalfTickWindow() {
        FramePacer pacer = new FramePacer(1000);   // 半拍 = 500ms = 500_000_000ns
        pacer.remember(frame(0L, "第一拍"), T0);

        assertThat(pacer.reusable(T0)).as("0ns 后：当然还在窗口里").isTrue();
        assertThat(pacer.reusable(T0 + 499_999_999L)).as("差 1ns 到半拍：仍在窗口内").isTrue();
        assertThat(pacer.reusable(T0 + 500_000_000L))
                .as("**恰好**半拍：不复用（< 而非 ≤ —— 边界取严，宁可多算一次也不吞一拍）").isFalse();
        assertThat(pacer.reusable(T0 + 900_000_000L)).as("近一个整拍：真算").isFalse();
    }

    @Test
    @DisplayName("还没记住任何一拍时**不复用**（否则首帧会拿到 null）")
    void notReusableBeforeFirstFrame() {
        FramePacer pacer = new FramePacer(1000);
        assertThat(pacer.reusable(T0)).as("last == null ⇒ 不能复用").isFalse();
    }

    @Test
    @DisplayName("复用帧 = 上一份内容**原样** + 换 seq / ts（页面按 seq 幂等，内容重复不是错数据）")
    void reuseKeepsContentAndReplacesSeqAndTs() {
        FramePacer pacer = new FramePacer(1000);
        pacer.remember(frame(7L, "本拍增量"), T0);

        Map<String, Object> reused = pacer.reuse(8L);

        assertThat(reused.get("payload")).as("内容（差分量）原样保留").isEqualTo("本拍增量");
        assertThat(reused.get("seq")).as("seq 换成调用方要的那一拍").isEqualTo(8L);
        assertThat(reused.get("ts")).as("ts 换成本刻的墙钟（不是上一拍的）").isNotNull();
        assertThat(reused.get("seq")).isNotEqualTo(7L);
    }

    @Test
    @DisplayName("复用返回的是**副本**：改复用帧不会污染记住的那一份（下一次复用仍是干净的）")
    void reuseReturnsACopy() {
        FramePacer pacer = new FramePacer(1000);
        pacer.remember(frame(0L, "原始"), T0);

        Map<String, Object> first = pacer.reuse(1L);
        first.put("payload", "被改过");
        first.put("extra", "偷偷加的键");

        Map<String, Object> second = pacer.reuse(2L);
        assertThat(second.get("payload")).as("浅拷贝：改副本不动原型").isEqualTo("原始");
        assertThat(second).as("也没有多余的键").doesNotContainKey("extra");
    }

    @Test
    @DisplayName("节拍配 0（测试 / 抓一帧用）⇒ 窗口 0 ⇒ **每次都真算**")
    void zeroIntervalNeverReuses() {
        FramePacer pacer = new FramePacer(0);
        pacer.remember(frame(0L, "上一拍"), T0);

        assertThat(pacer.reusable(T0)).as("同一纳秒也不复用：窗口是 0").isFalse();
        assertThat(pacer.reusable(T0 + 1L)).isFalse();
    }

    @Test
    @DisplayName("负数节拍按 0 处理（不抛、也不变成负数窗口）")
    void negativeIntervalBehavesLikeZero() {
        FramePacer pacer = new FramePacer(-5);
        pacer.remember(frame(0L, "上一拍"), T0);
        assertThat(pacer.reusable(T0 + 1L)).isFalse();
    }

    private static Map<String, Object> frame(long seq, String payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("seq", seq);
        frame.put("ts", 1_760_000_000_000L);
        frame.put("payload", payload);
        return frame;
    }
}
