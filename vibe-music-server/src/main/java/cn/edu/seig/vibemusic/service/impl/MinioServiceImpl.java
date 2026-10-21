package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.service.MinioService;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.messages.DeleteObject;
import io.minio.errors.ErrorResponseException;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.http.Method;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.messages.DeleteError;
import io.minio.messages.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Duration;

@Service
public class MinioServiceImpl implements MinioService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    public MinioServiceImpl(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public String uploadFile(MultipartFile file, String folder) {
        String originalFilename = sanitizeFileName(file.getOriginalFilename());
        String fileName = UUID.randomUUID() + "-" + originalFilename;
        return uploadFile(file, folder, fileName);
    }

    @Override
    public String uploadFile(MultipartFile file, String folder, String fileName) {
        String objectName = objectName(folder, fileName);
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(contentType(file.getContentType()))
                            .build()
            );
            return publicUrl(objectName);
        } catch (Exception e) {
            throw new RuntimeException(MessageConstant.FILE_UPLOAD + MessageConstant.FAILED + "：" + e.getMessage());
        }
    }

    @Override
    public String uploadBytes(byte[] content, String folder, String fileName, String contentType) {
        String objectName = objectName(folder, fileName);
        try (InputStream inputStream = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, content.length, -1)
                            .contentType(contentType(contentType))
                            .build()
            );
            return publicUrl(objectName);
        } catch (Exception e) {
            throw new RuntimeException(MessageConstant.FILE_UPLOAD + MessageConstant.FAILED + "：" + e.getMessage());
        }
    }

    @Override
    public String copyObject(String sourceObjectName, String targetFolder, String targetFileName) {
        String targetObjectName = objectName(targetFolder, targetFileName);
        try {
            CopySource source = CopySource.builder()
                    .bucket(bucketName)
                    .object(sourceObjectName)
                    .build();
            minioClient.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(bucketName)
                            .object(targetObjectName)
                            .source(source)
                            .build()
            );
            return publicUrl(targetObjectName);
        } catch (Exception e) {
            throw new RuntimeException("文件转存失败：" + e.getMessage());
        }
    }

    @Override
    public boolean objectExists(String objectName) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || "NoSuchObject".equals(e.errorResponse().code())) {
                return false;
            }
            throw new RuntimeException("检查文件状态失败：" + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("检查文件状态失败：" + e.getMessage());
        }
    }

    @Override
    public void deleteFile(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            return;
        }
        deleteObject(resolveObjectName(fileUrl));
    }

    @Override
    public String toObjectName(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            return "";
        }
        return resolveObjectName(fileUrl);
    }

    /**
     * 从公开访问 URL 中解析出 MinIO 对象名。
     *
     * <p>刻意不拿 {@code minio.endpoint} 拼前缀做 {@code startsWith} 比较：历史数据里主机名
     * 既有 {@code localhost} 也有 {@code 127.0.0.1}，一旦对不上就会把整条 URL 当成对象名，
     * 删除随即静默失败（S3 DELETE 幂等，不存在的 key 同样返回成功），旧文件便永远留在桶里。
     *
     * <p>这里只按 {@code /{bucket}/} 定位，因此天然兼容：主机名差异、历史脏数据中多出来的
     * 路径段（例如 MinIO Console 的 {@code /browser/}），以及直接传入纯对象名的调用。
     */
    private String resolveObjectName(String fileUrl) {
        String raw = fileUrl.trim();

        // 丢弃 scheme://host:port，只保留 path
        int schemeIdx = raw.indexOf("://");
        if (schemeIdx >= 0) {
            int pathStart = raw.indexOf('/', schemeIdx + 3);
            raw = pathStart >= 0 ? raw.substring(pathStart) : "";
        }

        // 丢弃查询串与片段
        int cut = raw.length();
        int queryIdx = raw.indexOf('?');
        if (queryIdx >= 0) {
            cut = queryIdx;
        }
        int fragmentIdx = raw.indexOf('#');
        if (fragmentIdx >= 0 && fragmentIdx < cut) {
            cut = fragmentIdx;
        }
        raw = raw.substring(0, cut);

        String marker = "/" + bucketName + "/";
        int bucketIdx = raw.indexOf(marker);
        if (bucketIdx >= 0) {
            return raw.substring(bucketIdx + marker.length());
        }
        // 已经是对象名（不带 bucket 前缀）时原样返回
        return raw.startsWith("/") ? raw.substring(1) : raw;
    }

    @Override
    public void deleteObject(String objectName) {
        if (!StringUtils.hasText(objectName)) {
            return;
        }
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("文件删除失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteObjectsByPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return;
        }
        try {
            List<DeleteObject> objects = new ArrayList<>();
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix)
                            .recursive(true)
                            .build()
            );
            for (Result<Item> result : results) {
                objects.add(new DeleteObject(result.get().objectName()));
            }
            if (objects.isEmpty()) {
                return;
            }

            Iterable<Result<DeleteError>> errors = minioClient.removeObjects(
                    RemoveObjectsArgs.builder()
                            .bucket(bucketName)
                            .objects(objects)
                            .build()
            );
            for (Result<DeleteError> error : errors) {
                DeleteError deleteError = error.get();
                throw new RuntimeException("文件删除失败: " + deleteError.message());
            }
        } catch (Exception e) {
            throw new RuntimeException("目录清理失败: " + e.getMessage());
        }
    }

    @Override
    public String presignedPutUrl(String objectName, Duration expiry) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry((int) Math.max(1, Math.min(expiry == null ? 900 : expiry.getSeconds(), 7 * 24 * 3600)))
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("生成文件上传地址失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listObjectNames(String prefix) {
        List<String> names = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix == null ? "" : prefix)
                            .recursive(true)
                            .build()
            );
            for (Result<Item> result : results) {
                names.add(result.get().objectName());
            }
        } catch (Exception e) {
            throw new RuntimeException("列出文件失败: " + e.getMessage(), e);
        }
        return names;
    }

    @Override
    public List<StagedObject> listStagedObjects(String prefix) {
        List<StagedObject> objects = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix == null ? "" : prefix)
                            .recursive(true)
                            .build()
            );
            for (Result<Item> result : results) {
                Item item = result.get();
                objects.add(new StagedObject(item.objectName(), item.lastModified()));
            }
        } catch (Exception e) {
            throw new RuntimeException("列出文件失败: " + e.getMessage(), e);
        }
        return objects;
    }

    @Override
    public InputStream getObject(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).object(objectName).build());
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    private String objectName(String folder, String fileName) {
        String cleanFolder = folder == null ? "" : folder.replace('\\', '/').replaceAll("^/+|/+$", "");
        String cleanFileName = sanitizeFileName(fileName);
        return cleanFolder.isEmpty() ? cleanFileName : cleanFolder + "/" + cleanFileName;
    }

    private String sanitizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return UUID.randomUUID().toString();
        }
        String normalized = fileName.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    @Override
    public String publicUrl(String objectName) {
        return publicUrlInternal(objectName);
    }

    private String publicUrlInternal(String objectName) {
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return cleanEndpoint + "/" + bucketName + "/" + objectName;
    }

    private String contentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType : "application/octet-stream";
    }
}
