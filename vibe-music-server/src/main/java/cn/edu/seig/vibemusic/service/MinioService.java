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

    /**
     * 由公开访问 URL 反解出 MinIO 对象名，是 {@link #deleteFile} 内部解析逻辑的公开形态。
     *
     * <p>媒体清理任务只登记<b>对象名</b>（身份 = {@code (bucket, objectKey)}），绝不登记完整 URL：
     * 历史数据里同一对象的主机名既有 {@code localhost} 也有 {@code 127.0.0.1}，用 URL 做身份会
     * 把同一个对象当成两个。刻意不做百分号解码，因此文件名里的字面 {@code +}、空格保持原样。
     *
     * @param fileUrl 公开访问 URL 或纯对象名；空白输入返回空串
     */
    String toObjectName(String fileUrl);

    /** 列出指定前缀下的所有对象名，用于回收孤儿暂存文件。 */
    List<String> listObjectNames(String prefix);

    /**
     * 列出指定前缀下的对象名与最后修改时间。
     *
     * <p>孤儿清扫必须知道「这个目录是刚建的还是早就没人管了」：{@code prepare} 是
     * 「先传文件、后写 Redis 会话」，中间那段窗口里目录已存在而会话还没建，
     * 只按「无会话」判断会把正在上传的文件删掉。调用方据此跳过最近有写入的目录。
     *
     * @param prefix 对象前缀
     * @return 对象名 + 最后修改时间（{@link java.time.ZonedDateTime}，取不到时为 {@code null}）
     */
    List<StagedObject> listStagedObjects(String prefix);

    /**
     * 暂存对象的一项：对象名与最后修改时间。
     *
     * @param objectName   对象名
     * @param lastModified 最后修改时间；MinIO 未返回时为 {@code null}（调用方应按「刚写入」处理，宁可不删）
     */
    record StagedObject(String objectName, java.time.ZonedDateTime lastModified) {
    }
}
