package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.AlbumUploadCommitDTO;
import cn.edu.seig.vibemusic.model.vo.AlbumUploadPrepareVO;
import cn.edu.seig.vibemusic.result.Result;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface IAlbumUploadService {
    Result<AlbumUploadPrepareVO> prepare(MultipartFile[] audio, MultipartFile cover);
    Result<Object> commit(AlbumUploadCommitDTO dto);

    /**
     * 提交成功后的收尾清理：删会话 + 删暂存文件，失败只记日志。
     *
     * <p><b>只用于「垃圾清理」</b>，不要拿它响应用户的「放弃草稿」——它失败时也返回成功，
     * 用户会以为删干净了。那个场景用 {@link #abort(String)}。
     */
    Result<Object> clear(String uploadId);

    /**
     * 放弃一次上传（管理端「放弃草稿」）：先删暂存文件，<b>确认成功之后</b>才删 Redis 会话。
     *
     * <p>与 {@link #clear(String)} 的区别在失败语义：这里删文件失败会<b>保留会话并返回错误</b>，
     * 管理员可以重试；会话是唯一记录「这次上传还没收尾」的地方，留着它才不会让残留文件
     * 变成无人知晓的孤儿（虽然孤儿清扫也能按前缀兜底，但要多等最多一轮定时任务）。
     *
     * @param uploadId 上传会话 id
     * @return 成功/失败；失败时暂存文件与会话都保持原样
     */
    Result<Object> abort(String uploadId);

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
