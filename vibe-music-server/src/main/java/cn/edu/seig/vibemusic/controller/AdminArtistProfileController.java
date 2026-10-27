package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.vo.ArtistCandidateVO;
import cn.edu.seig.vibemusic.model.vo.ArtistProfileVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.artist.ArtistProfileService;
import cn.edu.seig.vibemusic.service.artist.QqArtistAvatarUrl;
import cn.edu.seig.vibemusic.service.cover.CoverDownloader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 管理端歌手资料检索接口（用于新增歌手时的「从 QQ 音乐自动填充」）。
 *
 * <p>路径在 {@code /admin/**} 下，由角色权限配置限定为 ROLE_ADMIN 可访问。
 *
 * <p>三个接口都<b>只读</b>、<b>不落盘</b>：搜索返回候选（含 CDN 直链，由浏览器直接加载），
 * 详情返回待填充的字段，头像原图接口只把 CDN 字节原样转发（供前端同源裁剪）。
 * 头像真正写入 MinIO 发生在管理员点「确定」提交表单的那一刻 —— 服务端按 mid 下载，
 * 或接收前端裁剪后的图片文件。
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

    /**
     * 取某位候选歌手的头像原图（字节代理）。
     *
     * <p>腾讯 CDN 只对白名单 Origin（如 {@code y.qq.com}）回 CORS 头，管理端页面直接把
     * {@code y.gtimg.cn} 的图画进 canvas 会因画布污染导不出裁剪结果。这里由服务端把图
     * 取回、原样转发字节，前端在<b>同源</b>响应上完成裁剪。与 {@link #profile} 一样：
     * 只接受白名单 mid、按固定模板拼地址，不接受任意 URL，不产生 SSRF；只读、不落盘。
     *
     * @param singerMid QQ 音乐歌手 mid
     * @param size      头像尺寸（300/500/800），可省略，默认 500
     * @return 图片字节；mid 不合法返回 400，CDN 侧失败返回 502
     */
    @GetMapping("/{singerMid}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable("singerMid") String singerMid,
                                         @RequestParam(value = "size", required = false) Integer size) {
        if (!QqArtistAvatarUrl.isValidSingerMid(singerMid)) {
            return ResponseEntity.badRequest().build();
        }
        CoverDownloader.Result result = artistProfileService.downloadAvatar(singerMid, size, null);
        if (!result.success()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(result.bytes());
    }
}
