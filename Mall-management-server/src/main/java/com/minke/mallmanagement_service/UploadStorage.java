package com.minke.mallmanagement_service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

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
 * <p>【本版是同名策略的<b>唯一</b>入口】
 * {@code defect-survey.md} 病 B 的根因是「文件名被当成了图片的身份」：同名即同图，
 * 于是商家 B 传 {@code 1.jpg} 时文件不落盘、DB 却记下指向<b>商家 A 那张图</b>的 URL、
 * 还返回 success。本版把「同名」拆成两种并分别对待（策略 C）：
 * <ul>
 *   <li>同名<b>且</b>内容相同 —— 复用磁盘上那份，不重写；</li>
 *   <li>同名<b>但</b>内容不同 —— 改名另存，两条记录各自指向自己的图。</li>
 * </ul>
 *
 * <p>⚠️ 抛出的 {@link IOException} 由调用方决定吞还是冒：三处现状<b>刻意不一致</b>
 * （TD-07：{@code goodsUp} 不 catch，另两处吞）—— 本类不替它们统一，
 * 三处的 catch 由调用方自己写明（2026-09-30 起三处均已改为显式失败）。
 */
public final class UploadStorage {

    /** 改名后缀的十六进制位数，必须为偶数。8 hex = 32 bit ⇒ 撞名概率约 2^-32。 */
    private static final int HASH_CHARS = 8;

    private UploadStorage() {
    }

    /**
     * 按同名策略 C 落盘，返回<b>实际使用</b>的文件名 —— 即 URL 尾段，也即磁盘上的文件名。
     *
     * <p>⚠️ 调用方<b>必须</b>用返回值拼 URL，不能再拿 {@code file.getOriginalFilename()}
     * 自己拼：改名时入参名与落盘名不一致，用入参名拼出来的 URL 会指向别人的图
     * —— 那正是本笔要修的缺陷本身。
     *
     * <p>【为什么先比长度】{@code spring.servlet.multipart.max-file-size}（当前用默认 1MB）
     * 只约束<b>上传侧</b>的内存副本；磁盘上那个同名文件不受它约束，可以任意大。
     * 直接 {@code readAllBytes} 会把它整份读进堆。先比 {@code Files.size}（O(1)）
     * 能在绝大多数「明显不是同一个文件」的情况下省掉这次读。
     *
     * <p>【孤儿文件】改名后，被别人同名图占住的那个旧名字仍留在盘上。本方法
     * <b>不删任何文件</b> —— 接受孤儿，无 GC。理由：删文件比留文件危险，历史行
     * （{@code goods.GoodImg} / {@code order.Images}）可能还在引用它，而删除是不可逆的。
     *
     * <p>【列长余量】改名会加长文件名，而 URL 要落进 {@code goods.GoodImg char(100)} 与
     * {@code order.Images varchar(100)}。前缀 {@code http://localhost:8088/img/} 占 27 字符，
     * 加 {@code _} + 8 hex = 36 字符，留给原名约 60 字符。超长文件名本来就会打爆
     * {@code char(100)}（MySQL strict mode 下 ERROR 1406），本策略只是把门槛提前 9 个字符。
     * 若日后调大 {@code max-file-size} 以放行长文件名，<b>必须同时考虑这两列的长度</b>。
     *
     * @param uploadDir 上传根目录（调用方已 normalize 的绝对路径）
     * @param file      待落盘的上传文件
     * @return 实际落盘的文件名
     */
    public static String store(Path uploadDir, MultipartFile file) throws IOException {
        String requested = file.getOriginalFilename();
        // 只读一次：这份 byte[] 同时用于比对与写盘。
        // （不要先 transferTo 再回头读 —— DiskFileItem.write 会把临时文件改名带走，
        //   之后再 getInputStream 得到 FileNotFoundException。读→写安全，写→读才炸。）
        byte[] bytes = file.getBytes();

        Path target = uploadDir.resolve(requested);
        if (Files.exists(target) && !sameContent(bytes, target)) {
            // 同名不同内容 ⇒ 改名。内容寻址：同一份内容再传必然落在同一个新名上，天然幂等。
            target = uploadDir.resolve(disambiguated(requested, bytes));
            if (Files.exists(target) && !sameContent(bytes, target)) {
                // 32 bit 哈希撞名（约 2^-32）。宁可显式失败，也不静默覆盖别人的图。
                throw new IOException("改名后仍与既有文件冲突：" + requested);
            }
        }

        if (!Files.exists(target)) {
            Files.write(target, bytes);
        }
        return target.getFileName().toString();
    }

    /**
     * 长度预筛 + 逐字节比对。
     *
     * <p>先比长度：省掉对「磁盘上那个可能很大的文件」的整份读取（见 {@link #store}）。
     */
    private static boolean sameContent(byte[] bytes, Path existing) throws IOException {
        if (bytes.length != Files.size(existing)) {
            return false;
        }
        return Arrays.equals(bytes, Files.readAllBytes(existing));
    }

    /**
     * 在扩展名之前插入内容哈希：{@code 色款.jpg} → {@code 色款_1a2b3c4d.jpg}。
     *
     * <p>无扩展名（{@code README}）、或点开头（{@code .gitignore} 这类隐藏文件）时整个当茎，
     * 故用 {@code dot > 0} 而非 {@code dot >= 0}。
     */
    private static String disambiguated(String name, byte[] bytes) {
        int dot = name.lastIndexOf('.');
        boolean hasExt = dot > 0;
        String base = hasExt ? name.substring(0, dot) : name;
        String ext = hasExt ? name.substring(dot) : "";
        return base + "_" + contentHash(bytes) + ext;
    }

    /** SHA-256 摘要的前 {@link #HASH_CHARS} 个十六进制字符（小写）。 */
    private static String contentHash(byte[] bytes) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 必备算法（SUN provider 内置），不可能走到这里。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        StringBuilder sb = new StringBuilder(HASH_CHARS);
        for (int i = 0; sb.length() < HASH_CHARS; i++) {
            sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return sb.toString();
    }
}
