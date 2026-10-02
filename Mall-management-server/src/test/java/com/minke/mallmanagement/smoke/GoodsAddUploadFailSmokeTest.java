package com.minke.mallmanagement.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 落盘失败的显式化：POST /goods/goodsAdd 在图片写不进磁盘时**必须**
 * 返回 {@code {success:false, msg}} 且在**写库之前**返回（TD-07 的「三处不一致」收尾）。
 *
 * <p>【怎么制造「必然写不进去」】把 {@code app.upload-dir} 指到一个<b>父路径是文件</b>的
 * 目录：{@code Mall-management-server/pom.xml/nope-img/}。{@code pom.xml} 是文件，其下
 * 不可能建目录 ⇒ {@code Files.write} 稳定失败，<b>不依赖权限、不依赖平台</b>
 * （比 chmod 只读目录可靠：Windows 上后者对同用户常常无效）。
 * 路径写相对值即可 —— 测试的工作目录就是 {@code Mall-management-server}，
 * 与 {@code application-smoke.properties} 里 {@code app.upload-dir=src/main/resources/static/img/}
 * 用的是同一套相对基准。
 *
 * <p>【为什么这个断言值钱】原实现吞掉异常继续写库：DB 记下一个指向不存在文件的 URL、
 * 前端弹「成功」—— 静默脏数据比 500 更难查。本用例把「早退 + 不碰库」两条一起钉死：
 * 只钉响应体的话，将来有人把 DB 写入挪到落盘之前，用例不会红。
 *
 * <p>本类自带 {@code @TestPropertySource} ⇒ 与其它 smoke 用例<b>不共享上下文</b>（各自缓存一份），
 * 这是刻意付的成本：覆盖上传目录这件事只对本用例成立。
 */
@TestPropertySource(properties = "app.upload-dir=pom.xml/nope-img/")
class GoodsAddUploadFailSmokeTest extends AbstractSmokeTest {

    @Test
    @DisplayName("图片落盘失败：显式 success:false + msg，且一行 JDBC 都没执行")
    void goodsAddUploadFailureReturnsExplicitFailureBeforeTouchingDb() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "1.jpg", "image/jpeg", "IMG".getBytes());

        mockMvc.perform(request(multipart("/goods/goodsAdd")
                        .file(file)
                        .param("MerchantId", "SMOKE-M1")
                        .param("GoodName", "smoke-goods")
                        .param("Price", "1.0")
                        .param("GoodType", "smoke-type")
                        .param("ProductCode", "SMOKE-P1")
                        .param("GoodTypeNew", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                // 三处前端都是 if (data.success) … else alert('失败：' + data.msg)
                // ⇒ 失败分支必须带 msg，否则前端弹「失败：undefined」。
                .andExpect(jsonPath("$.msg").value("商品图片保存失败"));

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }
    /**
     * URL 契约防回归（2026-10-02 笔 2）：旧路径必须**不再有 handler**。
     *
     * <p>为什么不能只断言「新路径通」：那样把旧映射一并留着也照样绿 —— 「换了前缀」这件事就
     * 没有可观测证据。两个断言合起来才等价于「旧的不在、新的在」。
     */
    @Test
    void oldPrefixHasNoHandlerAnyMore() throws Exception {
        mockMvc.perform(request(multipart("/mysqlGoods/goodsAdd")
                        .file(new MockMultipartFile(
                                "file", "1.jpg", "image/jpeg", "IMG".getBytes()))))
                .andExpect(status().isNotFound());
    }

}
