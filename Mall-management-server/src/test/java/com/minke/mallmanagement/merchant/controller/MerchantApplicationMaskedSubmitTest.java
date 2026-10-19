package com.minke.mallmanagement.merchant.controller;

import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.merchant.service.MerchantApplicationService;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3-4 写侧接线：提交入驻申请时，带 {@code *} 的 {@code contactPhone} / {@code shopAddress}
 * **必须在进入 Service 之前**被拒 —— 「带 {@code *} 必须失败」的判据是
 * {@code applicationService.apply} <b>一次都没被调用</b>，不是「回体里 success=false」。
 *
 * <p>⚠️ 反向用例（干净入参真的会到 Service，且原样、未被掩码）与拒绝用例成对：
 * 只测拒绝的话，把整个 {@code apply} 入口改成一概拒绝也全绿。
 *
 * <p>⚠️ 身份闸在这里是**放行**档：{@link HttpServletRequest} 的替身没有 token / 会话 ⇒
 * {@code AuthSession.identify} 回 {@code null} ⇒ {@code IdentityGuard} 按「闸关 / 白名单」
 * 那条纪律放行。本测试只验掩码闸，不验身份；身份闸另有 smoke。
 */
@ExtendWith(MockitoExtension.class)
class MerchantApplicationMaskedSubmitTest {

    @Mock
    private MerchantApplicationService applicationService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditService auditService;
    @Mock
    private ObjectProvider<UserStateLookup> userStateLookupProvider;
    @Mock
    private HttpServletRequest request;

    private MerchantApplicationController controller() {
        return new MerchantApplicationController(applicationService, notificationService,
                auditService, userStateLookupProvider);
    }

    @Test
    @DisplayName("contactPhone 带 * ⇒ 200 + success:false，Service 一次没被调用")
    void maskedContactPhoneIsRejected() {
        ResponseEntity<Object> response = controller().apply(
                "alice", "小铺", "LIC-1", "张三", "138****8000", "北京市朝阳区建国路1号",
                "img", null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("success")).isEqualTo(false);
        assertThat((String) body.get("msg")).contains("联系电话").contains("*");
        verify(applicationService, never()).apply(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("shopAddress 带 * ⇒ 200 + success:false，Service 一次没被调用")
    void maskedShopAddressIsRejected() {
        ResponseEntity<Object> response = controller().apply(
                "alice", "小铺", "LIC-1", "张三", "13800138000", "北京市朝***",
                "img", null, request);

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("success")).isEqualTo(false);
        assertThat((String) body.get("msg")).contains("店铺地址").contains("*");
        verify(applicationService, never()).apply(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("干净入参：原样到达 Service（读侧掩码不回流到写侧）")
    void cleanSubmissionReachesServiceUnchanged() {
        when(applicationService.apply("alice", "小铺", "LIC-1", "张三",
                "13800138000", "北京市朝阳区建国路1号", "img", false))
                .thenReturn(Map.of("success", false, "msg", "stub 拒绝，避免走通知/审计"));

        ResponseEntity<Object> response = controller().apply(
                "alice", "小铺", "LIC-1", "张三", "13800138000", "北京市朝阳区建国路1号",
                "img", null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(applicationService).apply("alice", "小铺", "LIC-1", "张三",
                "13800138000", "北京市朝阳区建国路1号", "img", false);
    }
}
