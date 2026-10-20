package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.vo.ArtistCandidateVO;
import cn.edu.seig.vibemusic.model.vo.ArtistProfileVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.artist.ArtistProfileService;
import cn.edu.seig.vibemusic.service.artist.QqArtistAvatarUrl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端歌手资料检索接口（用于新增歌手时的「从 QQ 音乐自动填充」）。
 *
 * <p>路径在 {@code /admin/**} 下，由角色权限配置限定为 ROLE_ADMIN 可访问。
 *
 * <p>两个接口都<b>只读</b>、<b>不落盘</b>：搜索返回候选（含 CDN 直链，由浏览器直接加载），
 * 详情返回待填充的字段。头像真正写入 MinIO 发生在管理员点「确定」、调用
 * {@code POST /admin/addArtistWithAvatar} 的那一刻 —— 那时服务端才按 mid 下载。
 *
 * <p>为什么不在这里就把头像下载下来：一次搜索会返回多个候选，绝大多数会被丢弃；
 * 而"服务端按调用方给的 mid 自行拼地址"也顺带挡住了 SSRF —— 接口从不接受任意 URL。
 */
@RestController
@RequestMapping("/admin/artists/qq")
public class AdminArtistProfileController {

    @Autowired
    private ArtistProfileService artistProfileService;

    /**
     * 按名字搜索歌手候选。
     *
     * <p>名字带尾部括号（如 {@code IU (아이유)}）且原名搜不到时，会自动去掉括号重试一次。
     *
     * @param keyword 歌手名
     * @return 按匹配度降序的候选；搜不到时返回空列表（不是错误）
     */
    @GetMapping("/search")
    public Result<List<ArtistCandidateVO>> search(@RequestParam("keyword") String keyword) {
        List<ArtistCandidateVO> candidates = artistProfileService.search(keyword);
        if (candidates.isEmpty()) {
            return Result.success("未找到匹配的歌手，可手动填写资料", candidates);
        }
        return Result.success("找到 " + candidates.size() + " 位候选歌手", candidates);
    }

    /**
     * 取某位候选歌手的完整资料。
     *
     * @param singerMid QQ 音乐歌手 mid
     * @return 资料；对方无此歌手时返回错误说明
     */
    @GetMapping("/{singerMid}/profile")
    public Result<ArtistProfileVO> profile(@PathVariable("singerMid") String singerMid) {
        if (!QqArtistAvatarUrl.isValidSingerMid(singerMid)) {
            return Result.error("歌手标识不合法");
        }
        ArtistProfileVO profile = artistProfileService.detail(singerMid);
        if (profile == null) {
            return Result.error("未取到该歌手的资料，请换一位候选或手动填写");
        }
        return Result.success("已获取歌手资料", profile);
    }
}
