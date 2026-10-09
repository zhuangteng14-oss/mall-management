package com.minke.mallmanagement.user.service;

import com.minke.mallmanagement.shared.web.UserStateLookup;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link UserStateLookup} 的唯一实现（C3 块 2 用户块）—— 把 {@link UserGovernanceDao}
 * 的四条读包上**fail-open** 与**一条 ASCII 标记的 WARN**，供封禁第二道闸、下单冻结闸与 self 判据共用。
 *
 * <h2>【⚠️ 为什么整个类只有 try/catch + 一个 log，没有别的】</h2>
 * 见 {@link UserStateLookup} 类注释：「封禁 / 冻结不是安全边界」⇒ 读失败时**放行**而不是拒绝。
 * 若这里把异常放出去，一个暂时连不上的库会同时打翻**所有登录**（闸链上游）与**所有下单**
 * （订单域）—— 把可观测的小降级放大成全局不可用。所以四个方法一律 catch 一切、返回
 * {@code false} / {@code null}、记 WARN。
 *
 * <p>⚠️ <b>日志标记用 ASCII {@code USERSTATE}</b>：本仓日志是 GBK 字节，中文 pattern 检索不到
 * （同 {@code AuditService} 的 {@code AUDIT}、{@code NotificationService} 的 {@code NOTIFY}）。
 * 运维口径：搜 {@code WARN} + {@code USERSTATE}，命中即说明「有一段时间封禁/冻结判据是失效的」。
 *
 * <p>⚠️ <b>本类刻意不加 {@code @Transactional}</b>：三条都是单条读、自动提交，
 * 加事务只增代理与边界；且它被 {@code AuthInterceptor}（一个**非 Spring bean**，
 * 见 {@link com.minke.mallmanagement.shared.web.WebAuthConfig}）经接口调用，
 * 加事务会带来一个「闸每请求过一次代理」的隐性成本，收益为零。
 */
@Service
public class UserStateLookupService implements UserStateLookup {

    private static final Logger log = LoggerFactory.getLogger(UserStateLookupService.class);

    /** {@code login.Role} 的商家取值 —— 与 {@code UserService.businessId} 的选列判据同源（那是 2）。 */
    private static final int ROLE_MERCHANT = 2;

    private final UserGovernanceDao dao;

    public UserStateLookupService(UserGovernanceDao dao) {
        this.dao = dao;
    }

    @Override
    public boolean isBanned(String userId, Integer role) {
        if (userId == null) {
            return false;   // 没有业务号 = 无事可判（闸侧此时通常已经是未登录，走不到这里）
        }
        try {
            return dao.isBannedByBusinessId(userId, role != null && role == ROLE_MERCHANT);
        } catch (Exception e) {
            log.warn("封禁判据读取失败（USERSTATE）：userId={} role={} —— 本次按未封禁放行", userId, role, e);
            return false;
        }
    }

    @Override
    public boolean isBannedByUsername(String username) {
        if (username == null) {
            return false;
        }
        try {
            return dao.isBannedByUsername(username);
        } catch (Exception e) {
            log.warn("封禁判据读取失败（USERSTATE）：username={} —— 本次按未封禁放行", username, e);
            return false;
        }
    }

    @Override
    public String usernameOf(String userId, Integer role) {
        if (userId == null) {
            return null;   // 没有业务号 = 反查不出登录名（闸侧此时通常已经是未登录）
        }
        try {
            return dao.selectUsernameByBusinessId(userId, role != null && role == ROLE_MERCHANT);
        } catch (Exception e) {
            // ⚠️ 与上面三条同一条纪律：读失败**回 null**（= 判不出来），不抛 —— 但这一处的
            //    代价要写下来：`IdentityGuard.requireSelfOrAdminByUsername` 对 null 是**放行**，
            //    故库不可达时这三个端点退化成「不校验入参身份」（与封禁闸失效同一类降级）。
            log.warn("业务号反查登录名失败（USERSTATE）：userId={} role={} —— 本次按判不出来放行", userId, role, e);
            return null;
        }
    }

    @Override
    public boolean isMerchantFrozen(String merchantId) {
        if (merchantId == null) {
            return false;
        }
        try {
            return dao.isMerchantFrozen(merchantId);
        } catch (Exception e) {
            log.warn("降级冻结判据读取失败（USERSTATE）：merchantId={} —— 本次按未冻结放行", merchantId, e);
            return false;
        }
    }
}
