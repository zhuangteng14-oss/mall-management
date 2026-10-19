package com.minke.mallmanagement.admin.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.admin.service.UserGovernanceService;
import com.minke.mallmanagement.merchant.service.MerchantApplicationService;
import com.minke.mallmanagement.merchant.service.MerchantDowngradeService;
import com.minke.mallmanagement.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「查看明文」端点的两条判据：<b>返回的是明文</b>，且<b>每看一次都留一条审计</b>。
 *
 * <p>⚠️ 只测 Service 不够：{@code userRow} 本来就是明文（审计快照要它），真正容易漏的是
 * Controller 这一层的挂钩 —— 有人把审计那行删掉，列表仍是掩码、详情仍是明文，页面看起来
 * 完全正常，而「谁看过明文」这条证据已经没了。故这里直接断言 {@code auditService.record}
 * 的五个实参（动作 tag / TargetType / TargetId / before / after）。
 */
@ExtendWith(MockitoExtension.class)
class AdminAccountsPlaintextDetailTest {

    @Mock
    private UserGovernanceService governanceService;
    @Mock
    private MerchantApplicationService applicationService;
    @Mock
    private MerchantDowngradeService downgradeService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditService auditService;

    private AdminAccountsController controller() {
        return new AdminAccountsController(governanceService, applicationService,
                downgradeService, notificationService, auditService);
    }

    @Test
    @DisplayName("user/detail：原样返回明文 email，并写 user.viewplaintext 审计（after=整行）")
    void detailReturnsPlaintextAndWritesAudit() {
        Map<String, Object> row = new HashMap<>();
        row.put("username", "alice");
        row.put("email", "alice@example.com");
        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("success", true);
        serviceResult.put("user", row);
        when(governanceService.userRow("alice")).thenReturn(serviceResult);

        Map<String, Object> result = controller().userDetail("alice", new MockHttpServletRequest());

        assertThat(result).containsEntry("success", true);
        assertThat(result.get("user")).isSameAs(row);
        @SuppressWarnings("unchecked")
        Map<String, Object> returned = (Map<String, Object>) result.get("user");
        assertThat(returned.get("email")).isEqualTo("alice@example.com");
        verify(auditService).record(isNull(), eq(AuditActions.USER_VIEW_PLAINTEXT),
                eq("user"), eq("alice"), isNull(), same(row));
    }

    @Test
    @DisplayName("user/detail：用户不存在 ⇒ success=false，且**不写审计**（什么都没看到）")
    void missingUserDoesNotWriteAudit() {
        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("success", false);
        serviceResult.put("msg", "用户不存在");
        when(governanceService.userRow("nobody")).thenReturn(serviceResult);

        Map<String, Object> result = controller().userDetail("nobody", new MockHttpServletRequest());

        assertThat(result).containsEntry("success", false).containsEntry("msg", "用户不存在");
        verify(auditService, never()).record(org.mockito.ArgumentMatchers.any(),
                eq(AuditActions.USER_VIEW_PLAINTEXT), eq("user"), eq("nobody"), isNull(),
                org.mockito.ArgumentMatchers.any());
    }
}
