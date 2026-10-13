package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.ArtistAddDTO;
import cn.edu.seig.vibemusic.model.dto.ArtistDTO;
import cn.edu.seig.vibemusic.model.dto.ArtistUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import cn.edu.seig.vibemusic.model.vo.ArtistDetailVO;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
import cn.edu.seig.vibemusic.model.vo.ArtistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IArtistService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.TypeConversionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
@CacheConfig(cacheNames = "artistCache")
public class ArtistServiceImpl extends ServiceImpl<ArtistMapper, Artist> implements IArtistService {

    @Autowired
    private ArtistMapper artistMapper;
    @Autowired
    private AlbumMapper albumMapper;
    @Autowired
    private UserFavoriteMapper userFavoriteMapper;
    @Autowired
    private MinioService minioService;
    @Autowired
    private SongArtistService songArtistService;

    /**
     * 获取所有歌手列表
     *
     * @param artistDTO artistDTO
     * @return 歌手列表
     */
    @Override
    @Cacheable(key = "#artistDTO.pageNum + '-' + #artistDTO.pageSize + '-' + #artistDTO.artistName + '-' + #artistDTO.gender + '-' + #artistDTO.area + '-v2'")
    public Result<PageResult<ArtistVO>> getAllArtists(ArtistDTO artistDTO) {
        // 分页查询
        Page<Artist> page = new Page<>(artistDTO.getPageNum(), artistDTO.getPageSize());
        QueryWrapper<Artist> queryWrapper = new QueryWrapper<>();
        // 软删除的歌手对用户端一律不可见
        queryWrapper.eq("status", ArtistStatusEnum.ACTIVE.getStatus());
        // 根据 artistDTO 的条件构建查询条件
        if (artistDTO.getArtistName() != null) {
            queryWrapper.like("name", artistDTO.getArtistName());
        }
        if (artistDTO.getGender() != null) {
            queryWrapper.eq("gender", artistDTO.getGender());
        }
        if (artistDTO.getArea() != null) {
            queryWrapper.like("area", artistDTO.getArea());
        }

        IPage<Artist> artistPage = artistMapper.selectPage(page, queryWrapper);
        if (artistPage.getRecords().size() == 0) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        // 转换成 ArtistVO
        List<ArtistVO> artistVOList = artistPage.getRecords().stream()
                .map(artist -> {
                    ArtistVO artistVO = new ArtistVO();
                    BeanUtils.copyProperties(artist, artistVO);
                    return artistVO;
                }).toList();

        return Result.success(new PageResult<>(artistPage.getTotal(), artistVOList));
    }

    /**
     * 获取所有歌手列表（含详情）
     *
     * @param artistDTO artistDTO
     * @return 歌手列表
     */
    @Override
    @Cacheable(key = "#artistDTO.pageNum + '-' + #artistDTO.pageSize + '-' + #artistDTO.artistName + '-' + #artistDTO.gender + '-' + #artistDTO.area + '-' + #artistDTO.includeDisabled + '-admin-v2'")
    public Result<PageResult<Artist>> getAllArtistsAndDetail(ArtistDTO artistDTO) {
        // 分页查询
        Page<Artist> page = new Page<>(artistDTO.getPageNum(), artistDTO.getPageSize());
        QueryWrapper<Artist> queryWrapper = new QueryWrapper<>();
        // 管理端默认只看启用记录；显式传 includeDisabled=true 时才把停用记录一并带出，
        // 用于追溯「合并掉的组合歌手」这类历史数据。
        if (!Boolean.TRUE.equals(artistDTO.getIncludeDisabled())) {
            queryWrapper.eq("status", ArtistStatusEnum.ACTIVE.getStatus());
        }
        // 根据 artistDTO 的条件构建查询条件
        if (artistDTO.getArtistName() != null) {
            queryWrapper.like("name", artistDTO.getArtistName());
        }
        if (artistDTO.getGender() != null) {
            queryWrapper.eq("gender", artistDTO.getGender());
        }
        if (artistDTO.getArea() != null) {
            queryWrapper.like("area", artistDTO.getArea());
        }

        // 倒序排序
        queryWrapper.orderByDesc("id");

        IPage<Artist> artistPage = artistMapper.selectPage(page, queryWrapper);
        if (artistPage.getRecords().size() == 0) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        return Result.success(new PageResult<>(artistPage.getTotal(), artistPage.getRecords()));
    }

