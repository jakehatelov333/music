package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.CoverSearchDTO;
import cn.edu.seig.vibemusic.model.vo.CoverCandidateVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.cover.CoverSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端封面搜索接口。
 *
 * <p>路径在 {@code /admin/**} 下，由角色权限配置限定为 ROLE_ADMIN 可访问。
 * 只返回候选（含 CDN 直链），<b>不落盘</b> —— 候选图由浏览器直接加载，
 * 只有管理员真正选中某一张时，才调用
 * {@code POST /admin/song-upload/{uploadId}/cover-from-album} 下载到 staging。
 *
 * <p>为什么不在这里就把候选下载下来：每搜一次会返回 4–8 张图（约 200–600 KB），
 * 绝大多数会被丢弃。落盘只在"选中"这一刻发生，暂存区的垃圾量因此可控。
 */
@RestController
@RequestMapping("/admin/song-cover")
public class AdminSongCoverController {

    @Autowired
    private CoverSearchService coverSearchService;

    /**
     * 搜索封面候选。
     *
     * <p>两种模式：
     * <ul>
     *   <li>自动 —— 不传 {@code keyword}，按 {@code songName} + {@code artistName} 检索，
     *       再用歌名完全相等 + 歌手交集过滤，按专辑去重，返回最多 4 个</li>
     *   <li>手动 —— 传 {@code keyword}（可只填歌名或只填专辑名），原样检索、
     *       <b>不做过滤</b>，按专辑聚拢，返回最多 8 个</li>
     * </ul>
     *
     * <p>手动模式是必需的逃生通道：音频标签解析错误时（歌名带乱码、歌手填成文件名），
     * 自动模式会拿着错误元数据去搜，结果必然也错。此时由管理员自己输入正确关键词。
     */
    @PostMapping("/search")
    public Result<List<CoverCandidateVO>> search(@RequestBody CoverSearchDTO dto) {
        List<CoverCandidateVO> candidates = coverSearchService.search(dto);
        if (candidates.isEmpty()) {
            return Result.success("未找到可用的封面", candidates);
        }
        return Result.success("找到 " + candidates.size() + " 个封面候选", candidates);
    }

}
