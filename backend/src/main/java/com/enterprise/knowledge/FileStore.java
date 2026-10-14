package com.enterprise.knowledge;

import java.io.InputStream;
import java.nio.file.Path;

/** 文件存储抽象：本地磁盘或 S3 兼容对象存储。 */
public interface FileStore {
    void save(String key, InputStream in, long size) throws Exception;
    InputStream open(String key) throws Exception;
    void delete(String key) throws Exception;
    /** 以本地文件方式访问对象（本地存储直接返回原路径；S3 下载到临时文件，用后调用 releaseLocal 清理）。 */
    Path toLocalFile(String key) throws Exception;
    void releaseLocal(Path p) throws Exception;
}
