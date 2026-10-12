package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.AdminPlaylistQueryDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistAddDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistSongsDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.vo.AdminPlaylistVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
public interface IPlaylistService extends IService<Playlist> {

    // 获取所有歌单（仅公开且已发布）
    Result<PageResult<PlaylistVO>> getAllPlaylists(PlaylistDTO playlistDTO);

    // 管理员获取平台歌单（含草稿，可按发布状态与可见性筛选）
    Result<PageResult<AdminPlaylistVO>> getAllPlaylistsInfo(AdminPlaylistQueryDTO playlistDTO);

    // 获取推荐歌单
    Result<List<PlaylistVO>> getRecommendedPlaylists(HttpServletRequest request);

    // 根据id获取歌单详情
    Result<PlaylistDetailVO> getPlaylistDetail(Long playlistId, HttpServletRequest request);

    // 管理员获取平台歌单详情（含草稿与私人歌单）
    Result<PlaylistDetailVO> getAdminPlaylistDetail(Long playlistId);

    // 获取所有歌单数量
    Result<Long> getAllPlaylistsCount(String style);

    // 添加歌单（管理员，始终创建为草稿）
    Result<PlaylistDetailVO> addPlaylist(PlaylistAddDTO playlistAddDTO);

    // 更新歌单基本信息
    Result<PlaylistDetailVO> updatePlaylist(PlaylistUpdateDTO playlistUpdateDTO);

    // 更新歌单封面
    Result updatePlaylistCover(Long playlistId, String coverUrl);

    // 发布歌单（平台歌单固定为公开）
    Result<PlaylistDetailVO> publishPlaylist(Long playlistId);

    // 撤回为草稿
    Result<PlaylistDetailVO> unpublishPlaylist(Long playlistId);

    // 为平台歌单添加歌曲
    Result<PlaylistDetailVO> addPlaylistSongs(Long playlistId, PlaylistSongsDTO input);

    // 从平台歌单移除歌曲
    Result<PlaylistDetailVO> removePlaylistSong(Long playlistId, Long songId);

    // 删除歌单
    Result deletePlaylist(Long playlistId);

    // 批量删除歌单
    Result deletePlaylists(List<Long> playlistIds);

}
