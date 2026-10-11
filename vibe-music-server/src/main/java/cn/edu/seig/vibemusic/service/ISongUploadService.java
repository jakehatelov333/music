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

    Result<SongUploadCommitVO> commit(SongUploadCommitDTO dto);

    Result clear(String uploadId);

    void cleanupExpired();
}
