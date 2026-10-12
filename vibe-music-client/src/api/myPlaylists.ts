import { http } from '@/utils/http'
import type { PlaylistDetail, Song } from '@/api/interface'
import type { Result } from '@/api/system'

export interface OwnedPlaylist {
  playlistId: number
  title: string
  coverUrl: string | null
  introduction: string | null
  style: string | null
  isPublic: boolean
  ownerUserId: number
  songCount: number
  status?: string | null
  /** true 表示封面是用户手动上传的，不会再被默认封面覆盖 */
  coverIsCustom?: boolean | null
}

export interface PlaylistInput {
  title: string
  introduction: string
  style: string
  isPublic: boolean
}

export interface PageResult<T> {
  items: T[]
  total: number
}

export const getMyPlaylists = (
  pageNum: number,
  title: string,
  signal?: AbortSignal
) =>
  http<Result<PageResult<OwnedPlaylist>>>('get', '/playlist/mine', {
    params: { pageNum, pageSize: 12, title },
    signal,
  })

export const getOwnedPlaylist = (id: number, signal?: AbortSignal) =>
  http<Result<PlaylistDetail>>('get', `/playlist/getPlaylistDetail/${id}`, {
    signal,
  })

export const createMyPlaylist = (
  data: PlaylistInput,
  key: string,
  signal?: AbortSignal
) =>
  http<Result<PlaylistDetail>>('post', '/playlist/mine', {
    data: { ...data, songIds: [] },
    headers: { 'Idempotency-Key': key },
    signal,
  })

export const updateMyPlaylist = (
  id: number,
  data: PlaylistInput,
  signal?: AbortSignal
) =>
  http<Result<PlaylistDetail>>('put', `/playlist/mine/${id}`, { data, signal })

export const deleteMyPlaylist = (id: number, signal?: AbortSignal) =>
  http<Result>('delete', `/playlist/mine/${id}`, { signal })

export const addPlaylistSongs = (
  id: number,
  songIds: number[],
  signal?: AbortSignal
) =>
  http<Result<PlaylistDetail>>('post', `/playlist/mine/${id}/songs`, {
    data: { songIds },
    signal,
  })

export const removePlaylistSong = (
  id: number,
  songId: number,
  signal?: AbortSignal
) =>
  http<Result<PlaylistDetail>>(
    'delete',
    `/playlist/mine/${id}/songs/${songId}`,
    { signal }
  )

/** 上传自定义封面；成功后该歌单不再使用默认封面 */
export const updateMyPlaylistCover = (
  id: number,
  cover: Blob,
  signal?: AbortSignal
) => {
  const formData = new FormData()
  formData.append('cover', cover, 'cover.png')
  return http<Result<PlaylistDetail>>('patch', `/playlist/mine/${id}/cover`, {
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
    signal,
  })
}

/** 恢复为「第一首歌曲封面」的默认封面 */
export const resetMyPlaylistCover = (id: number, signal?: AbortSignal) =>
  http<Result<PlaylistDetail>>('delete', `/playlist/mine/${id}/cover`, {
    signal,
  })

export const searchPlaylistSongs = (
  songName: string,
  artistName: string,
  pageNum: number,
  signal?: AbortSignal
) =>
  http<Result<PageResult<Song>>>('post', '/song/getAllSongs', {
    data: { songName, artistName, pageNum, pageSize: 10 },
    signal,
  })

/** Keep the same key for an uncertain network outcome; changed input is a new intent. */
export function createPlaylistIntent() {
  let previousPayload = ''
  let key = ''
  return {
    keyFor(input: PlaylistInput) {
      const payload = JSON.stringify(input)
      if (!key || payload !== previousPayload) {
        previousPayload = payload
        key = crypto.randomUUID()
      }
      return key
    },
    reset() {
      previousPayload = ''
      key = ''
    },
  }
}
