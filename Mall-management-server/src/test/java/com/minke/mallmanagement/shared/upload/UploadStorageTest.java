package com.minke.mallmanagement.shared.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UploadStorage} 同名策略 C 的行为契约。**无 Spring**：{@code store} 是无状态静态函数，
 * 用 {@link MockMultipartFile} + {@link TempDir} 直接驱动，不加载上下文
 * （故本类与 {@code smoke/} 那批分开，也不受 {@code A3_ENABLED} 门控）。
 *
 * <p>守的是策略 C 的两条分支与一条被刻意接受的弱项：
 * <ol>
 *   <li><b>同名同内容 → 复用</b>（{@link #sameNameSameContentReusesExistingWithoutRewrite}）——
 *       钉「不重写」。用 mtime 判定，不靠推断：{@code Files.write} 必然更新 mtime，
 *       故「mtime 未变」是「确实没写」的实测证据。</li>
 *   <li><b>同名异内容 → 改名另存</b>（{@link #sameNameDifferentContentRenamesAside}）——
 *       两侧内容各自正确，原图不得被覆盖。这是 defect-survey.md 病 B 的直接反例。</li>
 *   <li><b>改名是内容寻址的</b>（{@link #renamingIsContentAddressedSoReuploadHitsTheSameName}）——
 *       同一份内容再传落在同名上，天然幂等。</li>
 * </ol>
 *
 * <p>⚠️ 未覆盖、且<b>刻意不覆盖</b>的：改名后仍撞名（约 2^-32）时抛 {@code IOException} 那条分支
 * —— 除非伪造 SHA-256 前缀，无法在测试里命中；其「显式失败而非静默覆盖」的取舍写在该分支的注释里。
 */
class UploadStorageTest {

    /** 合法的原图内容（ASCII，断言里不必处理编码）。 */
    private static final byte[] ORIGINAL = "IMG-ORIGINAL".getBytes();

    /** 借 {@code @RequestParam("file")} 的字段名，但 {@code store} 只用 originalFilename。 */
    private static MultipartFile upload(String originalFilename, byte[] content) {
        return new MockMultipartFile("file", originalFilename, "image/jpeg", content);
    }

    private static long regularFileCount(Path dir) throws Exception {
        // 必须 try-with-resources：留着未关的目录流句柄，@TempDir 在 Windows 上会删不掉。
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.count();
        }
    }

    @Test
    @DisplayName("同名同内容：复用磁盘上那份，不重写、不新增文件")
    void sameNameSameContentReusesExistingWithoutRewrite(@TempDir Path dir) throws Exception {
        Path existing = dir.resolve("1.jpg");
        Files.write(existing, ORIGINAL);

        // 拨回一个不可能的旧 mtime，再读回来作为期望值（读回可免疫文件系统的时间戳取整）。
        Files.setLastModifiedTime(existing, FileTime.fromMillis(0L));
        FileTime stamp = Files.getLastModifiedTime(existing);

        String returned = UploadStorage.store(dir, upload("1.jpg", ORIGINAL));

        assertThat(returned).isEqualTo("1.jpg");
        assertThat(Files.readAllBytes(existing)).isEqualTo(ORIGINAL);
        assertThat(Files.getLastModifiedTime(existing)).isEqualTo(stamp);
        assertThat(regularFileCount(dir)).isEqualTo(1L);
    }

    @Test
    @DisplayName("同名异内容：改名另存为 <原名>_<8位hex><扩展名>，原图不被覆盖")
    void sameNameDifferentContentRenamesAside(@TempDir Path dir) throws Exception {
        Path occupied = dir.resolve("色款.jpg");
        Files.write(occupied, ORIGINAL);
        byte[] mine = "IMG-MINE-BUT-DIFFERENT".getBytes();

        String returned = UploadStorage.store(dir, upload("色款.jpg", mine));

        assertThat(returned)
                .as("同名不同内容必须改名；用入参名拼 URL 会指向别人的图 —— 正是病 B 的形态")
                .isNotEqualTo("色款.jpg")
                .matches("色款_[0-9a-f]{8}\\.jpg");
        assertThat(Files.readAllBytes(occupied)).isEqualTo(ORIGINAL);
        assertThat(Files.readAllBytes(dir.resolve(returned))).isEqualTo(mine);
    }

    @Test
    @DisplayName("改名是内容寻址的：同一份内容再传落在同名上，且不重写")
    void renamingIsContentAddressedSoReuploadHitsTheSameName(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("1.jpg"), ORIGINAL);
        byte[] mine = "IMG-MINE-BUT-DIFFERENT".getBytes();

        String first = UploadStorage.store(dir, upload("1.jpg", mine));
        // 前置：首次上传**必须**发生改名。没这条，下面 second==first
        // 在「根本不改名」的病 B 复现下会**平凡成立**（"1.jpg"=="1.jpg"）
        // —— 实测：把 sameContent 改成恒 true 时，本样本原本是静默绿的。
        assertThat(first).as("首次上传必须改名（否则下面的幂等断言是空的）")
                .isNotEqualTo("1.jpg")
                .matches("1_[0-9a-f]{8}\\.jpg");
        Files.setLastModifiedTime(dir.resolve(first), FileTime.fromMillis(0L));
        FileTime stamp = Files.getLastModifiedTime(dir.resolve(first));

        String second = UploadStorage.store(dir, upload("1.jpg", mine));

        assertThat(second).isEqualTo(first);
        assertThat(Files.getLastModifiedTime(dir.resolve(second))).isEqualTo(stamp);
    }

    @Test
    @DisplayName("长度预筛：长度不同即判为不同内容，占名不得吞掉上传")
    void differentLengthIsTreatedAsDifferentContent(@TempDir Path dir) throws Exception {
        // 「占名」：别人以同名大图占住 1.jpg
        Files.write(dir.resolve("1.jpg"), new byte[64 * 1024]);
        byte[] mine = "X".getBytes();

        String returned = UploadStorage.store(dir, upload("1.jpg", mine));

        assertThat(returned).isNotEqualTo("1.jpg");
        assertThat(Files.readAllBytes(dir.resolve(returned))).isEqualTo(mine);
        // ⚠️ 本用例是**性能护栏**，如实标注：删掉 UploadStorage.sameContent 里的长度预筛
        //    它照样绿 —— 它区分不了「先比长度」与「直接读整份再比」。它钉的是**行为契约**
        //    （长度不同 ⇒ 视为不同内容），不是那条优化本身。省掉「对磁盘上那个可能很大的
        //    同名文件的整份读取」这一收益，只能靠 sameContent 的 javadoc + 代码审查守。
    }
}
