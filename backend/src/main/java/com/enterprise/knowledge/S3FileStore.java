package com.enterprise.knowledge;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** S3 兼容对象存储（AWS S3 / MinIO）。需设置 app.storage-type=s3 与 app.s3.*。 */
@Service
@ConditionalOnProperty(name = "app.storage-type", havingValue = "s3")
public class S3FileStore implements FileStore {
    private final S3Client s3;
    private final String bucket;

    public S3FileStore(@Value("${app.s3.bucket}") String bucket,
                       @Value("${app.s3.endpoint:}") String endpoint,
                       @Value("${app.s3.region:us-east-1}") String region,
                       @Value("${app.s3.access-key:}") String accessKey,
                       @Value("${app.s3.secret-key:}") String secretKey,
                       @Value("${app.s3.path-style:true}") boolean pathStyle) {
        if(bucket==null||bucket.isBlank())throw new IllegalArgumentException("S3_BUCKET 不能为空");
        var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(pathStyle);
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (accessKey != null && !accessKey.isBlank())
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        this.s3 = builder.build();
        this.bucket = bucket;
    }

    @Override public void save(String key, InputStream in, long size) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentLength(size).build(),
            RequestBody.fromInputStream(in, size));
    }

    @Override public InputStream open(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override public Path toLocalFile(String key) throws Exception {
        Path tmp = Files.createTempFile("rag-doc-", ".bin");
        try (InputStream in = open(key); OutputStream out = Files.newOutputStream(tmp)) { in.transferTo(out); return tmp; }
        catch(Exception e){Files.deleteIfExists(tmp);throw e;}
    }

    @jakarta.annotation.PreDestroy void close(){s3.close();}
    @Override public void releaseLocal(Path p) throws Exception { Files.deleteIfExists(p); }
}
