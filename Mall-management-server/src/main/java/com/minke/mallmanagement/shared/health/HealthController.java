package com.minke.mallmanagement.shared.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库身份接口 —— A3 前置校验 P5/P6/P7 的支撑设施。
 *
 * 【为什么需要它】
 * 加缓存之前，「HTTP 读回来的内容 == 库里的内容」是成立的，A3 的 P5（就绪探测）
 * 与 P6（哨兵往返）都建立在这个假设上。B1 给 goodstype 加缓存后该假设破裂：
 *   - P5 打 GET /goods/goodstype（B1 当时前缀为 /mysqlGoods，2026-10-02 笔 2 规范化），缓存命中时一次 DB 都不碰 →
 *     它不再能证明「Hikari/MySQL 可用」，退化成「Web 层可用」；
 *   - P6 用裸 JDBC 直写哨兵行、再经 HTTP 读回，而缓存返回的是填充时刻的旧列表 →
 *     哨兵行必然读不到 → 结构性 FAIL（不是偶发，重跑多少次都一样）。
 * 故改为「让后端自己报它连的是哪个库实例」，与脚本 JDBC 侧看到的身份对比。
 * 本接口不返回任何可能被缓存的数据，是「防缓存污染接口」。
 *
 * 【仅 a3 profile 生效】
 * 它会暴露 @@hostname / VERSION() 等基础设施信息，不应在生产可达；A3 本就以 a3
 * profile 启动，故不影响。若 profile 未生效，A3 侧拿到 404 → Spring 默认错误体
 * （{timestamp,status,error,path}）不含 "success":true → P5 明确 FAIL，不会假绿。
 *
 * ⚠️ 绝不要给本方法加 @Cacheable —— 它自身就是防缓存污染的设施。
 */
@RestController
@RequestMapping("/health")
@Profile("a3")
public class HealthController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/db")
    public Map<String, Object> db() {
        // @@server_uuid 是纯 GLOBAL 变量（MySQL 拒绝以 @@session 读取），
        // 故同一实例上任何连接、任何用户取到的值必然相同 —— 脚本侧与后端侧一致
        // 是变量语义决定的必然，不靠巧合。@@hostname 是 gethostname()，克隆 VM 可能
        // 保留原主机名，故只作辅判据。DATABASE() 与 @@port 在两台机器上必然相同，
        // 判别力为零，只作参考。
        String sql = "SELECT @@server_uuid AS serverUuid, @@hostname AS hostname, "
                + "DATABASE() AS `database`, @@port AS port, VERSION() AS version";
        Map<String, Object> row = jdbcTemplate.queryForMap(sql);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);   // A3 的 P5 就绪判据依赖它
        result.putAll(row);
        return result;
    }
}
