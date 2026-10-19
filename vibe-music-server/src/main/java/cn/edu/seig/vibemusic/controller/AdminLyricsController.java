package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.LyricsBatchRequestDTO;
import cn.edu.seig.vibemusic.model.dto.LyricsSaveDTO;
import cn.edu.seig.vibemusic.model.dto.LyricsSearchDTO;
import cn.edu.seig.vibemusic.model.vo.LyricsBatchTaskVO;
import cn.edu.seig.vibemusic.model.vo.LyricsFetchVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ILyricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端歌词补全接口。
 * <p>
 * 路径位于 {@code /admin/**} 下，由角色权限配置限定为 ROLE_ADMIN 可访问。
 * 所有操作只在后台触发，不对外网暴露。
 */
@RestController
@RequestMapping("/admin/song/lyrics")
public class AdminLyricsController {

    @Autowired
    private ILyricsService lyricsService;

    /**
     * 抓取歌词用于预览，<b>不入库</b>。管理员确认或编辑后再调用保存接口。
     */
    @PostMapping("/preview/{songId}")
    public Result<LyricsFetchVO> preview(@PathVariable("songId") Long songId) {
        return lyricsService.preview(songId);
    }

    /**
     * 按元数据搜索歌词，<b>不入库</b>。
     *
     * <p>给「歌曲上传」页用：此时歌曲还没入库、没有 songId，只有表单里的
     * 歌名 / 歌手 / 专辑 / 时长。走的是与 {@link #preview(Long)} 完全相同的数据源
     * 与匹配打分链路（QQ 音乐主源 + LRCLIB 兜底，低于阈值宁可不返回）。
     */
    @PostMapping("/search")
    public Result<LyricsFetchVO> search(@RequestBody LyricsSearchDTO dto) {
        return lyricsService.searchByMeta(dto);
    }

    /**
     * 保存歌词（手动编辑或预览确认后提交）。
     * 已有歌词时需在 body 中传入 {@code force=true} 才会覆盖。
     */
    @PutMapping("/{songId}")
    public Result<LyricsFetchVO> save(@PathVariable("songId") Long songId,
                                      @RequestBody LyricsSaveDTO dto) {
        return lyricsService.save(songId, dto);
    }

    /**
     * 提交批量补全任务。
     * <p>
     * 支持三种范围：传 {@code songIds} 处理所选歌曲；传 {@code albumId} 处理整张专辑；
     * 都不传则按 {@code onlyMissing} 条件全量处理缺失歌词的歌曲。
     */
    @PostMapping("/batch")
    public Result<LyricsBatchTaskVO> batch(@RequestBody(required = false) LyricsBatchRequestDTO dto) {
        return lyricsService.submitBatch(dto);
    }

    /**
     * 查询批量任务进度。
     */
    @GetMapping("/task/{taskId}")
    public Result<LyricsBatchTaskVO> task(@PathVariable("taskId") String taskId) {
        return lyricsService.getTask(taskId);
    }

    /**
     * 查询当前正在执行的批量任务（无则 data 为 null）。
     */
    @GetMapping("/running")
    public Result<LyricsBatchTaskVO> running() {
        return lyricsService.getCurrentTask();
    }

    /** 请求停止批量补全任务；当前正在执行的数据源请求结束后停止后续歌曲。 */
    @PostMapping("/task/{taskId}/cancel")
    public Result<LyricsBatchTaskVO> cancel(@PathVariable("taskId") String taskId) {
        return lyricsService.cancelTask(taskId);
    }

    /** 将选中的歌曲标记为伴奏/无需歌词，并清空歌词文本。 */
    @PostMapping("/instrumental")
    public Result<Integer> markInstrumental(@RequestBody List<Long> songIds) {
        return lyricsService.markInstrumental(songIds);
    }

}
