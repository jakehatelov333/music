package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.mapper.MediaReferenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 媒体对象引用核对：回答"这个对象还有没有人在用"。
 *
 * <p>这是删除媒体文件前唯一被信任的判据。刻意<b>不接收 bucket 参数</b>：7 类引用列都隐含同一个
 * 桶（{@code minio.bucket}），库里没有任何一列记录桶名，传入别的桶根本无法核对，接收这个参数
 * 只会给出"核对过了"的假象。跨桶支持需要先给引用列加上桶信息，属于 C2 及以后的事。
 */
@Service
@RequiredArgsConstructor
public class MediaReferenceService {

    private final MediaReferenceMapper mediaReferenceMapper;

    /**
     * 统计该对象键在 7 类引用列里出现的总行数。
     *
     * @param objectKey MinIO 对象键（不是完整 URL）；空白输入返回 0（视为无引用由调用方决定，绝不删空白键）
     * @return 引用行数，0 表示没有任何行引用它
     */
    public long countReferences(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return 0L;
        }
        return mediaReferenceMapper.countReferences(objectKey);
    }

    /** 是否存在至少一条引用。 */
    public boolean isReferenced(String objectKey) {
        return countReferences(objectKey) > 0L;
    }
}
