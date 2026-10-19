package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.LyricsBatchRequestDTO;
import cn.edu.seig.vibemusic.model.dto.LyricsSaveDTO;
import cn.edu.seig.vibemusic.model.dto.LyricsSearchDTO;
import cn.edu.seig.vibemusic.model.vo.LyricsBatchTaskVO;
import cn.edu.seig.vibemusic.model.vo.LyricsFetchVO;
import cn.edu.seig.vibemusic.result.Result;

import java.util.List;

/**
 * 歌词补全服务。
 */
public interface ILyricsService {

    /**
     * 抓取歌词但不入库，供管理端预览与编辑。
     */
    Result<LyricsFetchVO> preview(Long songId);

    /**
     * 按元数据搜索歌词，<b>不依赖 songId、不入库</b>。
     *
     * <p>给上传流程用：歌曲还没入库时，按表单里的歌名 / 歌手 / 专辑 / 时长
     * 走与 {@link #preview(Long)} 完全相同的数据源与打分链路。
     */
    Result<LyricsFetchVO> searchByMeta(LyricsSearchDTO dto);

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

    /**
     * 查询当前正在执行的批量任务（无则 data 为 null），
     * 供管理端刷新页面后恢复进度展示。
     */
    Result<LyricsBatchTaskVO> getCurrentTask();

    /** 请求停止正在执行的批量任务。 */
    Result<LyricsBatchTaskVO> cancelTask(String taskId);

    /** 将选中的歌曲标记为伴奏/无需歌词。 */
    Result<Integer> markInstrumental(List<Long> songIds);

}
