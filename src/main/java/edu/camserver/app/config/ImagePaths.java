package edu.camserver.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

@Component
public class ImagePaths {

    private final Path baseDir;

    public ImagePaths(@Value("${app.images.base-dir}") String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    public Path baseDir() {
        return baseDir;
    }

    public File fileFor(String fileName) {
        return resolve(fileName).toFile();
    }

    /**
     * Resolves a bare file name inside the image directory. Names that would escape the
     * directory (absolute paths, "..", nested paths) are rejected.
     */
    public Path resolve(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Image file name is empty");
        }
        Path resolved = baseDir.resolve(fileName).normalize();
        if (resolved.equals(baseDir) || !resolved.getParent().equals(baseDir)) {
            throw new IllegalArgumentException("Invalid image file name: " + fileName);
        }
        return resolved;
    }
}
