import { http } from "@/utils/http";
import { getToken } from "@/utils/auth";

export type Result = {
  code: number;
  message: string;
  data?: Array<any> | number | string | object;
};

export type ResultTable = {
  code: number;
  message: string;
  data?: {
    /** 列表数据 */
    items: Array<any>;
    /** 总条目数 */
    total?: number;
    /** 每页显示条目个数 */
    pageSize?: number;
    /** 当前页数 */
    currentPage?: number;
  };
};

/** 用户管理-获取用户列表 */
export const getUserList = (data: object) => {
  const userData = getToken();
  return http.request<ResultTable>("post", "/admin/getAllUsers", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    }, // 确保是 JSON
    data // 直接传 JSON 对象
  });
};

/** 用户管理-新增用户 */
export const addUser = (data: object) => {
  const userData = getToken();
  return http.request<Result>("post", "/admin/addUser", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 用户管理-编辑用户 */
export const updateUser = (data: object) => {
  const userData = getToken();
  return http.request<Result>("put", "/admin/updateUser", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 用户管理-更新用户状态 */
export const updateUserStatus = (id: number, status: number) => {
  const userData = getToken();
  return http.request<Result>(
    "patch",
    `/admin/updateUserStatus/${id}/${status}`,
    {
      headers: {
        Authorization: userData.accessToken
      }
    }
  );
};

/** 用户管理-删除用户 */
export const deleteUser = (id: number) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteUser/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 用户管理-批量删除用户 */
export const deleteUsers = (ids: Array<number>) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteUsers`, {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data: ids
  });
};

/** 歌手管理-获取歌手列表 */
export const getArtistList = (data: object) => {
  const userData = getToken();
  return http.request<ResultTable>("post", "/admin/getAllArtists", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 歌手管理-新增歌手 */
export const addArtist = (data: object) => {
  const userData = getToken();
  return http.request<Result>("post", "/admin/addArtist", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/**
 * 歌手管理-新增歌手（可同时上传头像）。
 *
 * 用 multipart 一次提交「歌手信息 + 头像」：分成两个请求时，中间任何一步失败都会
 * 留下半成品（有歌手没头像 / 有头像没歌手）。头像可选，不传就等同于 addArtist。
 */
export const addArtistWithAvatar = (artist: object, avatar?: File) => {
  const userData = getToken();
  const formData = new FormData();
  // 后端用 @RequestPart("artist") ArtistAddDTO 接收，必须声明 JSON 类型，
  // 否则 Spring 会当成普通文本字段而反序列化失败
  formData.append(
    "artist",
    new Blob([JSON.stringify(artist)], { type: "application/json" })
  );
  if (avatar) {
    formData.append("avatar", avatar);
  }
  return http.request<Result>("post", "/admin/addArtistWithAvatar", {
    headers: {
      "Content-Type": "multipart/form-data",
      Authorization: userData.accessToken
    },
    data: formData
  });
};

/** 歌手管理-编辑歌手 */
export const updateArtist = (data: object) => {
  const userData = getToken();
  return http.request<Result>("put", "/admin/updateArtist", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 歌手管理-更新歌手头像 */
export const updateArtistAvatar = (id: number, data: object) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/updateArtistAvatar/${id}`, {
    headers: {
      "Content-Type": "multipart/form-data",
      Authorization: userData.accessToken
    },
    data,
    responseType: "json" // 确保使用正确的响应类型（可以使用 'json' 或 'blob'）
  });
};

/** 歌手管理-删除歌手 */
export const deleteArtist = (id: number) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteArtist/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 歌手管理-批量删除歌手 */
export const deleteArtists = (ids: Array<number>) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteArtists`, {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data: ids
  });
};

/** 歌曲管理-获取所有歌手 */
export const getAllArtists = () => {
  const userData = getToken();
  return http.request<Result>("get", "/admin/getAllArtistNames", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    }
  });
};

/** 歌曲管理-获取歌曲列表 */
export const getSongList = (data: object) => {
  const userData = getToken();
  return http.request<ResultTable>("post", "/admin/getAllSongsByArtist", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 歌曲管理-新增歌曲 */
export const addSong = (data: object) => {
  const userData = getToken();
  return http.request<Result>("post", "/admin/addSong", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 歌曲管理-编辑歌曲 */
export const updateSong = (data: object) => {
  const userData = getToken();
  return http.request<Result>("put", "/admin/updateSong", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 歌曲管理-检查专辑是否存在 */
export const checkAlbumExists = (artistId: number, albumName: string) => {
  const userData = getToken();
  return http.request<Result>("get", "/admin/checkAlbumExists", {
    params: { artistId, albumName },
    headers: {
      Authorization: userData.accessToken
    }
  });
};

/** 歌曲管理-更新歌曲封面 */
export const updateSongCover = (id: number, data: object) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/updateSongCover/${id}`, {
    headers: {
      Authorization: userData.accessToken
    },
    data,
    responseType: "json" // 确保使用正确的响应类型（可以使用 'json' 或 'blob'）
  });
};

/** 歌曲管理-更新歌曲音频 */
export const updateSongAudio = (id: number, data: object) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/updateSongAudio/${id}`, {
    headers: {
      Authorization: userData.accessToken
    },
    data,
    responseType: "json" // 确保使用正确的响应类型（可以使用 'json' 或 'blob'）
  });
};

/** 歌曲管理-删除歌曲 */
export const deleteSong = (id: number) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteSong/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 歌曲管理-批量删除歌曲 */
export const deleteSongs = (ids: Array<number>) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteSongs`, {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data: ids
  });
};

