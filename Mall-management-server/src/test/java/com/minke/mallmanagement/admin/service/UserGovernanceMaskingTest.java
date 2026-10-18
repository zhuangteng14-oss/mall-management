package com.minke.mallmanagement.admin.service;

import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * D3-4 读侧接线：用户治理**列表**掩 {@code email}，{@code userRow}（审计快照）保持明文。
 *
 * <p>⚠️ 两条用例必须同时存在：只测「列表被掩」的话，把 {@code userRow} 也接上掩码同样全绿；
 * 而 {@code userRow} 是封禁 / 降级审计的 {@code before} 快照，掩了它就等于把证据改成摘要。
 */
@ExtendWith(MockitoExtension.class)
class UserGovernanceMaskingTest {

    @Mock
    private UserGovernanceDao dao;

    @Test
    @DisplayName("listUsers：email 出列表时是掩码，源行不被改写")
    void listUsersMasksEmail() {
        Map<String, Object> row = new HashMap<>();
        row.put("username", "alice");
        row.put("email", "alice@example.com");
        row.put("banned", 0);
        when(dao.selectUsers("ali", 100, 0)).thenReturn(List.of(row));
        when(dao.countUsers("ali")).thenReturn(1L);

        Map<String, Object> result = new UserGovernanceService(dao).listUsers("ali", 100, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("email")).isEqualTo("a***@example.com");
        assertThat(list.get(0).get("username")).isEqualTo("alice");
        assertThat(row.get("email")).as("DAO 返回的源行必须保持明文").isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("userRow：审计快照保持明文（不接掩码）")
    void userRowStaysPlaintext() {
        Map<String, Object> row = new HashMap<>();
        row.put("username", "alice");
        row.put("email", "alice@example.com");
        when(dao.selectUserRow("alice")).thenReturn(row);

        Map<String, Object> result = new UserGovernanceService(dao).userRow("alice");

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) result.get("user");
        assertThat(user.get("email")).isEqualTo("alice@example.com");
    }
}
