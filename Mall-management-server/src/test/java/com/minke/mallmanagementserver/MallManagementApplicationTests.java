package com.minke.mallmanagementserver;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MallManagementApplicationTests {

    /**
     * 全上下文启动冒烟测试。
     *
     * <p>当前被 {@code @Disabled} 关闭：本测试需要真实 MySQL 连接
     * （DataSource 自动配置 + {@code spring.jpa.hibernate.ddl-auto}），
     * 在无 MySQL 的 CI 环境中会失败并拖垮整个 {@code mvn test}。
     *
     * <p>之所以不改成 {@code @WebMvcTest} 切片：Web 层已有独立的 smoke 测试
     * （见 {@code smoke/} 包），改切片会与之重复，同时丢掉「完整上下文能否启动」
     * 这一独有覆盖。故保留原样、暂时禁用。
     *
     * <p>详见 docs/agent/tech-debt.md TD-10。
     * A3 阶段为 CI 增加 MySQL service 后，删除 {@code @Disabled} 重新启用。
     */
    @Disabled("TD-10：需要真实 MySQL 连接，CI 暂无 MySQL service。见 docs/agent/tech-debt.md TD-10")
    @Test
    void contextLoads() {
    }

}
