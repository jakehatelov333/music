package cn.edu.seig.vibemusic.service;

import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.io.InputStream;
import java.util.List;

public interface MinioService {
    /**
     * 上传文件到 MinIO。
     *
     * @param file   要上传的文件
     * @param folder 存储文件的目录
     * @return 文件访问 URL
     */
    String uploadFile(MultipartFile file, String folder);

    /**
     * 使用指定文件名上传文件，避免旧接口自动生成文件名。
     */
    String uploadFile(MultipartFile file, String folder, String fileName);

    /**
     * 上传内存中的二进制内容。
     */
    String uploadBytes(byte[] content, String folder, String fileName, String contentType);

    /**
     * 在 MinIO 内部复制对象，并返回正式文件 URL。
     */
    String copyObject(String sourceObjectName, String targetFolder, String targetFileName);

    /**
     * 判断对象是否存在。
     */
    boolean objectExists(String objectName);

    /**
     * 删除 MinIO 文件。
     *
     * @param fileUrl 文件 URL
     */
    void deleteFile(String fileUrl);

    /**
     * 按对象名删除文件。
     */
    void deleteObject(String objectName);

    /**
     * 删除指定前缀下的所有对象，仅用于清理单次上传的 staging 目录。
     */
    void deleteObjectsByPrefix(String prefix);

    /** 为浏览器直传 staging 对象生成一次性 PUT 地址。 */
    String presignedPutUrl(String objectName, Duration expiry);

    /** 读取 staging 对象，用于重新解析元数据。调用方负责关闭流。 */
    InputStream getObject(String objectName);

    /** 由对象名拼出可公开访问的 URL（浏览器直传的文件只有对象名，没有 URL）。 */
    String publicUrl(String objectName);

    /** 列出指定前缀下的所有对象名，用于回收孤儿暂存文件。 */
    List<String> listObjectNames(String prefix);
}
