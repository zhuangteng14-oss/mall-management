package com.minke.mallmanagement.admin.service;

import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户治理（C3 块 2 用户块）—— 管理端「用户治理」页的读 + 封禁 / 解封写。
 *
 * <h2>【⚠️ 为什么封禁只改 {@code user} 表，不动 {@code login}】</h2>
 * A 档裁决 3：封禁 = **只标 {@code user.banned}**，登录闸与请求闸**双查**它。
 * 不去改（更不删）{@code login} 行 —— 那会让「解封」变成不可能（凭据已经没了），
 * 也会让「谁被封过」这段历史随全量重导消失。封禁是**状态**，不是**删除**（规则 v2）。
 *
 * <h2>【⚠️ 封禁列不在 {@code userPublic} 缓存的 5 列里 ⇒ 不需要清缓存】</h2>
 * 那 5 列是 {@code customerid / merchantid / username / usertype / gender}
 * （见 {@code UserDao.updateUserProfile} 的论证）。{@code banned} 不在其中 ⇒
 * 本类的两个写方法**都**不调 {@code evictPublic}。⚠️ 将来若把 {@code banned}（或任何封禁列）
 * 加进那份缓存负载，这里必须同批加失效点 —— 否则「刚封的人还能读到自己是正常的」到 TTL 为止。
 */
@Service
public class UserGovernanceService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    private final UserGovernanceDao userGovernanceDao;

    public UserGovernanceService(UserGovernanceDao userGovernanceDao) {
        this.userGovernanceDao = userGovernanceDao;
    }

    /** 用户列表（{@code keyword} 可空）。 */
    public Map<String, Object> listUsers(String keyword, Integer limit) {
        int effective = normalizeLimit(limit);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", userGovernanceDao.selectUsers(keyword, effective));
        result.put("total", userGovernanceDao.countUsers(keyword));
        result.put("limit", effective);
        return result;
    }

    /** 单账号整行（供 Controller 取审计 {@code before} 快照，也供前端「治理」弹窗预填）。 */
    public Map<String, Object> userRow(String username) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> row = isBlank(username) ? null : userGovernanceDao.selectUserRow(username);
        result.put("success", row != null);
        if (row != null) {
            result.put("user", row);
        } else {
            result.put("msg", "用户不存在");
        }
        return result;
    }

    /**
     * 封禁。{@code reason} **必填** —— 它落 {@code banReason}，而那一列会在
     * {@code /user/me} 的返回里带出去（{@code UserDao.selectUserByUsername} 是全仓唯一
     * {@code SELECT *}）⇒ 等于**写给被封的人看**。留空会让被封者只看到「你被封了」而没有理由。
     * （与 {@code DowngradeReason} 同口径，用户已裁决「保留，不剥」。）
     */
    public Map<String, Object> ban(String username, String reason) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(username)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        if (isBlank(reason)) {
            result.put("success", false);
            result.put("msg", "封禁必须填写理由（该理由会展示给用户）");
            return result;
        }
        int affected = userGovernanceDao.updateBan(username, true, reason, LocalDateTime.now());
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "用户不存在");
            return result;
        }
        result.put("success", true);
        result.put("username", username);
        return result;
    }

    /** 解封。**幂等**：对未封禁的账号也答 {@code success:true}（{@code updated} 仍会 ≥1，因为 UPDATE 不带条件）。 */
    public Map<String, Object> unban(String username) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(username)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        int affected = userGovernanceDao.updateBan(username, false, null, null);
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "用户不存在");
            return result;
        }
        result.put("success", true);
        result.put("username", username);
        return result;
    }

    // ==================== 内部 ====================

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
