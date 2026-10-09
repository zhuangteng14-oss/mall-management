package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「上传目录里没有图时，**应用自带**的图仍要能服务」（2026-10-09）。
 *
 * <p>【为什么单开一个类，而不是往 {@link ImgMappingSmokeTest} 里加用例】
 * 那个类跑在 smoke profile 的 {@code app.upload-dir=src/main/resources/static/img/} 上 ——
 * 上传目录**恰好就是**自带图所在目录 ⇒ 两支 location 指向同一批文件，**测不出**兜底在不在。
 * 本类用 {@link TestPropertySource} 把上传目录改成一个**空目录**（内联属性优先级高于 profile
 * 属性，见 {@code application-smoke.properties} 块首那条），复现 a3 profile 的真实条件：
 * {@code app.upload-dir=${java.io.tmpdir}/a3-uploads/} —— 那个目录存在但是空的。
 *
 * <p>【它钉住的失效】2026-10-09 实测：a3 profile 的 8088 上 {@code /img/Mall-Section-2.jpg}
 * 与 {@code /img/default_user.png} **全 404**（{@code ImgPath} 当时只注册上传目录一个位置）
 * ⇒ 登录页品牌大图只剩 {@code background-color} 兜底，看着像「图没画出来」。修法是给
 * {@code /img/**} 补一个 {@code classpath:/static/img/} 兜底；本类即那条修复的回归基线。
 *
 * <p>两条用例缺一不可：只有「兜底在」会漏掉「兜底把上传的图顶掉了」—— 那是相反方向的坏。
 */
@TestPropertySource(properties = "app.upload-dir=target/test-upload-dir-fallback/")
class ImgMappingClasspathFallbackTest extends AbstractSmokeTest {

    /** 与 {@code ImgMappingSmokeTest} 同款夹具：static/img/ 下唯一被业务语义引用的文件。 */
    private static final String KNOWN_FILE = "default_user.png";

    /** 与上面那条 {@code TestPropertySource} 逐字对应（同一个目录，一处改两处核）。 */
    private static final Path UPLOAD_DIR = Paths.get("target/test-upload-dir-fallback");

    /**
     * 清掉「上传目录优先」那条用例写进去的夹具。
     *
     * <p>⚠️ 必须清：它同名于 classpath 里那张真图，留着会让下一条用例（断言兜底生效）
     * 因为**错误的理由**通过 —— 那正是本类要防的那种假绿。
     */
    @AfterEach
    void removeUploadFixture() throws Exception {
        Files.deleteIfExists(UPLOAD_DIR.resolve(KNOWN_FILE));
    }

    /**
     * 正断言：上传目录是空的，自带图**照样 200 且响应体非空**（走 classpath 兜底）。
     * 反断言：不存在的文件仍 404 —— 防「无差别兜底」造成的假绿（同 {@code ImgMappingSmokeTest} 的理由）。
     */
    @Test
    void classpath_image_is_served_when_upload_dir_is_empty() throws Exception {
        // 前置：夹具在 classpath（即 target/classes）里必须在，且上传目录里必须**不在**
        // —— 两件都是本条用例的成立前提，先查，免得拿一个「上传目录里正好有」的假象当结论。
        assertThat(Files.exists(Paths.get("src/main/resources/static/img/", KNOWN_FILE)))
                .as("自带图夹具不在源码目录：%s", KNOWN_FILE)
                .isTrue();
        assertThat(Files.exists(UPLOAD_DIR.resolve(KNOWN_FILE)))
                .as("上传目录里不该有同名夹具（上一个用例没清干净？）：%s", UPLOAD_DIR)
                .isFalse();

        MvcResult served = mockMvc.perform(get("/img/" + KNOWN_FILE))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray())
                .as("兜底来的响应体不应为空")
                .isNotEmpty();

        mockMvc.perform(get("/img/nonexistent-classpath-fallback-fixture.png"))
                .andExpect(status().isNotFound());
    }

    /**
     * 优先级：上传目录与 classpath **同名**时，服务的必须是**上传目录**那份。
     *
     * <p>判据用「响应体逐字节等于我写进去的内容」而不是「200」：两者都会 200，
     * 只有比字节才分得清谁赢了。
     */
    @Test
    void upload_dir_file_wins_over_classpath_same_name() throws Exception {
        byte[] mine = "NOT-A-REAL-IMAGE-just-bytes".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(UPLOAD_DIR);
        Files.write(UPLOAD_DIR.resolve(KNOWN_FILE), mine);

        MvcResult served = mockMvc.perform(get("/img/" + KNOWN_FILE))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray())
                .as("同名时应答上传目录那一份（locations 有序，前者优先）")
                .isEqualTo(mine);
    }
}