/** 专辑管理-获取所有专辑 */
export const getAlbumListAdmin = (data: object) => {
  const userData = getToken();
  return http.request<ResultTable>("post", "/admin/getAllAlbums", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 专辑管理-获取专辑详情及歌曲列表 */
export const getAlbumDetailAdmin = (id: number) => {
  const userData = getToken();
  return http.request<Result>("get", `/album/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 专辑管理-新增专辑 */
export const addAlbum = (data: object) => {
  const userData = getToken();
  return http.request<Result>("post", "/admin/addAlbum", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 专辑管理-修改专辑 */
export const updateAlbum = (data: object) => {
  const userData = getToken();
  return http.request<Result>("put", "/admin/updateAlbum", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 专辑管理-更新专辑封面 */
export const updateAlbumCover = (id: number, data: FormData) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/updateAlbumCover/${id}`, {
    headers: {
      Authorization: userData.accessToken
    },
    data
  });
};

/** 专辑管理-删除专辑；deleteSongs 为 true 时连同专辑内的所有歌曲一起删除 */
export const deleteAlbum = (id: number, deleteSongs = false) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteAlbum/${id}`, {
    params: { deleteSongs },
    headers: { Authorization: userData.accessToken }
  });
};

/** 专辑管理-批量删除专辑 */
export const deleteAlbums = (ids: Array<number>) => {
  const userData = getToken();
  return http.request<Result>("delete", "/admin/deleteAlbums", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data: ids
  });
};

/** 歌单管理-获取歌单列表 */
export const getPlaylistList = (data: object) => {
  const userData = getToken();
  return http.request<ResultTable>("post", "/admin/getAllPlaylists", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 歌单管理-新增歌单 */
export const addPlaylist = (data: object) => {
  const userData = getToken();
  return http.request<Result>("post", "/admin/addPlaylist", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 歌单管理-编辑歌单 */
export const updatePlaylist = (data: object) => {
  const userData = getToken();
  return http.request<Result>("put", "/admin/updatePlaylist", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 歌单管理-更新歌单封面 */
export const updatePlaylistCover = (id: number, data: object) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/updatePlaylistCover/${id}`, {
    headers: {
      "Content-Type": "multipart/form-data",
      Authorization: userData.accessToken
    },
    data,
    responseType: "json" // 确保使用正确的响应类型（可以使用 'json' 或 'blob'）
  });
};

/** 歌单管理-删除歌单 */
export const deletePlaylist = (id: number) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deletePlaylist/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 歌单管理-批量删除歌单 */
export const deletePlaylists = (ids: Array<number>) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deletePlaylists`, {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data: ids
  });
};

/** 歌单管理-获取歌单详情（含草稿，返回歌曲列表） */
export const getPlaylistDetail = (id: number) => {
  const userData = getToken();
  return http.request<Result>("get", `/admin/getPlaylistDetail/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 歌单管理-发布歌单（平台歌单发布后固定为公开） */
export const publishPlaylist = (id: number) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/publishPlaylist/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 歌单管理-撤回为草稿 */
export const unpublishPlaylist = (id: number) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/unpublishPlaylist/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 歌单管理-为歌单添加歌曲 */
export const addPlaylistSongs = (id: number, songIds: Array<number>) => {
  const userData = getToken();
  return http.request<Result>("post", `/admin/addPlaylistSongs/${id}`, {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data: { songIds }
  });
};

/** 歌单管理-从歌单移除歌曲 */
export const removePlaylistSong = (playlistId: number, songId: number) => {
  const userData = getToken();
  return http.request<Result>(
    "delete",
    `/admin/removePlaylistSong/${playlistId}/${songId}`,
    {
      headers: { Authorization: userData.accessToken }
    }
  );
};

/** 反馈管理-获取反馈列表 */
export const getFeedbackList = (data: object) => {
  const userData = getToken();
  return http.request<ResultTable>("post", "/admin/getAllFeedbacks", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 反馈管理-删除反馈 */
export const deleteFeedback = (id: number) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteFeedback/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 反馈管理-批量删除反馈 */
export const deleteFeedbacks = (ids: Array<number>) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteFeedbacks`, {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data: ids
  });
};

/** 轮播图管理-获取轮播图列表 */
export const getBannerList = (data: object) => {
  const userData = getToken();
  return http.request<ResultTable>("post", "/admin/getAllBanners", {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 轮播图管理-新增轮播图 */
export const addBanner = (data: object) => {
  const userData = getToken();
  return http.request<Result>("post", "/admin/addBanner", {
    headers: {
      "Content-Type": "multipart/form-data",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 轮播图管理-编辑轮播图 */
export const updateBanner = (id: number, data: object) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/updateBanner/${id}`, {
    headers: {
      "Content-Type": "multipart/form-data",
      Authorization: userData.accessToken
    },
    data
  });
};

/** 轮播图管理-编辑轮播图状态 */
export const updateBannerStatus = (id: number, status: number) => {
  const userData = getToken();
  return http.request<Result>("patch", `/admin/updateBannerStatus/${id}`, {
    headers: {
      Authorization: userData.accessToken
    },
    params: { status }
  });
};

/** 轮播图管理-删除轮播图 */
export const deleteBanner = (id: number) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteBanner/${id}`, {
    headers: { Authorization: userData.accessToken }
  });
};

/** 轮播图管理-批量删除轮播图 */
export const deleteBanners = (ids: Array<number>) => {
  const userData = getToken();
  return http.request<Result>("delete", `/admin/deleteBanners`, {
    headers: {
      "Content-Type": "application/json",
      Authorization: userData.accessToken
    },
    data: ids
  });
};

