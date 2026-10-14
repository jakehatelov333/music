import { http } from '@/utils/http'
import type { Result } from '@/api/system'

/**
 * 与 api/ai.ts / api/agent.ts 保持同一策略：默认同源（Vite 代理到 8080）。
 * 登录态由请求拦截器注入 Authorization，withCredentials 兼顾 Cookie 身份。
 */
const API_BASE: string = import.meta.env.VITE_AI_BASE ?? ''

/**
 * 手动偏好的分类枚举。取值与后端 `MemoryPreferenceCategory` 逐字一致。
 *
 * ARTIST 类以 `targetId`（artist_id）为准，`targetValue` 由后端解析为权威歌手名；
 * STYLE / NOTE 类 `targetId=null`，以 `targetValue` 为准。
 */
export type MemoryPreferenceCategory =
  | 'FAVORITE_ARTIST'
  | 'FAVORITE_STYLE'
  | 'PREFERENCE_NOTE'
  | 'EXCLUDE_ARTIST'
  | 'EXCLUDE_STYLE'
  | 'EXCLUDE_NOTE'

/** 最终生效偏好项的来源：手动指定 or 系统推断。 */
export type PreferenceSource = 'MANUAL' | 'AUTO'

/** 一条手动偏好（`MemoryPreferenceVO`）。 */
export interface MemoryPreference {
  id: number
  category: MemoryPreferenceCategory
  targetId: number | null
  targetValue: string
  createdAt: string | null
  updatedAt: string | null
}

/** 最终生效偏好里的单项。 */
export interface EffectivePreferenceItem {
  value: string
  targetId: number | null
  source: PreferenceSource
}

/** 合并后的最终生效偏好（`effective` 计算规则见契约 §4）。 */
export interface EffectivePreference {
  likeArtists: EffectivePreferenceItem[]
  likeStyles: EffectivePreferenceItem[]
  preferenceNotes: EffectivePreferenceItem[]
  excludeArtists: EffectivePreferenceItem[]
  excludeStyles: EffectivePreferenceItem[]
  excludeNotes: EffectivePreferenceItem[]
}

/** 喜欢与排除同时命中时的冲突提示（排除优先）。 */
export interface PreferenceConflict {
  targetType: string
  targetId: number | null
  targetValue: string
  message: string
}

/** `GET /ai/memory/overview` 的响应体。 */
export interface MemoryOverview {
  personalizationEnabled: boolean
  preferences: MemoryPreference[]
  effective: EffectivePreference
  conflicts: PreferenceConflict[]
}

/** `POST /ai/memory/clear-auto` 的响应体。 */
export interface ClearAutoResult {
  profileCleared: boolean
  summariesCleared: number
}

/** 新增 / 修改手动偏好的请求体。 */
export interface MemoryPreferenceRequest {
  category: MemoryPreferenceCategory
  targetId?: number | null
  targetValue?: string | null
}

/**
 * `GET /ai/profile` 返回的原始画像实体（`UserMusicProfile`）。
 *
 * `topStyles` / `topArtists` 是 **JSON 数组文本**，元素形如
 * `{"name":"流行","count":3}`（歌手可能带 `id`），需用 `parseTaste` 安全解析。
 */
export interface AiMusicProfile {
  userId: number | null
  topStyles: string | null
  topArtists: string | null
  favSongCount: number | null
  favPlaylistCount: number | null
  ownPlaylistCount: number | null
  ownPlaylistSongCount: number | null
  prefSummary: string | null
  summarySourceHash: string | null
  version: number | null
  updatedAt: string | null
}

/** 画像里解析出的一个口味条目。 */
export interface TasteItem {
  name: string
  count: number
}

/**
 * 安全解析后端画像里的 JSON 数组文本。
 *
 * 后端字段是字符串（历史原因），脏数据、null、非数组一律降级为 `[]`，
 * 绝不抛异常打断页面渲染。
 */
export const parseTaste = (raw: string | null | undefined): TasteItem[] => {
  if (!raw) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) return []
  return parsed
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({
      name: typeof item.name === 'string' ? item.name : item.name == null ? '' : String(item.name),
      count:
        typeof item.count === 'number' && Number.isFinite(item.count)
          ? item.count
          : 0,
    }))
    .filter((item) => item.name !== '')
}

/**
 * 系统推断的音乐画像。**不包 `Result`**，直接返回原始实体；游客调用 401。
 * 契约 §5.8：本接口已存在，前端只读复用。
 */
export const getAiProfile = (signal?: AbortSignal) =>
  http<AiMusicProfile>('get', '/ai/profile', {
    signal,
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/** 手动偏好 + 最终生效偏好 + 冲突 + 个性化开关。 */
export const getMemoryOverview = (signal?: AbortSignal) =>
  http<Result<MemoryOverview>>('get', '/ai/memory/overview', {
    signal,
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/** 新增一条手动偏好。 */
export const createMemoryPreference = (body: MemoryPreferenceRequest) =>
  http<Result<MemoryPreference>>('post', '/ai/memory/preferences', {
    data: body,
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/** 修改一条手动偏好（按该行 category 校验）。 */
export const updateMemoryPreference = (
  id: number,
  body: Partial<MemoryPreferenceRequest>
) =>
  http<Result<MemoryPreference>>(
    'put',
    `/ai/memory/preferences/${encodeURIComponent(String(id))}`,
    {
      data: body,
      withCredentials: true,
      baseURL: API_BASE,
      timeout: 20000,
    }
  )

/** 删除一条手动偏好（服务端按归属校验，非本人 404）。 */
export const deleteMemoryPreference = (id: number) =>
  http<Result<string>>(
    'delete',
    `/ai/memory/preferences/${encodeURIComponent(String(id))}`,
    {
      withCredentials: true,
      baseURL: API_BASE,
      timeout: 20000,
    }
  )

/** 个性化推荐开关。返回新状态。 */
export const setPersonalization = (enabled: boolean) =>
  http<Result<boolean>>('put', '/ai/memory/personalization', {
    data: { enabled },
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/**
 * 清除自动生成的记忆（自动画像行 + 会话摘要），不删手动偏好 / 消息 / 待确认任务。
 * 清空后自动画像会按现有 TTL 从收藏 / 歌单惰性重建。
 */
export const clearAutoMemory = () =>
  http<Result<ClearAutoResult>>('post', '/ai/memory/clear-auto', {
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 30000,
  })

/** 派生自曲库的可选风格列表（拆分去重排序，上限 100）。 */
export const getMemoryStyles = (signal?: AbortSignal) =>
  http<Result<string[]>>('get', '/ai/memory/styles', {
    signal,
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })
