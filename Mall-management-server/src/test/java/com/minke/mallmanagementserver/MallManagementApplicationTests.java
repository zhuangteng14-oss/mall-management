package com.minke.mallmanagementserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 全上下文启动冒烟 + 数据库可达性。
 *
 * <p>两个测试都由环境变量开关控制：仅当 {@code A3_ENABLED=true} 时执行，其余情况
 * （含 {@code backend} job 的全量 {@code mvn test}）自动跳过 —— 跳过不是失败，故
 * backend job 仍保持零 MySQL。开关放在<b>类上</b>而非各方法上：将来新增方法不会漏。
 *
 * <p>为何不是 {@code @Disabled}：那会让「有 MySQL 时也不跑」成为永久状态 ——
 * 想启用必须改代码。换成环境开关后，CI 里那个<b>已有 MySQL service 的
 * concurrency job</b> 只需加一个 env 就能真跑起来。
 *
 * <p>之所以不改成 {@code @WebMvcTest} 切片：Web 层已有独立的 smoke 测试
 * （见 {@code smoke/} 包），改切片会与之重复，同时丢掉「完整上下文能否启动」
 * 这一独有覆盖。故保留 {@code @SpringBootTest}（默认 {@code webEnvironment=MOCK}，
 * 不真起 Tomcat，也就不与同 job 里跑着的后端抢 8088）。
 *
 * <p>连接目标由 {@code application.properties}（默认 profile）决定：
 * {@code localhost:3306/mall_management_sql}，root/123456 —— 与 concurrency job 的
 * MySQL service 及库名恰好吻合（库由 {@code mall_management_sql.sql} 自建）。
 * 刻意<b>不</b>在此加 {@code SPRING_PROFILES_ACTIVE=a3}：a3 profile 的默认值指向
 * VM 的 192.168.229.129，那是给「启动被测后端」用的，不是给本测试用的。
 *
 * <p>TD-10 至此关闭，理由见 docs/agent/tech-debt.md（同日更新）。
 */
@EnabledIfEnvironmentVariable(named = "A3_ENABLED", matches = "true")
@SpringBootTest
class MallManagementApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 全上下文启动冒烟。
     *
     * <p>⚠️ 它<b>只</b>证明「完整 Spring 上下文能启动」，<b>不</b>证明能连数据库。
     * 依据是 2026-09-20 的三个探针：库名不存在（{@code Unknown database}）、乃至端口
     * 无监听（{@code Connection refused}）时，本方法<b>依然 PASS</b>。成因有二：
     * {@code spring.jpa.hibernate.ddl-auto=update} 的 schema 管理是「尽力而为」（失败
     * 只记 ERROR 日志、不终止启动），且本项目无 {@code @Entity}（TD-01：JPA 空转），
     * 本就无 schema 可导出 —— 两条叠加，启动路径上没有任何一处「连不上就必须失败」。
     *
     * <p>它仍有独立价值：smoke 测试是 {@code @WebMvcTest} 切片，从不启动完整上下文。
     * 数据库可达性由 {@link #databaseIsReachable()} 承担，别把两者混为一谈。
     */
    @Test
    void contextLoads() {
    }

    /**
     * 数据库可达性：真发一条查询，而不是「上下文起得来就算数」。
     *
     * <p>本方法存在的唯一理由就是上一条的 ⚠️：若无它，库不存在 / 连不上时整个类
     * 依然 PASS，于是「需真实 MySQL」这句话是虚的 —— 属「断言落空」那一族
     * （参见 tech-debt TD-15、context-pack「元教训」）。
     *
     * <p>刻意<b>不</b>断言 {@code VERSION()} 前缀：本地开发库是 8.0.32，断言 8.4 会让
     * 本测试在本地变红，而环境契约不是它的职责 —— 版本门禁由 A3 脚本的 P1
     * （{@code VERSION()} 必须以 {@code 8.4.} 开头，硬门禁）承担。
     */
    @Test
    void databaseIsReachable() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertNotNull(one, "SELECT 1 返回 null");
        assertEquals(1, one.intValue());
    }

}
