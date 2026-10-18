package com.minke.mallmanagement.observe.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调度停摆闸（{@link ScheduledJobGate}，D3-1）—— 它只有一个判据：注入开着就返回 {@code true}。
 *
 * <p>⚠️ 本类**不**测七个 {@code @Scheduled} 是否真的跳过了：那要看每个任务自己有没有在
 * 第一行调它（落点分散在七个文件，任何一处漏掉都能让「停摆」名不副实）。这里只保证闸本身
 * 与共享配置是同一份状态。
 */
class ScheduledJobGateTest {

    @Test
    @DisplayName("jobDown 开 ⇒ 所有任务名都返回 paused；关 ⇒ 都放行")
    void pausedFollowsTheInjectionBit() {
        AttackSwitchConfig config = mock(AttackSwitchConfig.class);
        ScheduledJobGate gate = new ScheduledJobGate(config);

        when(config.jobsPaused()).thenReturn(false);
        assertThat(gate.paused("orderTimeoutCancel")).isFalse();

        when(config.jobsPaused()).thenReturn(true);
        assertThat(gate.paused("orderTimeoutCancel")).isTrue();
        assertThat(gate.paused("anyOtherJob")).as("闸不看名字，只看总开关").isTrue();

        when(config.jobsPaused()).thenReturn(false);
        assertThat(gate.paused("orderTimeoutCancel")).isFalse();
    }
}
