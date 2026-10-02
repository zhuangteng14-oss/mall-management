package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 静态资源 smoke test：GET /img/**
 *
 * <p>验证 ImgPath 注册的 {@code /img/**} → 本地目录映射可用。
 * 该映射即 TD-05（静态资源路径依赖 user.dir）修复后由 app.upload-dir 驱动的部分，
 * 本测试同时为 TD-09（jar 部署场景验证）准备回归基线。
 *
 * <p>选用 {@code default_user.png} 作为夹具：它是 static/img/ 下唯一被业务语义
 * 引用的文件（loginAdd 的 image 参数默认值，见 api-contract.md），最不可能被删除。
 */
class ImgMappingSmokeTest extends AbstractSmokeTest {

    private static final String KNOWN_FILE = "default_user.png";
    private static final String UPLOAD_DIR = "src/main/resources/static/img/";

    /**
     * 正断言：已存在文件返回 200 且响应体非空。
     * 反断言：不存在的文件返回 404。
     *
     * <p>两条断言放在同一用例内，是为了防止「映射把所有路径都兜底成成功」造成假绿：
     * 只测 200 无法区分「映射正确」与「映射无差别命中」。
     */
    @Test
    void img_mapping_serves_known_file() throws Exception {
        // 前置检查：夹具必须在磁盘上存在。app.upload-dir 是相对路径，
        // 若工作目录不是 Mall-management-server，此处会给出可操作的失败信息，
        // 而不是让后面的 404 伪装成「映射坏了」。
        Path fixture = Paths.get(UPLOAD_DIR, KNOWN_FILE);
        assertThat(Files.exists(fixture))
                .as("测试夹具不存在：%s。app.upload-dir 为相对路径，"
                        + "请以 Mall-management-server 为工作目录运行测试"
                        + "（见 agent.md：cd Mall-management-server && mvn test）",
                        fixture.toAbsolutePath().normalize())
                .isTrue();

        // 正断言
        MvcResult served = mockMvc.perform(get("/img/" + KNOWN_FILE))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray())
                .as("响应体不应为空")
                .isNotEmpty();

        // 反断言
        mockMvc.perform(get("/img/nonexistent-smoke-fixture.png"))
                .andExpect(status().isNotFound());
    }
}
