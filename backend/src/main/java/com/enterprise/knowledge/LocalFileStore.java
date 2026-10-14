package com.enterprise.knowledge;

import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.storage-type", havingValue = "local", matchIfMissing = true)
public class LocalFileStore implements FileStore {
    private final Path root;

    public LocalFileStore(@Value("${app.storage}") String storage) throws Exception {
        this.root = Path.of(storage).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override public void save(String key, InputStream in, long size) throws Exception {
        Path p = root.resolve(key);
        Path parent = p.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (var out = Files.newOutputStream(p)) { in.transferTo(out); }
    }

    @Override public InputStream open(String key) throws Exception { return Files.newInputStream(root.resolve(key)); }

    @Override public void delete(String key) throws Exception { Files.deleteIfExists(root.resolve(key)); }

    @Override public Path toLocalFile(String key) { return root.resolve(key); }

    @Override public void releaseLocal(Path p) { /* 本地文件无需清理 */ }
}
