package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.AlbumUploadCommitDTO;
import cn.edu.seig.vibemusic.model.vo.AlbumUploadPrepareVO;
import cn.edu.seig.vibemusic.result.Result;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface IAlbumUploadService {
    Result<AlbumUploadPrepareVO> prepare(MultipartFile[] audio, MultipartFile cover);
    Result<Object> commit(AlbumUploadCommitDTO dto);
    Result<Object> clear(String uploadId);

    Result<Object> createSession(List<AlbumUploadFileRequest> files);
    Result<Object> signFile(String uploadId, String fileName, long size, String contentType, boolean cover);
    Result<Object> completeFile(String uploadId, String fileName, long size, String objectName);
    Result<AlbumUploadPrepareVO> parseStaging(String uploadId);
    Result<Object> session(String uploadId);

    /**
     * 回收孤儿暂存文件：列出 staging 目录，凡是已经没有对应会话（Redis key 已过期或被放弃）的
     * 上传任务，其暂存文件都会被删除。
     *
     * @return 被清理的上传任务目录数量
     */
    int cleanupOrphanStaging();

    record AlbumUploadFileRequest(String fileName, long size, String contentType, boolean cover) {}
}
