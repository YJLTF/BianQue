package com.bianque.server.config;

import com.bianque.server.service.DocsService;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源：/docs/{version}/** 直接托管官方文档站解包目录（离线、版本锚定）。
 */
@Configuration
public class DocsStaticConfig implements WebMvcConfigurer {

    private final DocsService docs;
    private final String unpackRoot;

    public DocsStaticConfig(DocsService docs,
                            @Value("${bianque.docs.unpack-root:}") String unpackRoot) {
        this.docs = docs;
        this.unpackRoot = DocsService.resolveUnpackRoot(unpackRoot).toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path absolute = Path.of(unpackRoot).toAbsolutePath().normalize();
        registry.addResourceHandler("/docs/4.3.5/**")
                .addResourceLocations("file:" + absolute + "/")
                .setCachePeriod(3600);
    }
}
