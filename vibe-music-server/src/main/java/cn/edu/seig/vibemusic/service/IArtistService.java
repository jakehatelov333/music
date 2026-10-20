package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.ArtistAddDTO;
import cn.edu.seig.vibemusic.model.dto.ArtistDTO;
import cn.edu.seig.vibemusic.model.dto.ArtistUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.vo.ArtistDetailVO;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
import cn.edu.seig.vibemusic.model.vo.ArtistVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
public interface IArtistService extends IService<Artist> {

    // 获取所有歌手
    Result<PageResult<ArtistVO>> getAllArtists(ArtistDTO artistDTO);

    // 获取所有歌手
    Result<PageResult<Artist>> getAllArtistsAndDetail(ArtistDTO artistDTO);

    // 获取所有歌手id和名字
    Result<List<ArtistNameVO>> getAllArtistNames();

    // 获取随机歌手
    Result<List<ArtistVO>> getRandomArtists();

    // 根据id获取歌手详情
    Result<ArtistDetailVO> getArtistDetail(Long artistId, HttpServletRequest request);

    // 获取所有歌手数量
    Result<Long> getAllArtistsCount(Integer gender, String area);

    // 添加歌手
    Result addArtist(ArtistAddDTO artistAddDTO);

    /**
     * 添加歌手，并可在同一次请求里带上头像。
     *
     * <p>把「建歌手」和「存头像」合并成一次调用，是为了避免两种中间状态：
     * 先建后传头像失败 → 库里留下一条永远没头像的记录；先传头像后建失败 → MinIO 里留下孤儿对象。
     * 现在的顺序是「先传头像、再插库」，插库失败时把刚上传的对象删掉（此刻它必然没有任何引用）。
     *
     * @param artistAddDTO 歌手信息
     * @param avatar       头像文件，可为 {@code null} 表示本次不设头像
     * @return 成功时 {@code data} 为新歌手的 id —— 前端建完需要它来刷新列表/继续操作
     */
    Result addArtist(ArtistAddDTO artistAddDTO, MultipartFile avatar);

    // 更新歌手
    Result updateArtist(ArtistUpdateDTO artistUpdateDTO);

    // 更新歌手头像
    Result updateArtistAvatar(Long artistId, String avatar);

    // 删除歌手
    Result deleteArtist(Long ArtistId);

    // 批量删除歌手
    Result deleteArtists(List<Long> artistIds);

}
