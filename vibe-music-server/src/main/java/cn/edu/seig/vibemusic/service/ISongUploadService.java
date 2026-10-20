package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.SongUploadCommitDTO;
import cn.edu.seig.vibemusic.model.vo.SongUploadCommitVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadPrepareVO;
import cn.edu.seig.vibemusic.result.Result;
import org.springframework.web.multipart.MultipartFile;

/**
 * 歌曲上传的两阶段工作流服务。
 */
public interface ISongUploadService {

    Result<SongUploadPrepareVO> prepare(MultipartFile audio);

    Result<SongUploadPrepareVO> updateAssets(String uploadId, MultipartFile cover, MultipartFile lyrics);

    /**
     * 把搜到的 QQ 音乐专辑封面下载到该上传会话的 staging 目录。
     *
     * <p>只接受 {@code albumMid + size}，<b>不接受 URL</b>：允许传 URL 等于给管理端开一个
     * 任意地址抓取入口（SSRF）。服务端按固定模板拼地址，抓取目标被限制在单个 CDN 上。
     *
     * <p>下载结果落在 staging，与手动上传的封面走完全相同的后续路径
     * （{@code commit} 时 {@code copyObject} 到 {@code songCovers/}），因此不需要为
     * "搜来的封面"单独实现入库逻辑。
     */
    Result<SongUploadPrepareVO> updateCoverFromAlbum(String uploadId, String albumMid, Integer size);

    Result<SongUploadCommitVO> commit(SongUploadCommitDTO dto);

    /**
     * 按 {@code uploadId} 读取仍在有效期内的上传会话，重建成与 {@code prepare} 相同的结构。
     *
     * <p>供管理端草稿箱使用：音频、内嵌封面、补充封面与歌词都已在 staging 里，
     * 会话也保存在 Redis 中，唯一缺的就是「前端记住了哪个 uploadId」。
     * 页面刷新/关掉重开后，凭它即可把上次的解析结果原样取回，不用重新上传音频。
     *
     * <p>返回 {@code data = null}（而不是 error）表示会话已过期或不存在：
     * 草稿过期是正常生命周期事件，管理端据此静默移除条目即可。
     */
    Result<SongUploadPrepareVO> session(String uploadId);

    Result clear(String uploadId);

    void cleanupExpired();

    /**
     * 回收「已无对应 Redis 会话」的 staging 目录（孤儿文件兜底）。
     *
     * <p>{@link #cleanupExpired()} 的判据是 zset 索引，只覆盖正常走完流程的上传。
     * {@code prepare} 中途失败、历史版本删会话但删文件失败、Redis 被清空等情况，
     * 索引里都没有记录，那些文件只能靠扫 MinIO 目录 + 反查 Redis 才会被发现。
     *
     * @return 本次清理掉的 uploadId 数量
     */
    int cleanupOrphanStaging();
}
