package cn.edu.seig.vibemusic.controller;


import cn.edu.seig.vibemusic.model.dto.*;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.vo.AdminPlaylistVO;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongAdminVO;
import cn.edu.seig.vibemusic.model.vo.UserManagementVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.*;
import cn.edu.seig.vibemusic.util.BindingResultUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private IAdminService adminService;
    @Autowired
    private IUserService userService;
    @Autowired
    private IArtistService artistService;
    @Autowired
    private ISongService songService;
    @Autowired
    private IAlbumService albumService;
    @Autowired
    private IPlaylistService playlistService;
    @Autowired
    private MinioService minioService;

    // 管理员注册端点（POST /admin/register）已于 2026-10-15 下线。
    // 原因：该路径曾被 WebConfig 排除鉴权，匿名调用者可创建管理员并登录管理端。
    // 新增管理员请走受控入口（运维 SQL 或后续设计的受保护接口），不要在此恢复公开注册。

    /**
     * 登录管理员
     *
     * @param adminDTO      管理员信息
     * @param bindingResult 绑定结果
     * @return 结果
     */
    @PostMapping("/login")
    public Result login(@RequestBody @Valid AdminDTO adminDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return adminService.login(adminDTO);
    }

    /**
     * 登出
     *
     * @param token 认证token
     * @return 结果
     *
     * @RequestHeader("Authorization") String token：
     * 从请求头中提取 Authorization 字段
     * 成功在redis删除token
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader("Authorization") String token) {
        return adminService.logout(token);
    }

    /**********************************************************************************************/

    /**
     * 获取所有用户数量
     *
     * @return 用户数量
     */
    @GetMapping("/getAllUsersCount")
    public Result<Long> getAllUsersCount() {
        return userService.getAllUsersCount();
    }

    /**
     * 获取所有用户信息
     *
     * @param userSearchDTO 用户搜索条件
     * @return 结果
     */
    @PostMapping("/getAllUsers")
    public Result<PageResult<UserManagementVO>> getAllUsers(@RequestBody @Valid UserSearchDTO userSearchDTO) {
        return userService.getAllUsers(userSearchDTO);
    }

    /**
     * 新增用户
     *
     * @param userAddDTO 用户注册信息
     * @return 结果
     */
    @PostMapping("/addUser")
    public Result addUser(@RequestBody @Valid UserAddDTO userAddDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.addUser(userAddDTO);
    }

    /**
     * 更新用户信息
     *
     * @param userDTO 用户信息
     * @return 结果
     */
    @PutMapping("/updateUser")
    public Result updateUser(@RequestBody @Valid UserDTO userDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.updateUser(userDTO);
    }

    /**
     * 更新用户状态
     *
     * @param userId     用户id
     * @param userStatus 用户状态
     * @return 结果
     */
    @PatchMapping("/updateUserStatus/{id}/{status}")
    public Result updateUserStatus(@PathVariable("id") Long userId, @PathVariable("status") Integer userStatus) {
        return userService.updateUserStatus(userId, userStatus);
    }

    /**
     * 删除用户
     *
     * @param userId 用户id
     * @return 结果
     */
    @DeleteMapping("/deleteUser/{id}")
    public Result deleteUser(@PathVariable("id") Long userId) {
        return userService.deleteUser(userId);
    }

    /**
     * 批量删除用户
     *
     * @param userIds 用户id列表
     * @return 结果
     */
    @DeleteMapping("/deleteUsers")
    public Result deleteUsers(@RequestBody List<Long> userIds) {
        return userService.deleteUsers(userIds);
    }

    /**********************************************************************************************/

    /**
     * 获取所有歌手数量
     *
     * @param gender 性别
     * @param area   地区
     * @return 歌手数量
     */
    @GetMapping("/getAllArtistsCount")
    public Result<Long> getAllArtistsCount(@RequestParam(required = false) Integer gender, @RequestParam(required = false) String area) {
        return artistService.getAllArtistsCount(gender, area);
    }

    /**
     * 获取所有歌手信息
     *
     * @param artistDTO 歌手搜索条件
     * @return 结果
     */
    @PostMapping("/getAllArtists")
    public Result<PageResult<Artist>> getAllArtists(@RequestBody @Valid ArtistDTO artistDTO) {
        return artistService.getAllArtistsAndDetail(artistDTO);
    }

    /**
     * 新增歌手
     *
     * @param artistAddDTO 歌手信息
     * @return 结果
     */
    @PostMapping("/addArtist")
    public Result addArtist(@RequestBody ArtistAddDTO artistAddDTO) {
        return artistService.addArtist(artistAddDTO);
    }

    /**
     * 更新歌手信息
     *
     * @param artistUpdateDTO 歌手信息
     * @return 结果
     */
    @PutMapping("/updateArtist")
    public Result updateArtist(@RequestBody ArtistUpdateDTO artistUpdateDTO) {
        return artistService.updateArtist(artistUpdateDTO);
    }

    /**
     * 更新歌手头像
     *
     * @param artistId 歌手id
     * @param avatar   头像
     * @return 结果
     */
    @PatchMapping("/updateArtistAvatar/{id}")
    public Result updateArtistAvatar(@PathVariable("id") Long artistId, @RequestParam("avatar") MultipartFile avatar) {
        String avatarUrl = minioService.uploadFile(avatar, "artists");  // 上传到 artists 目录
        return artistService.updateArtistAvatar(artistId, avatarUrl);
    }

    /**
     * 删除歌手
     *
     * @param artistId 歌手id
     * @return 结果
     */
    @DeleteMapping("/deleteArtist/{id}")
    public Result deleteArtist(@PathVariable("id") Long artistId) {
        return artistService.deleteArtist(artistId);
    }

    /**
     * 批量删除歌手
     *
     * @param artistIds 歌手id列表
     * @return 结果
     */
    @DeleteMapping("/deleteArtists")
    public Result deleteArtists(@RequestBody List<Long> artistIds) {
        return artistService.deleteArtists(artistIds);
    }

    /**********************************************************************************************/

    /**
     * 获取所有歌曲的数量
     *
     * @param style 歌曲风格
     * @return 歌曲数量
     */
    @GetMapping("/getAllSongsCount")
    public Result<Long> getAllSongsCount(@RequestParam(required = false) String style) {
        return songService.getAllSongsCount(style);
    }

    /**
     * 获取所有歌手id和名称
     *
     * @return 结果
     */
    @GetMapping("/getAllArtistNames")
    public Result<List<ArtistNameVO>> getAllArtistNames() {
        return artistService.getAllArtistNames();
    }

    /**
     * 根据歌手id获取其歌曲信息
     *
     * @param songDTO 歌曲搜索条件
     * @return 结果
     */
    @PostMapping("/getAllSongsByArtist")
    public Result<PageResult<SongAdminVO>> getAllSongsByArtist(@RequestBody @Valid SongAndArtistDTO songDTO) {
        return songService.getAllSongsByArtist(songDTO);
    }

    /**
     * 添加歌曲信息
     *
     * @param songAddDTO 歌曲信息
     * @return 结果
     */
    @PostMapping("/addSong")
    public Result addSong(@RequestBody SongAddDTO songAddDTO) {
        return songService.addSong(songAddDTO);
    }

    /**
     * 修改歌曲信息
     *
     * @param songUpdateDTO 歌曲信息
     * @return 结果
     */
    @PutMapping("/updateSong")
    public Result UpdateSong(@RequestBody SongUpdateDTO songUpdateDTO) {
        return songService.updateSong(songUpdateDTO);
    }

    /**
     * 检查指定艺人的专辑是否存在。
     */
    @GetMapping("/checkAlbumExists")
    public Result<Boolean> checkAlbumExists(@RequestParam Long artistId,
                                            @RequestParam String albumName) {
        return albumService.albumExists(artistId, albumName);
    }

    /**
     * 更新歌曲封面
     *
     * @param songId 歌曲id
     * @param cover  封面
     * @return 结果
     */
    @PatchMapping("/updateSongCover/{id}")
    public Result updateSongCover(@PathVariable("id") Long songId, @RequestParam("cover") MultipartFile cover) {
        String coverUrl = minioService.uploadFile(cover, "songCovers");  // 上传到 songCovers 目录
        return songService.updateSongCover(songId, coverUrl);
    }

    /**
     * 更新歌曲音频
     *
     * @param songId 歌曲id
     * @param audio  音频
     * @return 结果
     */
    @PatchMapping("/updateSongAudio/{id}")
    public Result updateSongAudio(@PathVariable("id") Long songId, @RequestParam("audio") MultipartFile audio) {
        String audioUrl = minioService.uploadFile(audio, "songs");  // 上传到 songs 目录
        return songService.updateSongAudio(songId, audioUrl);
    }

    /**
     * 删除歌曲
     *
     * @param songId 歌曲id
     * @return 结果
     */
    @DeleteMapping("/deleteSong/{id}")
    public Result deleteSong(@PathVariable("id") Long songId) {
        return songService.deleteSong(songId);
    }

    /**
     * 批量删除歌曲
     *
     * @param songIds 歌曲id列表
     * @return 结果
     */
    @DeleteMapping("/deleteSongs")
    public Result deleteSongs(@RequestBody List<Long> songIds) {
        return songService.deleteSongs(songIds);
    }

    /**********************************************************************************************/

    /**
     * 获取所有专辑数量
     */
    @GetMapping("/getAllAlbumsCount")
    public Result<Long> getAllAlbumsCount() {
        return albumService.getAllAlbumsCount();
    }

    /**
     * 获取所有专辑信息
     */
    @PostMapping("/getAllAlbums")
    public Result<PageResult<cn.edu.seig.vibemusic.model.vo.AlbumVO>> getAllAlbums(@RequestBody @Valid AlbumQueryDTO queryDTO) {
        if (queryDTO.getStatus() != null) {
            queryDTO.setStatus(queryDTO.getStatus().trim().toUpperCase());
        }
        return albumService.getAlbumList(queryDTO);
    }

    /**
     * 新增专辑
     */
    @PostMapping("/addAlbum")
    public Result addAlbum(@RequestBody AlbumAddDTO albumAddDTO) {
        return albumService.addAlbum(albumAddDTO);
    }

    /**
     * 更新专辑信息
     */
    @PutMapping("/updateAlbum")
    public Result updateAlbum(@RequestBody AlbumUpdateDTO albumUpdateDTO) {
        return albumService.updateAlbum(albumUpdateDTO);
    }

    /**
     * 更新专辑封面
     */
    @PatchMapping("/updateAlbumCover/{id}")
    public Result updateAlbumCover(@PathVariable("id") Long albumId,
                                   @RequestParam("cover") MultipartFile cover) {
        String coverUrl = minioService.uploadFile(cover, "albumCovers");
        return albumService.updateAlbumCover(albumId, coverUrl);
    }

    /**
     * 删除专辑
     */
    @DeleteMapping("/deleteAlbum/{id}")
    public Result deleteAlbum(@PathVariable("id") Long albumId,
                              @RequestParam(value = "deleteSongs", defaultValue = "false") boolean deleteSongs) {
        return albumService.deleteAlbum(albumId, deleteSongs);
    }

    /**
     * 批量删除专辑
     */
    @DeleteMapping("/deleteAlbums")
    public Result deleteAlbums(@RequestBody List<Long> albumIds) {
        return albumService.deleteAlbums(albumIds);
    }

    /**********************************************************************************************/

    /**
     * 获取所有歌单数量
     *
     * @param style 歌单风格
     * @return 歌单数量
     */
    @GetMapping("/getAllPlaylistsCount")
    public Result<Long> getAllPlaylistsCount(@RequestParam(required = false) String style) {
        return playlistService.getAllPlaylistsCount(style);
    }

    /**
     * 获取平台歌单信息（含草稿）
     *
     * @param queryDTO 歌单搜索条件：名称、风格、发布状态、可见性
     * @return 结果
     */
    @PostMapping("/getAllPlaylists")
    public Result<PageResult<AdminPlaylistVO>> getAllPlaylists(@RequestBody @Valid AdminPlaylistQueryDTO queryDTO) {
        return playlistService.getAllPlaylistsInfo(queryDTO);
    }

    /**
     * 获取平台歌单详情（含草稿与私人歌单的歌曲列表）
     *
     * @param playlistId 歌单id
     * @return 结果
     */
    @GetMapping("/getPlaylistDetail/{id}")
    public Result<PlaylistDetailVO> getPlaylistDetail(@PathVariable("id") Long playlistId) {
        return playlistService.getAdminPlaylistDetail(playlistId);
    }

    /**
     * 新增歌单，默认创建为草稿
     *
     * @param playlistAddDTO 歌单信息
     * @return 结果
     */
    @PostMapping("/addPlaylist")
    public Result<PlaylistDetailVO> addPlaylist(@RequestBody @Valid PlaylistAddDTO playlistAddDTO) {
        return playlistService.addPlaylist(playlistAddDTO);
    }

    /**
     * 更新歌单信息
     *
     * @param playlistUpdateDTO 歌单信息
     * @return 结果
     */
    @PutMapping("/updatePlaylist")
    public Result<PlaylistDetailVO> updatePlaylist(@RequestBody @Valid PlaylistUpdateDTO playlistUpdateDTO) {
        return playlistService.updatePlaylist(playlistUpdateDTO);
    }

    /**
     * 发布歌单（平台歌单固定为公开）
     *
     * @param playlistId 歌单id
     * @return 结果
     */
    @PatchMapping("/publishPlaylist/{id}")
    public Result<PlaylistDetailVO> publishPlaylist(@PathVariable("id") Long playlistId) {
        return playlistService.publishPlaylist(playlistId);
    }

    /**
     * 撤回为草稿
     *
     * @param playlistId 歌单id
     * @return 结果
     */
    @PatchMapping("/unpublishPlaylist/{id}")
    public Result<PlaylistDetailVO> unpublishPlaylist(@PathVariable("id") Long playlistId) {
        return playlistService.unpublishPlaylist(playlistId);
    }

    /**
     * 为歌单添加歌曲
     *
     * @param playlistId 歌单id
     * @param input      歌曲 id 列表
     * @return 结果
     */
    @PostMapping("/addPlaylistSongs/{id}")
    public Result<PlaylistDetailVO> addPlaylistSongs(@PathVariable("id") Long playlistId,
                                                     @RequestBody @Valid PlaylistSongsDTO input) {
        return playlistService.addPlaylistSongs(playlistId, input);
    }

    /**
     * 从歌单移除歌曲
     *
     * @param playlistId 歌单id
     * @param songId     歌曲id
     * @return 结果
     */
    @DeleteMapping("/removePlaylistSong/{playlistId}/{songId}")
    public Result<PlaylistDetailVO> removePlaylistSong(@PathVariable Long playlistId, @PathVariable Long songId) {
        return playlistService.removePlaylistSong(playlistId, songId);
    }

    /**
     * 更新歌单封面
     *
     * @param playlistId 歌单id
     * @param cover      封面
     * @return 结果
     */
    @PatchMapping("/updatePlaylistCover/{id}")
    public Result updatePlaylistCover(@PathVariable("id") Long playlistId, @RequestParam("cover") MultipartFile cover) {
        String coverUrl = minioService.uploadFile(cover, "playlists");  // 上传到 playlists 目录
        return playlistService.updatePlaylistCover(playlistId, coverUrl);
    }

    /**
     * 删除歌单
     *
     * @param playlistId 歌单id
     * @return 结果
     */
    @DeleteMapping("/deletePlaylist/{id}")
    public Result deletePlaylist(@PathVariable("id") Long playlistId) {
        return playlistService.deletePlaylist(playlistId);
    }

    /**
     * 批量删除歌单
     *
     * @param playlistIds 歌单id列表
     * @return 结果
     */
    @DeleteMapping("/deletePlaylists")
    public Result deletePlaylists(@RequestBody List<Long> playlistIds) {
        return playlistService.deletePlaylists(playlistIds);
    }

}
