package com.xnlp.server.waste;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WastePhotoWebConfig implements WebMvcConfigurer {
    private final String location;

    public WastePhotoWebConfig(@Value("${waste.upload-dir:./data/waste-uploads}") String uploadDir) {
        this.location = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/waste/**").addResourceLocations(location + "/");
    }
}
