package com.xnlp.server.waste;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/waste")
public class WastePhotoController {
    private final Path uploadDir;

    public WastePhotoController(@Value("${waste.upload-dir:./data/waste-uploads}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("现场照片不能为空");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("单张照片不能超过 10MB");
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/")) throw new IllegalArgumentException("仅支持图片文件");
        String extension = extension(file.getOriginalFilename(), contentType);
        String filename = UUID.randomUUID() + extension;
        try {
            Files.createDirectories(uploadDir);
            Files.copy(file.getInputStream(), uploadDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("照片存储失败", e);
        }
        return Map.of("url", "/uploads/waste/" + filename, "filename", filename);
    }

    private static String extension(String originalName, String contentType) {
        if (originalName != null) {
            String name = Path.of(originalName).getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot > -1 && dot < name.length() - 1) {
                String ext = name.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.(jpg|jpeg|png|gif|webp)")) return ext;
            }
        }
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
