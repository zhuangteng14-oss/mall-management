package com.minke.mallmanagement.shared.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 全局 CORS 装配（<b>CORS 收窄笔，2026-10-06，登记见 progress.md §5-AH</b>）。
 *
 * <p><b>本笔只做一件事：把「允许哪些源（origin）」从通配收成字面量白名单。</b>
 * 其余四项（allowedHeader / allowedMethod / allowCredentials / maxAge）<b>逐字未动</b> ——
 * 理由见下方「为什么只收 origin」。
 *
 * <h2>【病：为什么必须收】</h2>
 * 原文是 {@code addAllowedOriginPattern("*")} + {@code setAllowCredentials(true)} 注册在
 * {@code /**}：<b>任意站点</b>都能让浏览器带着用户的 cookie（本项目 = {@code JSESSIONID}
 * 会话，见 {@code AuthSession}）发请求、<b>并读到响应体</b>。这是「合法且最宽」而非「配错」——
 * 它当年是**为了让 8080 的 dev server 真跨源打 8088** 才这么写的，但生产环境不能是这个形状。
 * 登记处：{@code defect-survey.md:102}（病 A 表）、{@code tech-debt.md} TD-04。
 *
 * <h2>【口径：字面量白名单，通配在机制上不可能】</h2>
 * 用 {@code addAllowedOrigin(String)} 逐条添加（<b>不是</b> {@code addAllowedOriginPattern}）⇒
 * 精确匹配，写不出通配 —— <b>不靠纪律，靠机制</b>。配置项：
 * <pre>
 *   app.cors.allowed-origins=http://localhost:8080,http://127.0.0.1:8080   （application.properties）
 *   APP_CORS_ALLOWED_ORIGINS=https://你的域名                              （prod：环境变量**替换**，不是追加）
 * </pre>
 *
 * <h2>【⚠️ 两种「空」含义不同 —— 别合并（用户 2026-10-06 明确区分）】</h2>
 * <ol>
 *   <li><b>完全空</b>（键没配 / 值为空串）⇒ <b>降级放行不了任何跨源 + WARN + 照常启动</b>：
 *       一次环境变量没设对，不该让整个服务起不来；且这是**安全的那一侧**（fail-closed）。</li>
 *   <li><b>非空但格式错</b>（含空项 / 某项含 {@code *}）⇒ <b>启动期抛 {@code IllegalStateException}</b>：
 *       这是配置写错了，越早炸越好 —— 同 {@code ImgPath.initUploadDir} 的「启动期炸，不会静默」
 *       （{@code application-a3.properties} 把该行为当范例记下）。</li>
 * </ol>
 *
 * <h2>【为什么只收 origin（不收 header / method）】</h2>
 * 收窄 header / method <b>挡不住</b>跨源攻击者 —— 能挡住他的是 <b>origin 被限</b>；而收窄这两项
 * 带来的是真回归面（{@code Authorization} 头、上传的 multipart、actuator 的 GET）。
 * 同理 {@code maxAge} 保持 3600：改它无收益，却会连带动摇 {@code progress.md §5-R (2)} 第 [13] 条
 * 真容器取证（那行记的就是 {@code Max-Age: 3600}）。
 *
 * <h2>【与登录闸（WebAuthConfig）的关系】</h2>
 * 两者<b>不冲突、不相干</b>：{@code WebAuthConfig} 只管鉴权（白名单 + 拦截器），零 CORS 内容。
 * 唯一交界面是<b>执行顺序</b>：{@code CorsFilter} 是 servlet Filter（先于 DispatcherServlet），
 * 拦截器后跑 ⇒ <b>预检（OPTIONS）在过滤器这一层就被应答，进不了闸</b>（真容器实证见
 * {@code progress.md §5-R (2)} 第 [13] 条）。
 */
@Configuration
public class ImgConfig {

    private static final Logger log = LoggerFactory.getLogger(ImgConfig.class);

    /**
     * 白名单的来源键。<b>默认值是空串（不是 localhost）—— 刻意 fail-closed</b>：
     * 任何读不到配置的上下文（切片、单元测试、误删配置的部署）都<b>不放行任何跨源</b>，
     * 而不是悄悄退回「开发用」那个宽松值。真实值写在 {@code application.properties}。
     */
    private static final String ALLOWED_ORIGINS = "${app.cors.allowed-origins:}";

    @Bean
    public CorsFilter corsFilter(@Value(ALLOWED_ORIGINS) String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        applyAllowedOrigins(config, allowedOrigins);   // ← 本笔唯一的行为改动（原为 addAllowedOriginPattern("*")）
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * 解析 + 校验白名单，并逐条装进 {@code config}；<b>同时把生效清单打进启动日志</b>。
     *
     * <p>⚠️ 启动期打印不是装饰：{@code progress.md §5-R (2)} 的附带发现 (i) 记的教训正是
     * 「开关没有启动期生效值打印 ⇒ 开没开只能从行为反推」。清单为空时<b>也打</b>（打 WARN），
     * 否则「一个都没配」与「配了但没生效」在日志里分不开。ASCII 片段 {@code CORS} 供 GBK 检索。
     */
    private static void applyAllowedOrigins(CorsConfiguration config, String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("CORS 白名单已装配（CORS）：0 条 [] —— 键 app.cors.allowed-origins 未配置 ⇒ 拒绝全部跨源请求（fail-closed 安全降级，进程照常启动）");
            return;
        }
        int count = 0;
        for (String part : raw.split(",", -1)) {
            String origin = part.trim();
            if (origin.isEmpty()) {
                throw new IllegalStateException(
                        "CORS 配置格式错：app.cors.allowed-origins 含空项（原值 = \"" + raw + "\"）"
                                + " —— 多半是多打了一个逗号；空项会让白名单含义不可读，故启动期直接失败");
            }
            if (origin.contains("*")) {
                throw new IllegalStateException(
                        "CORS 配置禁止通配：app.cors.allowed-origins 的某项含 '*'（" + origin + "）"
                                + " —— 本笔口径是字面量白名单（见 progress.md §5-AH），要新增域名就逐个写全");
            }
            config.addAllowedOrigin(origin);
            count++;
        }
        log.info("CORS 白名单已装配（CORS）：{} 条 {} —— 其余 origin 一律不返回 Access-Control-Allow-Origin", count, config.getAllowedOrigins());
    }
}
