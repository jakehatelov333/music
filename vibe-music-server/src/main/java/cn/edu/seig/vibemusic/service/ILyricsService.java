package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.LyricsBatchRequestDTO;
import cn.edu.seig.vibemusic.model.dto.LyricsSaveDTO;
import cn.edu.seig.vibemusic.model.vo.LyricsBatchTaskVO;
import cn.edu.seig.vibemusic.model.vo.LyricsFetchVO;
import cn.edu.seig.vibemusic.result.Result;

/**
 * 歌词补全服务。
 */
public interface ILyricsService {

    /**
     * 抓取歌词但不入库，供管理端预览与编辑。
     */
    Result<LyricsFetchVO> preview(Long songId);

    /**
     * 保存歌词（管理端手动编辑或预览确认后提交）。
     */
    Result<LyricsFetchVO> save(Long songId, LyricsSaveDTO dto);

    /**
     * 提交批量补全任务，立即返回任务状态，实际处理在后台线程池进行。
     */
    Result<LyricsBatchTaskVO> submitBatch(LyricsBatchRequestDTO dto);

    /**
     * 查询批量任务进度。
     */
    Result<LyricsBatchTaskVO> getTask(String taskId);

}
