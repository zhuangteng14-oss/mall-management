package com.minke.mallmanagement_server;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


@Configuration
public class ImgPath implements WebMvcConfigurer {

    @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}")
    private String uploadDir;

    /**
     * 上传根目录。
     *
     * <p>⚠️ 目录不可用时保持 {@code null} —— {@link #addResourceHandlers} 据此跳过注册，
     * 而不是让 NPE 把启动拖死在上下文刷新期（见 {@link #initUploadDir}）。
     */
    private Path uploadPath;

    /**
     * 建上传目录。
     *
     * <p>⚠️ 本方法<b>刻意不抛异常</b>：上传目录不可用是「非核心功能故障」，不该拖死
     * 整个应用（three-layers.md A#4）。此前签名是 {@code throws IOException}，一旦
     * {@code createDirectories} 失败 ⇒ {@code @PostConstruct} 失败 ⇒ 上下文刷新失败
     * ⇒ 整个服务拒绝启动。为修一个目录权限赔上全部功能，代价与收益完全不成比例。
     *
     * <p>【降级后的分工】启动期只 <b>WARN</b>、不阻断；失败推迟到真正落盘的那一刻，
     * 由上传入口显式返回 {@code {success:false, msg}}（见 UploadStorage.store 的三个
     * 调用点）。两者缺一不可：只有入口失败而没有启动警告 ⇒ 没人知道这是配置问题；
     * 只有启动警告而没有入口失败 ⇒ 又退回「静默脏数据」，比原样更差。
     *
     * <p>捕获 {@code Exception} 而非 {@code IOException}：{@code Paths.get} 会抛
     * {@code InvalidPathException}（RuntimeException），{@code Files} 在受限环境会抛
     * {@code SecurityException}。两者同样拖死启动，也同样属于「目录配置不对」这件事。
     */
    @PostConstruct
    public void initUploadDir() {
        try {
            Path candidate = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(candidate);
            if (!Files.isWritable(candidate)) {
                // createDirectories 返回正常 ≠ 目录可写：目录可能**本来就存在且只读**，
                // 那条路径根本不经过创建。补这一查，否则「只读目录」会伪装成正常启动。
                // 此时**读**仍然可用（老图照常能访问），只有**写**会失败
                // ⇒ 继续注册 /img/** 映射，只是提前把话说明白。
                System.out.println("[WARN] 上传目录不可写：" + candidate
                        + " —— 图片上传会失败，但应用继续启动");
            }
            this.uploadPath = candidate;
        } catch (Exception e) {
            // uploadPath 停在 null ⇒ addResourceHandlers 不注册映射 ⇒ /img/** 一律 404。
            System.out.println("[WARN] 上传目录初始化失败：" + uploadDir + " —— " + e
                    + " —— 图片上传会失败，但应用继续启动");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        System.out.println("图片配置生效！！！");
        if (uploadPath == null) {
            // 目录不可用 ⇒ 不注册 /img/**，请求落到 404。
            // 刻意**不**兜一个假目录（如 user.dir）：那会让「配置错了」看起来像
            // 「图丢了」，把配置问题伪装成数据问题，更难查。
            System.out.println("[WARN] 上传目录不可用，/img/** 静态映射未注册（图片请求将 404）");
            return;
        }
        String location = uploadPath.toUri().toString();
        System.out.println("目前图片所在本地位置为：" + location);
        registry.addResourceHandler("/img/**").addResourceLocations(location);

    }


}
