package com.minke.mallmanagement_server;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


@Configuration
public class ImgPath implements WebMvcConfigurer {

    @Value("${app.upload-dir:${user.dir}/src/main/resources/static/img/}")
    private String uploadDir;

    private Path uploadPath;

    @PostConstruct
    public void initUploadDir() throws IOException {
        this.uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(uploadPath);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        System.out.println("图片配置生效！！！");
        String location = uploadPath.toUri().toString();
        System.out.println("目前图片所在本地位置为：" + location);
        registry.addResourceHandler("/img/**").addResourceLocations(location);

    }


}
