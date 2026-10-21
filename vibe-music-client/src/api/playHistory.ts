import { http } from '@/utils/http'
import type { ArtistRef } from '@/api/interface'
import type { Result } from '@/api/system'

/**
 * 「最近播放」列表项。
 *
 * <p>字段名与 `Song` 保持同名同义（songId / songName / artistName / album / coverUrl /
 * audioUrl），只是多了 lastPlayedAt 与 playCount，因此可以复用同一套歌曲渲染逻辑。
 */
export interface PlayHistoryItem {
  /** 历史记录 id，删除单条时使用 */
  historyId: number
  songId: number
  albumId?: number | null
  songName: string
  /** 多歌手展示串，例如「银临 / Aki阿杰」；兼容字段 */
  artistName: string
  /** 结构化歌手列表，按展示顺序；无关系时为空数组 */
  artists?: ArtistRef[]
  album: string
  duration: string
  coverUrl: string | null
  audioUrl: string
  releaseTime: string | null
  /** 最近一次有效播放时间，格式 yyyy-MM-dd HH:mm:ss */
  lastPlayedAt: string
  /** 累计有效播放次数 */
  playCount: number
}

export interface PlayHistoryPage {
  items: PlayHistoryItem[]
  total: number
}

/**
 * 分页查询当前用户的播放历史。
 *
 * <p>**不接受 userId 参数**：归属由后端从认证上下文取。前端传什么都不影响结果，
 * 因此也不提供这个口子。
 */
export const getPlayHistoryPage = (
  pageNum: number,
  pageSize: number,
  songName = '',
  signal?: AbortSignal
) =>
  http<Result<PlayHistoryPage>>('post', '/play-history/page', {
    data: { pageNum, pageSize, songName },
    signal,
  })

/** 最近播放的歌曲（「刚刚听过」），limit 上限 50。 */
export const getRecentPlays = (limit = 20, signal?: AbortSignal) =>
  http<Result<PlayHistoryItem[]>>('get', '/play-history/recent', {
    params: { limit },
    signal,
  })

/**
 * 删除当前用户的一条历史记录。
 *
 * <p>不属于当前用户的 id 后端统一返回 404（不泄漏存在性）。
 */
export const deletePlayHistory = (historyId: number, signal?: AbortSignal) =>
  http<Result>('delete', `/play-history/${historyId}`, { signal })

/** 清空当前用户的播放历史；只影响自己。 */
export const clearPlayHistory = (signal?: AbortSignal) =>
  http<Result<{ cleared: number }>>('delete', '/play-history/clear', { signal })

/**
 * 上报一次有效播放。
 *
 * <p>**必须由「确实播到阈值」之后调用**，不要在页面加载或点击播放时立刻调用 ——
 * 那会把「点开就关掉」也记成播放。
 *
 * @param playId 本次播放尝试的幂等键。网络重试时**必须复用同一个值**，
 *               否则后端会把重试当成新的一次播放。换一首歌 / 重新播放才换新值。
 */
export const recordPlay = (
  songId: number,
  playId: string,
  positionSeconds?: number
) =>
  http<Result<{ counted: boolean }>>('post', '/play-history/record', {
    data: { songId, playId, positionSeconds },
    // 播放上报是后台行为：不弹 NProgress 进度条，避免每次切歌都闪一下
    headers: { 'X-Silent-Request': '1' },
  })
