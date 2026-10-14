package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.enumeration.LyricStatusEnum;
import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.GenreMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.mapper.StyleMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.SongAddDTO;
import cn.edu.seig.vibemusic.model.dto.SongAndArtistDTO;
import cn.edu.seig.vibemusic.model.dto.SongDTO;
import cn.edu.seig.vibemusic.model.dto.SongUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Genre;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.entity.Style;
import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import cn.edu.seig.vibemusic.model.vo.SongAdminVO;
import cn.edu.seig.vibemusic.model.vo.SongDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ISongService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.SortWhitelistUtil;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
@CacheConfig(cacheNames = "songCache")
public class SongServiceImpl extends ServiceImpl<SongMapper, Song> implements ISongService {

    @Autowired
    private SongMapper songMapper;
    @Autowired
    private AlbumMapper albumMapper;
    @Autowired
    private UserFavoriteMapper userFavoriteMapper;
    @Autowired
    private StyleMapper styleMapper;
    @Autowired
    private GenreMapper genreMapper;
    @Autowired
    private MinioService minioService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private SongArtistService songArtistService;

    /**
     * 获取所有歌曲
     *
     * @param songDTO songDTO
     * @return 歌曲列表
     */
    @Override
    // 返回值包含当前用户收藏状态，不能复用公共响应缓存。
    public Result<PageResult<SongVO>> getAllSongs(SongDTO songDTO, HttpServletRequest request) {
        // 获取请求头中的 token
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7); // 去掉 "Bearer " 前缀
        }

        Map<String, Object> map = null;
        if (token != null && !token.isEmpty()) {
            map = JwtUtil.parseToken(token);
        }

        // 查询歌曲列表
        Page<SongVO> page = new Page<>(songDTO.getPageNum(), songDTO.getPageSize());
        IPage<SongVO> songPage = songMapper.getSongsWithArtist(page, songDTO.getSongName(), songDTO.getArtistName(), songDTO.getAlbum());
        if (songPage.getRecords().isEmpty()) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        // 设置默认状态
        List<SongVO> songVOList = songPage.getRecords().stream()
                .peek(songVO -> songVO.setLikeStatus(LikeStatusEnum.DEFAULT.getId()))
                .toList();

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

        // 结构化歌手列表：走独立批量查询装配，主查询保持零 JOIN。
        songArtistService.attachArtists(songVOList);

        return Result.success(new PageResult<>(songPage.getTotal(), songVOList));
    }

    /**
     * 获取歌手的所有歌曲
     *
     * @param songDTO songAndArtistDTO
     * @return 歌曲列表
     */
    @Override
    // 缓存 key 必须覆盖全部筛选条件，否则不同条件的查询会互相污染（artistName 曾漏掉）。
    // ⚠️ 版本号变更历史：album-v4 → album-v5。多歌手改造把 artistName 的来源从
    //    「JOIN tb_artist」换成了「主表 artist_names」，结果集变了但 key 没变，
    //    Redis 里的旧条目会继续返回老歌手名 —— 所以必须升版本号让旧缓存自然失效。
    //    v5 → v6：新增排序参数，排序条件并入 key。
    @Cacheable(key = "#songDTO.pageNum + '-' + #songDTO.pageSize + '-' + #songDTO.songName + '-' + #songDTO.artistName + '-' + #songDTO.album + '-' + #songDTO.artistId + '-' + #songDTO.onlyMissingLyric + '-' + #songDTO.sortField + '-' + #songDTO.order + '-album-v6'")
    public Result<PageResult<SongAdminVO>> getAllSongsByArtist(SongAndArtistDTO songDTO) {
        // 分页查询
        Page<SongAdminVO> page = new Page<>(songDTO.getPageNum(), songDTO.getPageSize());
        // 排序：仅接受白名单字段，未命中/缺省时按发行日期倒序（历史行为）
        String orderBy = SortWhitelistUtil.toOrderBy(
                songDTO.getSortField(), songDTO.getOrder(),
                Map.of(
                        "songId", "s.id",
                        "songName", "CONVERT(s.name USING gbk)", // 拼音排序：中文按拼音、英文按字母
                        "releaseTime", "s.release_time"
                ),
                "s.release_time DESC");
        IPage<SongAdminVO> songPage = songMapper.getSongsWithArtistName(page, songDTO.getArtistId(), songDTO.getSongName(), songDTO.getArtistName(), songDTO.getAlbum(), songDTO.getOnlyMissingLyric(), orderBy);

        if (songPage.getRecords().isEmpty()) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        List<SongAdminVO> records = songPage.getRecords();
        // 管理端编辑表单需要按 display_order 回填多选值，这里一并装配。
        songArtistService.attachArtists(records);

        return Result.success(new PageResult<>(songPage.getTotal(), records));
    }

    /**
     * 获取推荐歌曲
     * 推荐歌曲的数量为 20
     *
     * @param request HttpServletRequest，用于获取请求头中的 token
     * @return 推荐歌曲列表
     */
    @Override
    public Result<List<SongVO>> getRecommendedSongs(HttpServletRequest request) {
        // 获取请求头中的 token
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);  // 去掉 "Bearer " 前缀
        }

        Map<String, Object> map = null;
        if (token != null && !token.isEmpty()) {
            map = JwtUtil.parseToken(token);
        }

        // 用户未登录，返回随机歌曲列表
        if (map == null) {
            return Result.success(songMapper.getRandomSongsWithArtist());
        }

        // 获取用户 ID
        Long userId = TypeConversionUtil.toLong(map.get(JwtClaimsConstant.USER_ID));

        // 查询用户收藏的歌曲 ID  如果没有 则随机返回
        List<Long> favoriteSongIds = userFavoriteMapper.getFavoriteSongIdsByUserId(userId);
        if (favoriteSongIds.isEmpty()) {
            return Result.success(songMapper.getRandomSongsWithArtist());
        }

        // 查询用户收藏的歌曲风格并统计频率
        List<Long> favoriteStyleIds = songMapper.getFavoriteSongStyles(favoriteSongIds);
        Map<Long, Long> styleFrequency = favoriteStyleIds.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        // 按风格出现次数降序排序
        List<Long> sortedStyleIds = styleFrequency.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 从 Redis 获取缓存的推荐列表
        String redisKey = "recommended_songs:" + userId;
        List<SongVO> cachedSongs = redisTemplate.opsForList().range(redisKey, 0, -1);

        // 如果 Redis 没有缓存，则查询数据库并缓存
        if (cachedSongs == null || cachedSongs.isEmpty()) {
            // 根据排序后的风格推荐歌曲（排除已收藏歌曲）
            cachedSongs = songMapper.getRecommendedSongsByStyles(sortedStyleIds, favoriteSongIds, 80);
            redisTemplate.opsForList().rightPushAll(redisKey, cachedSongs);
            redisTemplate.expire(redisKey, 30, TimeUnit.MINUTES); // 设置过期时间 30 分钟
        }

        // 随机选取 20 首
        Collections.shuffle(cachedSongs);
        List<SongVO> recommendedSongs = cachedSongs.subList(0, Math.min(20, cachedSongs.size()));

        // 如果推荐的歌曲不足 20 首，则用随机歌曲填充
        if (recommendedSongs.size() < 20) {
            List<SongVO> randomSongs = songMapper.getRandomSongsWithArtist();
            Set<Long> addedSongIds = recommendedSongs.stream().map(SongVO::getSongId).collect(Collectors.toSet());
            for (SongVO song : randomSongs) {
                if (recommendedSongs.size() >= 20) break;
                if (!addedSongIds.contains(song.getSongId())) {
                    recommendedSongs.add(song);
                }
            }
        }

        // 最终返回前才装配：上面的列表可能来自 Redis 缓存（缓存里只有展示串），
        // 统一在这里补齐结构化歌手，避免缓存内容与 artists 字段不一致。
        songArtistService.attachArtists(recommendedSongs);

        return Result.success(recommendedSongs);
    }

    /**
     * 获取歌曲详情
     *
     * @param songId  歌曲id
     * @param request HttpServletRequest，用于获取请求头中的 token
     * @return 歌曲详情
     */
    @Override
    // 返回值包含当前用户收藏状态，不能复用公共响应缓存。
    public Result<SongDetailVO> getSongDetail(Long songId, HttpServletRequest request) {
        SongDetailVO songDetailVO = songMapper.getSongDetailById(songId);

        // 获取请求头中的 token
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);  // 去掉 "Bearer " 前缀
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
                UserFavorite favoriteSong = userFavoriteMapper.selectOne(new QueryWrapper<UserFavorite>()
                        .eq("user_id", userId)
                        .eq("type", 0)
                        .eq("song_id", songId));
                if (favoriteSong != null) {
                    songDetailVO.setLikeStatus(LikeStatusEnum.LIKE.getId());
                }
            }
        }

        songArtistService.attachArtists(songDetailVO);

        return Result.success(songDetailVO);
    }

    /**
     * 获取所有歌曲的数量
     *
     * @param style 歌曲风格
     * @return 歌曲数量
     */
    @Override
    public Result<Long> getAllSongsCount(String style) {
        QueryWrapper<Song> queryWrapper = new QueryWrapper<>();
        if (style != null) {
            queryWrapper.like("style", style);
        }

        return Result.success(songMapper.selectCount(queryWrapper));
    }

    /**
     * 添加歌曲信息
     *
     * @param songAddDTO 歌曲信息
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public Result addSong(SongAddDTO songAddDTO) {
        // 歌手：artistIds 优先（顺序即展示顺序），回退到旧的单值 artistId。
        // 两者都为空就无法确定主艺人（tb_song.artist_id 是 NOT NULL），直接拒绝而不是猜。
        List<Long> artistIds = SongArtistService.normalizeArtistIds(
                songAddDTO.getArtistIds(), songAddDTO.getArtistId());
        if (artistIds.isEmpty()) {
            return Result.error("请至少选择一位歌手");
        }
        Long primaryArtistId = artistIds.get(0);

        Song song = new Song();
        BeanUtils.copyProperties(songAddDTO, song);
        // 专辑归属仍按主艺人解析（tb_album 只有一个主艺人，见 docs §7.4）。
        song.setArtistId(primaryArtistId);
        Long albumId = resolveAlbumId(
                primaryArtistId,
                songAddDTO.getAlbum(),
                songAddDTO.getAlbumId(),
                songAddDTO.getReleaseTime(),
                null
        );
        song.setAlbumId(albumId);
        song.setDiscNumber(songAddDTO.getDiscNumber() == null ? 1 : songAddDTO.getDiscNumber());
        song.setTrackNumber(resolveNextTrackNumber(albumId, songAddDTO.getTrackNumber()));
        if (Boolean.TRUE.equals(songAddDTO.getInstrumental())) {
            song.setLyric(null);
            song.setLyricSource("INSTRUMENTAL");
            song.setLyricStatus(LyricStatusEnum.INSTRUMENTAL.getId());
            song.setLyricUpdatedAt(java.time.LocalDateTime.now());
        }

        // 插入歌曲记录
        if (songMapper.insert(song) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }

        // 获取刚插入的歌曲记录（按主艺人 + 歌名 + 专辑名回查）
        Song songInDB = songMapper.selectOne(new QueryWrapper<Song>()
                .eq("artist_id", primaryArtistId)
                .eq("name", songAddDTO.getSongName())
                .eq("album", songAddDTO.getAlbum())
                .orderByDesc("id")
                .last("LIMIT 1"));

        if (songInDB == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }

        Long songId = songInDB.getSongId();

        // 关系表 + artist_names + 主艺人：与上面的插入在同一个事务里，任一步失败整体回滚。
        songArtistService.replaceSongArtists(songId, artistIds);

        // 解析风格字段（多个风格以逗号分隔）
        String styleStr = songAddDTO.getStyle();
        if (styleStr != null && !styleStr.isEmpty()) {
            List<String> styles = Arrays.asList(styleStr.split(","));

            // 查询风格 ID
            List<Style> styleList = styleMapper.selectList(new QueryWrapper<Style>().in("name", styles));

            // 插入到 tb_genre
            for (Style style : styleList) {
                Genre genre = new Genre();
                genre.setSongId(songId);
                genre.setStyleId(style.getStyleId());
                genreMapper.insert(genre);
            }
        }

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 更新歌曲信息
     *
     * @param songUpdateDTO 歌曲信息
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public Result updateSong(SongUpdateDTO songUpdateDTO) {
        // 查询数据库中是否存在该歌曲
        Song songInDB = songMapper.selectById(songUpdateDTO.getSongId());
        if (songInDB == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }

        // 更新歌曲基本信息
        Song song = new Song();
        BeanUtils.copyProperties(songUpdateDTO, song);
        Long previousAlbumId = songInDB.getAlbumId();
        // 歌手关系的三种语义：
        //   artistIds 非空          → 整组替换，顺序即展示顺序；
        //   只给 artistId           → 视为「改为这一位歌手」（兼容旧客户端）；
        //   两者都空                → 保持原有关系不变（例如只改歌词或专辑）。
        List<Long> requestedArtistIds = SongArtistService.normalizeArtistIds(
                songUpdateDTO.getArtistIds(), songUpdateDTO.getArtistId());
        boolean artistRelationProvided = !requestedArtistIds.isEmpty();
        Long artistId = artistRelationProvided ? requestedArtistIds.get(0) : songInDB.getArtistId();
        song.setArtistId(artistId);

        String normalizedAlbumName = songUpdateDTO.getAlbum() == null
                ? null
                : songUpdateDTO.getAlbum().trim();
        Long albumId = songUpdateDTO.getAlbumId();
        if (albumId == null && normalizedAlbumName != null && !normalizedAlbumName.isEmpty()) {
            Album existingAlbum = findAlbum(artistId, normalizedAlbumName);
            if (existingAlbum == null && !Boolean.TRUE.equals(songUpdateDTO.getConfirmCreateAlbum())) {
                return Result.error("专辑「" + normalizedAlbumName + "」不存在，请确认创建后再保存");
            }
            albumId = existingAlbum == null
                    ? createAlbum(artistId, normalizedAlbumName, songUpdateDTO.getReleaseTime(), songInDB.getCoverUrl()).getAlbumId()
                    : existingAlbum.getAlbumId();
        }

        if (albumId != null) {
            song.setAlbumId(albumId);
            if (!Objects.equals(previousAlbumId, albumId)) {
                song.setDiscNumber(songUpdateDTO.getDiscNumber() == null ? 1 : songUpdateDTO.getDiscNumber());
                song.setTrackNumber(resolveNextTrackNumber(albumId, songUpdateDTO.getTrackNumber()));
            } else if (songUpdateDTO.getTrackNumber() != null) {
                song.setTrackNumber(songUpdateDTO.getTrackNumber());
            }
        }
        if (songMapper.updateById(song) == 0) {
            deleteEmptyAlbum(albumId != null && !Objects.equals(previousAlbumId, albumId) ? albumId : null);
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        // 关系表 + 展示串（同一事务）。放在这里而不是方法末尾：上面若提前 return 错误，
        // 就不应该已经改掉歌手关系；而这里之后的步骤都不再可能提前返回。
        if (artistRelationProvided) {
            songArtistService.replaceSongArtists(songUpdateDTO.getSongId(), requestedArtistIds);
        }

        String normalizedLyric = songUpdateDTO.getLyric() == null
                ? null
                : songUpdateDTO.getLyric().trim();
        boolean markInstrumental = Boolean.TRUE.equals(songUpdateDTO.getInstrumental());
        boolean unmarkInstrumental = Boolean.FALSE.equals(songUpdateDTO.getInstrumental())
                && Objects.equals(songInDB.getLyricStatus(), LyricStatusEnum.INSTRUMENTAL.getId());
        String existingLyric = songInDB.getLyric() == null ? null : songInDB.getLyric().trim();
        boolean lyricChanged = normalizedLyric != null && !Objects.equals(normalizedLyric, existingLyric);

        if (markInstrumental) {
            songMapper.update(null, new UpdateWrapper<Song>()
                    .eq("id", songUpdateDTO.getSongId())
                    .set("lyric", null)
                    .set("lyric_source", "INSTRUMENTAL")
                    .set("lyric_status", LyricStatusEnum.INSTRUMENTAL.getId())
                    .set("lyric_updated_at", java.time.LocalDateTime.now()));
        } else if (unmarkInstrumental || lyricChanged) {
            boolean hasLyric = normalizedLyric != null && !normalizedLyric.isBlank();
            UpdateWrapper<Song> lyricUpdate = new UpdateWrapper<Song>()
                    .eq("id", songUpdateDTO.getSongId())
                    .set("lyric", hasLyric ? normalizedLyric : null)
                    .set("lyric_source", hasLyric ? "MANUAL" : null)
                    .set("lyric_status", hasLyric
                            ? LyricStatusEnum.MANUAL.getId()
                            : LyricStatusEnum.MISSING.getId())
                    .set("lyric_updated_at", java.time.LocalDateTime.now());
            songMapper.update(null, lyricUpdate);
        }

        if (albumId == null) {
            songMapper.update(null, new UpdateWrapper<Song>()
                    .eq("id", songUpdateDTO.getSongId())
                    .set("album_id", null));
        }

        Long songId = songUpdateDTO.getSongId();

        // 删除 tb_genre 中该歌曲的原有风格映射
        genreMapper.delete(new QueryWrapper<Genre>().eq("song_id", songId));

        // 解析新的风格字段（多个风格以逗号分隔）
        String styleStr = songUpdateDTO.getStyle();
        if (styleStr != null && !styleStr.isEmpty()) {
            List<String> styles = Arrays.asList(styleStr.split(","));

            // 查询风格 ID
            List<Style> styleList = styleMapper.selectList(new QueryWrapper<Style>().in("name", styles));

            // 插入新的风格映射到 tb_genre
            for (Style style : styleList) {
                Genre genre = new Genre();
                genre.setSongId(songId);
                genre.setStyleId(style.getStyleId());
                genreMapper.insert(genre);
            }
        }

        if (previousAlbumId != null && !Objects.equals(previousAlbumId, albumId)) {
            deleteEmptyAlbum(previousAlbumId);
        }

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    private Long resolveAlbumId(Long artistId, String albumName, Long albumId,
                                java.time.LocalDate releaseDate, String coverUrl) {
        if (albumId != null) {
            return albumId;
        }
        if (artistId == null || albumName == null || albumName.isBlank()) {
            return null;
        }

        String normalizedName = albumName.trim();
        Album album = findAlbum(artistId, normalizedName);
        if (album == null) {
            return createAlbum(artistId, normalizedName, releaseDate, coverUrl).getAlbumId();
        }

        boolean changed = false;
        if (album.getCoverUrl() == null && coverUrl != null) {
            album.setCoverUrl(coverUrl);
            changed = true;
        }
        if (album.getReleaseDate() == null && releaseDate != null) {
            album.setReleaseDate(releaseDate);
            changed = true;
        }
        if (changed) {
            albumMapper.updateById(album);
        }
        return album.getAlbumId();
    }

    private Album findAlbum(Long artistId, String albumName) {
        if (artistId == null || albumName == null || albumName.isBlank()) {
            return null;
        }
        return albumMapper.selectOne(new QueryWrapper<Album>()
                .eq("artist_id", artistId)
                .eq("name", albumName.trim())
                .last("LIMIT 1"));
    }

    private Album createAlbum(Long artistId, String albumName,
                              java.time.LocalDate releaseDate, String coverUrl) {
        Album album = new Album()
                .setArtistId(artistId)
                .setAlbumName(albumName.trim())
                .setReleaseDate(releaseDate)
                .setCoverUrl(coverUrl);
        albumMapper.insert(album);
        return album;
    }

    private void deleteEmptyAlbum(Long albumId) {
        if (albumId == null) {
            return;
        }
        Long songCount = songMapper.selectCount(new QueryWrapper<Song>().eq("album_id", albumId));
        if (songCount == null || songCount == 0) {
            albumMapper.deleteById(albumId);
        }
    }

    private Integer resolveNextTrackNumber(Long albumId, Integer trackNumber) {
        if (trackNumber != null && trackNumber > 0) {
            return trackNumber;
        }
        if (albumId == null) {
            return 1;
        }
        Integer nextTrackNumber = albumMapper.getNextTrackNumber(albumId);
        return nextTrackNumber == null ? 1 : nextTrackNumber;
    }

    /**
     * 更新歌曲封面
     *
     * @param songId   歌曲id
     * @param coverUrl 封面url
     * @return 更新结果
     */
    @Override
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result updateSongCover(Long songId, String coverUrl) {
        Song song = songMapper.selectById(songId);
        String cover = song.getCoverUrl();
        if (cover != null && !cover.isEmpty()) {
            minioService.deleteFile(cover);
        }

        song.setCoverUrl(coverUrl);
        if (songMapper.updateById(song) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 更新歌曲音频
     *
     * @param songId   歌曲id
     * @param audioUrl 音频url
     * @return 更新结果
     */
    @Override
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result updateSongAudio(Long songId, String audioUrl) {
        Song song = songMapper.selectById(songId);
        String audio = song.getAudioUrl();
        if (audio != null && !audio.isEmpty()) {
            minioService.deleteFile(audio);
        }

        song.setAudioUrl(audioUrl);
        if (songMapper.updateById(song) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 删除歌曲
     *
     * @param songId 歌曲id
     * @return 删除结果
     */
    @Override
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result deleteSong(Long songId) {
        Song song = songMapper.selectById(songId);
        if (song == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }
        String cover = song.getCoverUrl();
        String audio = song.getAudioUrl();

        if (cover != null && !cover.isEmpty()) {
            minioService.deleteFile(cover);
        }
        if (audio != null && !audio.isEmpty()) {
            minioService.deleteFile(audio);
        }

        if (songMapper.deleteById(songId) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 批量删除歌曲
     *
     * @param songIds 歌曲id列表
     * @return 删除结果
     */
    @Override
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result deleteSongs(List<Long> songIds) {
        // 1. 查询歌曲信息，获取歌曲封面 URL 列表
        List<Song> songs = songMapper.selectByIds(songIds);
        List<String> coverUrlList = songs.stream()
                .map(Song::getCoverUrl)
                .filter(coverUrl -> coverUrl != null && !coverUrl.isEmpty())
                .toList();
        List<String> audioUrlList = songs.stream()
                .map(Song::getAudioUrl)
                .filter(audioUrl -> audioUrl != null && !audioUrl.isEmpty())
                .toList();

        // 2. 先删除 MinIO 里的歌曲封面和音频文件
        for (String coverUrl : coverUrlList) {
            minioService.deleteFile(coverUrl);
        }
        for (String audioUrl : audioUrlList) {
            minioService.deleteFile(audioUrl);
        }

        // 3. 删除数据库中的歌曲信息
        if (songMapper.deleteByIds(songIds) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

}