    /**
     * 获取所有歌手id和歌手名称
     *
     * @return 歌手名称列表
     */
    @Override
    @Cacheable(key = "'allArtistNames-v2'")
    public Result<List<ArtistNameVO>> getAllArtistNames() {
        // 歌手选择器 / 歌手树 / 上传匹配都走这里，必须排除停用记录
        List<Artist> artists = artistMapper.selectList(new QueryWrapper<Artist>()
                .eq("status", ArtistStatusEnum.ACTIVE.getStatus())
                .orderByDesc("id"));
        if (artists.isEmpty()) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, null);
        }

        List<ArtistNameVO> artistNameVOList = artists.stream()
                .map(artist -> {
                    ArtistNameVO artistNameVO = new ArtistNameVO();
                    artistNameVO.setArtistId(artist.getArtistId());
                    artistNameVO.setArtistName(artist.getArtistName());
                    return artistNameVO;
                }).toList();

        return Result.success(artistNameVOList);
    }

    /**
     * 获取随机歌手
     * 随机歌手的数量为 10
     *
     * @return 随机歌手列表
     */
    @Override
    public Result<List<ArtistVO>> getRandomArtists() {
        QueryWrapper<Artist> queryWrapper = new QueryWrapper<>();
        // 停用歌手不得出现在首页随机推荐里
        queryWrapper.eq("status", ArtistStatusEnum.ACTIVE.getStatus());
        queryWrapper.last("ORDER BY RAND() LIMIT 10");

        List<Artist> artists = artistMapper.selectList(queryWrapper);
        if (artists.isEmpty()) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, null);
        }

        List<ArtistVO> artistVOList = artists.stream()
                .map(artist -> {
                    ArtistVO artistVO = new ArtistVO();
                    BeanUtils.copyProperties(artist, artistVO);
                    return artistVO;
                }).toList();

        return Result.success(artistVOList);
    }

    /**
     * 获取歌手详情
     *
     * @param artistId 歌手id
     * @param request  HttpServletRequest，用于获取请求头中的 token
     * @return 歌手详情
     */
    @Override
    // v3 → v4：歌曲结果集新增 albumId（供前端把专辑名做成可跳转链接）。
    // 改 SQL 结果集必须升版本号，否则 Redis 里的旧条目会继续返回没有 albumId 的歌，
    // 表现为「重启了但专辑名还是点不动」。
    @Cacheable(key = "#artistId + '-albums-v4'")
    public Result<ArtistDetailVO> getArtistDetail(Long artistId, HttpServletRequest request) {
        // 查询里带 status = ACTIVE：停用歌手详情返回 null，由下面统一按「歌手不存在」处理，
        // 避免把已合并掉的组合歌手页继续暴露出去。
        ArtistDetailVO artistDetailVO = artistMapper.getArtistDetailById(artistId);
        if (artistDetailVO == null) {
            return Result.error(MessageConstant.ARTIST + MessageConstant.NOT_FOUND);
        }
        artistDetailVO.setAlbums(albumMapper.getAlbumsByArtistId(artistId));

        // 设置默认状态
        List<SongVO> songVOList = artistDetailVO.getSongs();
        songVOList.forEach(songVO -> songVO.setLikeStatus(LikeStatusEnum.DEFAULT.getId()));

        // 获取请求头中的 token
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7); // 去掉 "Bearer " 前缀
        }

        Map<String, Object> map = null;
        if (token != null && !token.isEmpty()) {
            map = JwtUtil.parseToken(token);
        }

        // 如果 token 解析成功且用户为登录状态，进一步操作
        if (map != null) {
            String role = (String) map.get(JwtClaimsConstant.ROLE);
            if (role.equals(RoleEnum.USER.getRole())) {
                Object userIdObj = map.get(JwtClaimsConstant.USER_ID);
                Long userId = TypeConversionUtil.toLong(userIdObj);

                // 获取用户收藏的歌曲
                List<UserFavorite> favoriteSongs = userFavoriteMapper.selectList(new QueryWrapper<UserFavorite>()
                        .eq("user_id", userId)
                        .eq("type", 0));

                // 获取用户收藏的歌曲 id
                Set<Long> favoriteSongIds = favoriteSongs.stream()
                        .map(UserFavorite::getSongId)
                        .collect(Collectors.toSet());

                // 检查并更新状态
                for (SongVO songVO : songVOList) {
                    if (favoriteSongIds.contains(songVO.getSongId())) {
                        songVO.setLikeStatus(LikeStatusEnum.LIKE.getId());
                    }
                }
            }
        }

        // 设置歌曲列表
        // 这些歌曲来自 tb_song_artist（含该歌手参与的合作歌曲），每首歌再补上自己的完整歌手列表。
        songArtistService.attachArtists(songVOList);
        artistDetailVO.setSongs(songVOList);

        return Result.success(artistDetailVO);
    }

    /**
     * 获取所有歌手数量
     *
     * @param gender 性别
     * @param area   地区
     * @return 歌手数量
     */
    @Override
    public Result<Long> getAllArtistsCount(Integer gender, String area) {
        QueryWrapper<Artist> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", ArtistStatusEnum.ACTIVE.getStatus());
        if (gender != null) {
            queryWrapper.eq("gender", gender);
        }
        if (area != null) {
            queryWrapper.eq("area", area);
        }

        return Result.success(artistMapper.selectCount(queryWrapper));
    }

    /**
     * 添加歌手
     *
     * @param artistAddDTO 歌手添加DTO
     * @return 添加结果
     */
    @Override
    @CacheEvict(cacheNames = "artistCache", allEntries = true)
    public Result addArtist(ArtistAddDTO artistAddDTO) {
        // 查重只看「启用中」的记录：同名停用记录（例如被合并掉的组合歌手）不应阻止重新创建 ——
        // 所有面向业务的查询都带 status = ACTIVE，因此不会产生归属歧义。
        if (artistNameTaken(artistAddDTO.getArtistName(), null)) {
            return Result.error(MessageConstant.ARTIST + MessageConstant.ALREADY_EXISTS);
        }

        Artist artist = new Artist();
        BeanUtils.copyProperties(artistAddDTO, artist);
        artist.setStatus(ArtistStatusEnum.ACTIVE.getStatus());
        artistMapper.insert(artist);

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 歌手名是否已被某条<b>启用中</b>的记录占用。
     *
     * <p>刻意包含停用记录之外的判断：停用记录（软删除）不算占用，否则「合并掉组合歌手后
     * 想用回原名字」就会被永久卡住。
     */
    private boolean artistNameTaken(String artistName, Long excludeArtistId) {
        if (artistName == null || artistName.isBlank()) {
            return false;
        }
        QueryWrapper<Artist> wrapper = new QueryWrapper<Artist>()
                .eq("name", artistName.trim())
                .eq("status", ArtistStatusEnum.ACTIVE.getStatus());
        if (excludeArtistId != null) {
            wrapper.ne("id", excludeArtistId);
        }
        return artistMapper.selectCount(wrapper) > 0;
    }

    /**
     * 更新歌手
     *
     * @param artistUpdateDTO 歌手更新DTO
     * @return 更新结果
     */
    @Override
    @CacheEvict(cacheNames = "artistCache", allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public Result updateArtist(ArtistUpdateDTO artistUpdateDTO) {
        Long artistId = artistUpdateDTO.getArtistId();

        // 查重同样只看启用记录，并排除自己
        if (artistNameTaken(artistUpdateDTO.getArtistName(), artistId)) {
            return Result.error(MessageConstant.ARTIST + MessageConstant.ALREADY_EXISTS);
        }

        Artist artist = new Artist();
        BeanUtils.copyProperties(artistUpdateDTO, artist);
        if (artistMapper.updateById(artist) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        // tb_song.artist_names 是派生值：歌手名一变，所有引用它的歌曲展示串都必须重建，
        // 否则歌曲列表里会继续显示旧名。重建与上面的改名在同一事务内完成。
        songArtistService.rebuildArtistNamesForArtist(artistId);

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 更新歌手头像
     *
     * @param artistId 歌手id
     * @param avatar   头像
     * @return 更新结果
     */
    @Override
    @CacheEvict(cacheNames = "artistCache", allEntries = true)
    public Result updateArtistAvatar(Long artistId, String avatar) {
        Artist artist = artistMapper.selectById(artistId);
        String avatarUrl = artist.getAvatar();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            minioService.deleteFile(avatarUrl);
        }

        artist.setAvatar(avatar);
        if (artistMapper.updateById(artist) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 删除歌手
     *
     * @param artistId 歌手id
     * @return 删除结果
     */
    @Override
    @CacheEvict(cacheNames = {"artistCache", "songCache"}, allEntries = true)
    public Result deleteArtist(Long artistId) {
        Artist artist = artistMapper.selectById(artistId);
        if (artist == null) {
            return Result.error(MessageConstant.ARTIST + MessageConstant.NOT_FOUND);
        }

        // 软删除，不物理删行：tb_song.artist_id 的外键是 ON DELETE CASCADE，
        // 物理删除会连带删掉该歌手演唱过的全部歌曲。
        // 头像也不删除 —— 记录仍在，恢复后还要用；这里只改状态。
        if (artistMapper.update(null, new UpdateWrapper<Artist>()
                .eq("id", artistId)
                .set("status", ArtistStatusEnum.DISABLED.getStatus())) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 批量删除歌手（同样是软删除）。
     *
     * <p>刻意不做「删除前解绑歌曲关系」：{@code tb_song.artist_names} 保留该歌手的名字，
     * 歌曲列表仍能如实展示「这首歌由他/她演唱」；只是这位歌手不再出现在歌手列表、
     * 选择器、搜索、随机与画像里。详见 docs/phase6-song-multiple-artists.md §7.4。
     */
    @Override
    @CacheEvict(cacheNames = {"artistCache", "songCache"}, allEntries = true)
    public Result deleteArtists(List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        if (artistMapper.update(null, new UpdateWrapper<Artist>()
                .in("id", artistIds)
                .set("status", ArtistStatusEnum.DISABLED.getStatus())) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

}
