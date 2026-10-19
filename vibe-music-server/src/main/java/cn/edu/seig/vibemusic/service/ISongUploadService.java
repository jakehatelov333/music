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

    Result clear(String uploadId);

    void cleanupExpired();
}
