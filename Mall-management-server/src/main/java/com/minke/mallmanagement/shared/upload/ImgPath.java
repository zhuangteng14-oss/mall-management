package com.minke.mallmanagement.shared.upload;

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
     * 应用**自带图**的出处：随 jar 一起打包，与运行目录 / profile **无关**。
     *
     * <p>与 {@link #uploadPath}（可写的上传目录）**并列**注册，见 {@link #addResourceHandlers}。
     */
    private static final String CLASSPATH_IMG = "classpath:/static/img/";

    /**
     * 上传根目录。
     *
     * <p>⚠️ 目录不可用时保持 {@code null} —— {@link #addResourceHandlers} 据此**只**注册
     * classpath 那一支（2026-10-09 改；此前是整条映射都不注册），而不是让 NPE 把启动拖死在
     * 上下文刷新期（见 {@link #initUploadDir}）。
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
            // uploadPath 停在 null ⇒ addResourceHandlers 只注册 classpath 那一支
            // （自带图还能服务，上传会失败）。
            System.out.println("[WARN] 上传目录初始化失败：" + uploadDir + " —— " + e
                    + " —— 图片上传会失败，但应用继续启动");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        System.out.println("图片配置生效！！！");
        // ⚠️ **两个位置，有序**（2026-10-09 修，起因是一次「登录页大图空白」）：
        //   ① 上传目录（`app.upload-dir`）—— 上传的图落在这里；**同名时它优先**
        //      （`ResourceHttpRequestHandler` 按 `locations` 顺序取第一个命中的资源）；
        //   ② {@link #CLASSPATH_IMG} —— **随应用打包的自带图**（default_user.png、
        //      Mall-Section-*.jpg、Mall-Homepage.jpg…）。
        //
        // 【为什么必须补 ②】① 是「可写目录」，而 a3 profile **刻意**把它外置到
        //   `${java.io.tmpdir}/a3-uploads/`（理由见 application-a3.properties：确保 a3
        //   在任何情况下都不写仓库目录，TD-08）—— 那个目录**是空的**。只注册 ① 时，
        //   `/img/**` **整片 404**，连应用自带的图也一起没了。
        //   ⇒ 实测（2026-10-09，a3 profile 的 8088）：`/img/Mall-Section-2.jpg`、
        //   `/img/Mall-Section-1.jpg`、连 `/img/default_user.png` 全是 404 + JSON 错误体。
        //   登录页那张品牌大图（CSS 硬引 `http://localhost:8088/img/Mall-Section-2.jpg`）
        //   于是只剩 `.brand-fig{background-color:var(--img)}` 兜底 ⇒ 看着像「图没画出来」，
        //   实际是图**没被供给**。同一处的 `Mall-Section-1.jpg`（注册态）在浏览器里之所以
        //   还看得见，只可能是**本地缓存**里那份旧响应。
        //
        // 【② 不是「假目录」】下面那段被判为更坏的兜法（拿 `user.dir` 之流硬凑）之所以坏，
        //   是因为它把**配置错误**伪装成**数据缺失**；`classpath:/static/img/` 是自带图的
        //   **真实出处**，不是凑数。而「上传目录不可用」这件事照样在下面 WARN —— 配置问题
        //   仍然看得见，只是不再顺手把应用自己的图一起赔进去（three-layers.md A#4 的同一条口径：
        //   非核心功能故障不该升级成整片功能故障）。
        String[] locations;
        if (uploadPath == null) {
            System.out.println("[WARN] 上传目录不可用，/img/** 只服务自带图（上传的图会 404）");
            locations = new String[] { CLASSPATH_IMG };
        } else {
            String location = uploadPath.toUri().toString();
            System.out.println("目前图片所在本地位置为：" + location);
            locations = new String[] { location, CLASSPATH_IMG };
        }
        registry.addResourceHandler("/img/**").addResourceLocations(locations);
    }


}
