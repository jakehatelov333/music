package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.vo.AlbumSuggestVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IAlbumService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端专辑联想接口。
 *
 * <h3>为什么不复用 {@code GET /album/search}</h3>
 * 那是用户端接口，语义是「给听众找专辑」：
 * <ul>
 *   <li>它固定过滤 {@code ar.status = 'ACTIVE'} —— 停用歌手名下的专辑会消失，
 *       而管理员上传时看到「明明有这张专辑却搜不到」只会困惑；</li>
 *   <li>它只返回已发布内容，草稿专辑看不见 —— 管理员刚建了草稿却搜不到，同样困惑；</li>
 *   <li>它的权限只开给 {@code ROLE_USER}（见 {@code application.yml} 的
 *       {@code role-path-permissions}），管理员调用会直接 403。</li>
 * </ul>
 * 把管理端需求塞进用户端接口会让两边的语义都变模糊，因此单独开一个端点。
 *
 * <h3>为什么不复用 {@code /admin/checkAlbumExists}</h3>
 * 那个是精确匹配，用于「提交前查重」；这里是前缀/包含匹配，用于「输入时联想」。
 * 两者解决的问题不同，合并只会让任何一方都做不好。
 */
@RestController
@RequestMapping("/admin/albums")
public class AdminAlbumController {

    @Autowired
    private IAlbumService albumService;

    /**
     * 专辑联想建议。
     *
     * <p>传了 {@code artistId} 时，该歌手的专辑排在最前 —— 上传场景里最可能的意图
     * 就是「给这位歌手已有的专辑加歌」。
     *
     * @param keyword  关键词，空白时返回空列表（不查全库）
     * @param artistId 当前选中的主歌手 id，可选
     * @param limit    条数上限，默认 10，最大 20
     */
    @GetMapping("/suggest")
    public Result<List<AlbumSuggestVO>> suggest(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "artistId", required = false) Long artistId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return albumService.suggestAlbums(keyword, artistId, limit);
    }

}
