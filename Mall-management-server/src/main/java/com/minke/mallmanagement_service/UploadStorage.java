package com.minke.mallmanagement_service;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 上传落盘的公共入口 —— 三处上传点（goodsAdd / goodsUp / updateAvatar）共用。
 *
 * <p>【为什么是静态工具类，不是 {@code @Service}】
 * 本仓的 smoke 测试是 {@code @WebMvcTest} 切片，不导入 {@code @Service}/{@code @Component} ——
 * 见 {@code MallManagementGoodsController} 里关于 OrderStateMachine / OrderTransitionRecorder
 * 的注释（那两个 bean 缺席会让切片在<b>上下文加载期</b>就红，故 AbstractSmokeTest 必须给替身）。
 * 落盘是无状态的纯函数，做成 bean 只会平白多一个需要替身的依赖点 ⇒ 用静态方法，
 * 对 Spring 上下文的影响为零。
 *
 * <p>【本版只做「搬运」，不改同名语义】
 * 三处上传点原先各自复制了同一段「算路径 + 已存在则跳过 + transferTo」。本类把它收敛到一处，
 * 但<b>逐字保留</b>那段语义（含它的缺陷），行为不变。
 *
 * <p>⚠️ 抛出的 {@link IOException} 由调用方决定吞还是冒：三处现状<b>刻意不一致</b>
 * （TD-07：{@code goodsUp} 不 catch，另两处吞），本版不替它们统一。
 */
public final class UploadStorage {

    private UploadStorage() {
    }

    /**
     * 落库用的文件名 —— 即 URL 尾段，也即磁盘上的文件名。
     *
     * <p>⚠️ 当前实现是<b>直通</b>（客户端给什么就是什么）。它是同名策略的<b>唯一入口</b>：
     * 名字与内容是不是同一回事、冲突时改不改名，都只在这一处决定。现状直通，正是
     * {@code defect-survey.md} 病 B「同名文件静默跳过、DB 照样记 URL」的根因
     * —— 文件名被当成了图片的身份。
     */
    public static String storedName(MultipartFile file) {
        return file.getOriginalFilename();
    }

    /**
     * 按<b>当前</b>同名语义落盘：目标已存在则<b>不写</b>，直接复用磁盘上那个文件。
     *
     * <p>⚠️ 该语义对三种「同名」一视同仁，其中两种是错的 —— 见 {@link #storedName}。
     * 本版刻意保留，改法在下一笔。
     *
     * @param uploadDir 上传根目录（调用方已 normalize 的绝对路径）
     * @param name      {@link #storedName} 的返回值
     */
    public static void store(Path uploadDir, String name, MultipartFile file) throws IOException {
        File target = uploadDir.resolve(name).toFile();
        if (!target.exists()) {
            file.transferTo(target);
        }
    }
}
