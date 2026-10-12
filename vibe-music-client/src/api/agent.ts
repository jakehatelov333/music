import { http } from '@/utils/http'
import type { Result } from '@/api/system'

/** 待确认任务的预览歌曲，字段与后端 SongRef 对齐。 */
export interface AgentSongRef {
  songId: number
  songName: string
  artistName: string | null
  album: string | null
  style: string | null
  duration: string | null
  coverUrl: string | null
  audioUrl: string | null
}

export type PendingActionStatus =
  | 'PENDING'
  | 'EXECUTING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED'

/** 服务端持久化的待确认任务。刷新页面后按 taskId 查回的也是这个结构。 */
export interface PendingAction {
  taskId: string
  actionType: string
  status: PendingActionStatus
  statusDescription: string
  title: string
  introduction: string | null
  style: string | null
  isPublic: boolean | null
  version: number
  songs: AgentSongRef[]
  songCount: number
  /** 成功后真实创建的歌单 ID；未成功时为 null。 */
  playlistId: number | null
  errorCode: string | null
  errorMessage: string | null
  createdAt: string | null
  expiresAt: string | null
}

export interface AgentDraftInput {
  title: string
  introduction: string
  style: string
  isPublic: boolean
  songIds: number[]
  version: number
}

/** 当前会话下仍待确认的任务，用于页面刷新后重建确认卡片。 */
export const listPendingActions = (chatId?: string, signal?: AbortSignal) =>
  http<Result<PendingAction[]>>('get', '/ai/actions/pending', {
    params: chatId ? { chatId } : {},
    signal,
    timeout: 20000,
  })

export const getPendingAction = (taskId: string, signal?: AbortSignal) =>
  http<Result<PendingAction>>('get', `/ai/actions/${taskId}`, {
    signal,
    timeout: 20000,
  })

/** 确认前修改名称或歌曲列表；必须带上当前版本号。 */
export const updatePendingAction = (
  taskId: string,
  draft: AgentDraftInput,
  signal?: AbortSignal
) =>
  http<Result<PendingAction>>('put', `/ai/actions/${taskId}`, {
    data: draft,
    signal,
    timeout: 20000,
  })

/**
 * 确认并执行。`version` 传入服务端校验，防止“确认的是旧预览、执行的是新内容”。
 * 这个接口才是真正创建歌单的入口，前端不允许绕过它直接调普通创建接口。
 */
export const confirmPendingAction = (
  taskId: string,
  version?: number,
  signal?: AbortSignal
) =>
  http<Result<PendingAction>>('post', `/ai/actions/${taskId}/confirm`, {
    params: version === undefined ? {} : { version },
    signal,
    timeout: 30000,
  })

export const cancelPendingAction = (taskId: string, signal?: AbortSignal) =>
  http<Result<PendingAction>>('post', `/ai/actions/${taskId}/cancel`, {
    signal,
    timeout: 20000,
  })
