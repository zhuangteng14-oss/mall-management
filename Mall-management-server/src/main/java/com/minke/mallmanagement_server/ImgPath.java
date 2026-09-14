package com.minke.mallmanagement_server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class ImgPath implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        System.out.println("图片配置生效！！！");
        String filePath = System.getProperty("user.dir")+"\\src\\main\\resources\\static\\img\\";
        System.out.println("目前图片所在本地位置为：" + filePath);
        registry.addResourceHandler("/img/**").addResourceLocations("file:"+filePath);

    }


}
