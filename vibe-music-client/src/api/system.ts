import { http } from '@/utils/http'
import type { AlbumDetail, GlobalSearchResult, SongDetail } from '@/api/interface'

export type Result<T = Array<any> | number | string | object> = {
  code: number
  message: string
  data?: T
}

export type ResultTable = {
  code: number
  message: string
  data?: {
    /** 列表数据 */
    items: Array<any>
    /** 总条目数 */
    total?: number
    /** 每页显示条目个数 */
    pageSize?: number
    /** 当前页数 */
    currentPage?: number
  }
}

/** 用户登录 */
export const login = (data: object) => {
  return http<Result>('post', '/user/login', { data })
}

/** 用户登出 */
export const logout = () => {
  return http<Result>('post', '/user/logout')
}

/** 发送邮箱验证码 */
export const sendEmailCode = (email: string) => {
  return http<Result>('get', '/user/sendVerificationCode', {
    params: { email },
  })
}

/** 用户注册 */
export const register = (data: object) => {
  return http<Result>('post', '/user/register', { data })
}

/** 重置密码（忘记密码流程） */
export const resetPassword = (data: object) => {
  return http<Result>('patch', '/user/resetUserPassword', { data })
}

/** 修改密码（已登录，需旧密码验证） */
export const changePassword = (data: {
  oldPassword: string
  newPassword: string
  repeatPassword: string
}) => {
  return http<Result>('patch', '/user/updateUserPassword', { data })
}

/** 获取用户信息 */
export const getUserInfo = () => {
  return http<Result>('get', '/user/getUserInfo')
}

/** 更新用户信息 */
export const updateUserInfo = (data: object) => {
  return http<Result>('put', '/user/updateUserInfo', { data })
}

/** 更新用户头像 */
export const updateUserAvatar = (formData: FormData) => {
  return http<Result>('patch', '/user/updateUserAvatar', {
    headers: {
      'Content-Type': 'multipart/form-data',
    },
    data: formData,
    transformRequest: [(data) => data], // 防止 axios 处理 FormData
  })
}

/** 注销账号结果。用于区分「注销失败」与「账号已删除但外部资源清理待后台重试」。 */
export interface AccountDeletionResultVO {
  /** 账号本身是否已删除 */
  accountDeleted: boolean
  /** 是否存在待后台重试的外部资源清理 */
  cleanupPending: boolean
  /** 待清理项，取值 "REDIS_TOKEN" / "MINIO_AVATAR" */
  pendingItems: string[]
}

/** 注销账号 */
export const deleteUser = () => {
  return http<Result<AccountDeletionResultVO>>('delete', '/user/deleteAccount')
}

/** 获取轮播图 */
export const getBanner = () => {
  return http<Result>('get', '/banner/getBannerList')
}

/** 获取推荐歌单 */
export const getRecommendedPlaylists = () => {
  return http<Result>('get', '/playlist/getRecommendedPlaylists')
}

/** 获取推荐歌曲 */
export const getRecommendedSongs = () => {
  return http<Result>('get', '/song/getRecommendedSongs')
}

/** 全局搜索歌曲、歌手、专辑、歌单 */
export const globalSearch = (keyword: string, limit = 10) => {
  return http<Result<GlobalSearchResult>>('get', '/search', {
    params: { keyword, limit },
  })
}

/** 获取专辑列表 */
export const getAlbumList = (data: object) => {
  return http<ResultTable>('get', '/album/list', { params: data })
}

/** 获取专辑详情（包含按曲序排列的歌曲） */
export const getAlbumDetail = (id: number) => {
  return http<Result<AlbumDetail>>('get', `/album/${id}`)
}

/** 搜索专辑 */
export const searchAlbums = (keyword: string, limit = 10) => {
  return http<Result>('get', '/album/search', {
    params: { keyword, limit },
  })
}

/** 获取所有歌曲 */
export const getAllSongs = (data: object) => {
  return http<ResultTable>('post', '/song/getAllSongs', { data })
}

/** 获取歌曲详情 */
export const getSongDetail = (id: number) => {
  return http<Result<SongDetail>>('get', `/song/getSongDetail/${id}`)
}

/** 获取所有歌手 */
export const getAllArtists = (data: object, signal?: AbortSignal) => {
  return http<ResultTable>('post', '/artist/getAllArtists', { data, signal })
}

/** 获取歌手详情 */
export const getArtistDetail = (id: number) => {
  return http<Result>('get', `/artist/getArtistDetail/${id}`)
}

/** 获取所有歌单 */
export const getAllPlaylists = (data: object) => {
  return http<ResultTable>('post', '/playlist/getAllPlaylists', { data })
}

/** 获取歌单详情 */
export const getPlaylistDetail = (id: number) => {
  return http<Result>('get', `/playlist/getPlaylistDetail/${id}`)
}

/** 获取用户收藏的歌曲 */
export const getFavoriteSongs = (data: object) => {
  return http<Result>('post', '/favorite/getFavoriteSongs', { data })
}

/** 收藏歌曲 */
export const collectSong = (songId: number) => {
  return http<Result>('post', '/favorite/collectSong', { params: { songId } })
}

/** 取消收藏歌曲 */
export const cancelCollectSong = (songId: number) => {
  return http<Result>('delete', '/favorite/cancelCollectSong', {
    params: { songId },
  })
}

/** 获取用户收藏的歌单 */
export const getFavoritePlaylists = (data: object) => {
  return http<Result>('post', '/favorite/getFavoritePlaylists', { data })
}

/** 收藏歌单 */
export const collectPlaylist = (playlistId: number) => {
  return http<Result>('post', '/favorite/collectPlaylist', {
    params: { playlistId },
  })
}

/** 取消收藏歌单 */
export const cancelCollectPlaylist = (playlistId: number) => {
  return http<Result>('delete', '/favorite/cancelCollectPlaylist', {
    params: { playlistId },
  })
}

/** 新增歌曲评论 */
export const addSongComment = (data: object) => {
  return http<Result>('post', '/comment/addSongComment', { data })
}

/** 新增歌单评论 */
export const addPlaylistComment = (data: object) => {
  return http<Result>('post', '/comment/addPlaylistComment', { data })
}

/** 点赞评论 */
export const likeComment = (commentId: number) => {
  return http<Result>('patch', `/comment/likeComment/${commentId}`)
}

/** 取消点赞评论 */
export const cancelLikeComment = (commentId: number) => {
  return http<Result>('patch', `/comment/cancelLikeComment/${commentId}`)
}

/** 删除评论 */
export const deleteComment = (commentId: number) => {
  return http<Result>('delete', `/comment/deleteComment/${commentId}`)
}

/** 新增反馈 */
export const addFeedback = (data: { content: string }) => {
  return http<Result>('post', '/feedback/addFeedback', { params: data })
}
