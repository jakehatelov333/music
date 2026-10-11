package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.AudioMetadataDTO;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * 音频元数据与时长解析。
 */
public interface IAudioMetadataService {

    /**
     * 解析失败时不抛出业务异常，返回带空字段和 durationParsed=false 的结果。
     */
    AudioMetadataDTO parse(MultipartFile audio);

    AudioMetadataDTO parse(InputStream input, String fileName, long size);
}
