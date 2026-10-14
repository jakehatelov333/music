import { http } from '@/utils/http'
import { UserStore } from '@/stores/modules/user'
import type { Result } from '@/api/system'
import type { AgentSongRef, PendingAction } from '@/api/agent'

export type AiChatResponse = string

/**
 * 后端基地址。默认留空 = 同源（开发时由 Vite dev server 代理到 8080）。
 *
 * <p><b>为什么同源：</b>游客会话身份存在 Cookie（vibe_guest_session）里。
 * 前端跑在 8090、后端在 8080 属于跨端口，Chrome 对这类请求的
 * SameSite=Lax Cookie 收发不稳定，会出现"每轮对话都是新会话"——
 * 模型看起来完全没有记忆。同源后 Cookie 必然随请求发送。
 * 部署到不同域时可用 VITE_AI_BASE 指定绝对地址。
 */
const API_BASE: string = import.meta.env.VITE_AI_BASE ?? ''

/**
 * 会话列表中的一行。字段与后端 ConversationVO 对齐。
 *
 * `summaryText` 是滚动摘要（描述整段对话在聊什么，可能为空）；
 * `lastMessage` 是最近一条消息的截断。展示时优先摘要、回退到最后一条消息 ——
 * 只看任意一个都会在某种情况下出现空白行。
 */
export interface ConversationVO {
  chatId: string
  title: string | null
  summaryText: string | null
  lastMessage: string | null
  createdAt: string | null
  lastActiveAt: string | null
}

export const aiSimpleChat = (query: string, chatId?: string | null) =>
  http<AiChatResponse>('post', '/ai/chat', {
    data: chatId ? { query, chatId } : { query },
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 65000,
  })

export const aiHistory = (chatId?: string | null) =>
  http<any>('get', '/ai/chat/history', {
    params: chatId ? { chatId } : {},
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 30000,
  })

export const aiRecent = (limit = 10, chatId?: string | null) =>
  http<any>('get', '/ai/chat/recent', {
    params: chatId ? { limit, chatId } : { limit },
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 30000,
  })

/** 清空一段对话的消息。服务端会连带取消该会话下仍待确认的任务。 */
export const aiClear = (chatId?: string | null) =>
  http<string>('delete', '/ai/chat/clear', {
    params: chatId ? { chatId } : {},
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/** 当前登录用户的最近对话，按最近活跃时间倒序。游客调用返回 401。 */
export const listConversations = (limit?: number, signal?: AbortSignal) =>
  http<Result<ConversationVO[]>>('get', '/ai/conversations', {
    params: limit ? { limit } : {},
    signal,
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/** 彻底删除一段对话。传别人的 chatId 会得到 404（服务端校验归属）。 */
export const deleteConversation = (chatId: string, signal?: AbortSignal) =>
  http<Result<string>>('delete', `/ai/conversations/${encodeURIComponent(chatId)}`, {
    signal,
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/** 清除全部会话返回的统计结果。字段与后端 ClearConversationsResultVO 对齐。 */
export interface ClearConversationsResultVO {
  /** 清除的会话数 */
  conversationCount: number
  /** 清除的消息数 */
  messageCount: number
  /** 清除的滚动摘要数 */
  summaryCount: number
  /** 被连带取消的进行中任务数 */
  cancelledTaskCount: number
}

/**
 * 一次性清除当前登录用户的全部 AI 会话与消息。
 *
 * 身份由服务端认证上下文（Cookie / Authorization）决定，前端不传 userId。
 * 成功返回 `Result<ClearConversationsResultVO>`（code === 0）；
 * 若仍有会话正在生成，服务端返回 409（响应拦截器会提示后端文案，调用方不要重复弹窗）。
 */
export const clearAllConversations = (signal?: AbortSignal) =>
  http<Result<ClearConversationsResultVO>>('delete', '/ai/conversations', {
    signal,
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 60000,
  })

export interface SongSearch {
  keyword?: string | null
  artistName?: string | null
  style?: string | null
}

export interface SongPage {
  songs: AgentSongRef[]
  total: number
  page: number
  size: number
}

export const aiSongPage = (search: SongSearch, page = 1, size = 5) =>
  http<SongPage>('get', '/ai/songs', {
    params: { ...search, page, size },
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/** 当前登录用户自己的 Agent 任务指标（真实耗时、模型调用次数与 Token 用量）。 */
export const aiMetrics = (limit = 10) =>
  http<any>('get', '/ai/chat/metrics', {
    params: { limit },
    withCredentials: true,
    baseURL: API_BASE,
    timeout: 20000,
  })

/** 工具调用事件：同一 toolCallId 的 start / end 配对。 */
export interface AgentToolEvent {
  taskId: string
  toolCallId: string
  tool: string
  phase: 'start' | 'end'
  ok?: boolean | null
  code?: string | null
  message?: string | null
  total?: number | null
  kind?: string | null
  durationMs?: number | null
}

/** 结构化歌曲数据事件。 */
export interface AgentSongsEvent {
  search?: SongSearch | null
  taskId: string
  toolCallId: string
  ok: boolean
  code: string
  message: string
  total: number
  kind: string
  data: Array<{
    songId: number
    songName: string
    /** 多歌手展示串；兼容字段 */
    artistName: string | null
    /** 结构化歌手列表，按展示顺序 */
    artists?: { artistId: number; artistName: string }[] | null
    album: string | null
    style: string | null
    duration: string | null
    coverUrl: string | null
    audioUrl: string | null
  }>
}

/** 任务结束汇总事件。 */
export interface AgentTaskEvent {
  taskId: string
  ok: boolean
  code: string
  message: string
  data: {
    taskId: string
    model: string
    success: boolean
    failureCategory: string | null
    modelCalls: number
    toolCalls: number
    toolFailures: number
    resultCount: number
    latencyMs: number
    inputTokens: number | null
    outputTokens: number | null
    tokenSource: string
    playlistId: number | null
  }
}

export interface SSECallbacks {
  onToken: (token: string) => void
  onDone: (fullText: string) => void
  onError: (error: Error) => void
  /**
   * Agent 开始处理，携带本次任务的 taskId 与**实际使用的 chatId**。
   *
   * chatId 必须在回调里被采纳：点「新对话」时前端还不知道新会话的标识
   * （会话是服务端在收到第一条消息时才创建的，这样不会留下空会话），
   * 只能从第一个事件里认领它。
   */
  onStart?: (payload: { taskId: string; model: string; chatId?: string }) => void
  /** 工具开始/结束。 */
  onTool?: (payload: AgentToolEvent) => void
  /** 结构化歌曲数据，用于渲染歌曲卡片。 */
  onSongs?: (payload: AgentSongsEvent) => void
  /** 等待用户确认，携带服务端持久化的待确认任务。 */
  onConfirm?: (payload: PendingAction) => void
  /** 任务结束汇总（成功/失败/取消），用于展示真实耗时与用量。 */
  onTask?: (payload: AgentTaskEvent) => void
}

/** 本次请求要写进哪段对话。两者都不传 = 沿用服务端默认会话。 */
export interface ConversationTarget {
  /** 目标会话；服务端会校验它是否属于当前身份，不属于则 404。 */
  chatId?: string | null
  /** 开一段全新对话。为 true 时 chatId 被忽略，新标识从 onStart 事件里取回。 */
  newConversation?: boolean
}

/**
 * 消费 /ai/chat/stream 的 SSE。
 *
 * 事件协议（事件名 = event 名，data 为纯文本；结构化事件是 JSON 字符串）：
 * `start` / `tool` / `songs` / `confirm` / `task` / `token` / `done` / `error`。
 *
 * 兼容性：`token`/`done`/`error` 的语义与载荷与旧版完全一致，新增事件是叠加的。
 * 健壮性：无论正常结束、服务端报错、解析异常还是连接中断，都会且只会回调一次终态
 * （onDone 或 onError），页面不会永久停留在加载状态。
 */
export const aiStreamChat = (
  query: string,
  callbacks: SSECallbacks,
  signal?: AbortSignal,
  target?: ConversationTarget
): void => {
  const token = UserStore().userInfo?.token
  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
  }
  if (token) headers.Authorization = token

  const body: Record<string, unknown> = { query }
  if (target?.newConversation) body.newConversation = true
  else if (target?.chatId) body.chatId = target.chatId

  fetch(`${API_BASE}/ai/chat/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    credentials: 'include',
    signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        const body = await response.json().catch(() => null)
        throw new Error(body?.message || `请求失败（${response.status}）`)
      }
      if (!response.body) throw new Error('服务器未返回响应内容')
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let currentEvent = ''
      let dataLines: string[] = []
      let completed = false

      const parseJson = <T>(raw: string): T | null => {
        try {
          return JSON.parse(raw) as T
        } catch {
          return null
        }
      }

      const dispatch = () => {
        const data = dataLines.join('\n')
        switch (currentEvent) {
          case 'token':
            callbacks.onToken(data)
            break
          case 'done':
            completed = true
            callbacks.onDone(data)
            break
          case 'error':
            completed = true
            callbacks.onError(new Error(data || 'AI 回复失败'))
            break
          case 'start': {
            const payload = parseJson<{ taskId: string; model: string }>(data)
            if (payload) callbacks.onStart?.(payload)
            break
          }
          case 'tool': {
            const payload = parseJson<AgentToolEvent>(data)
            if (payload) callbacks.onTool?.(payload)
            break
          }
          case 'songs': {
            const payload = parseJson<AgentSongsEvent>(data)
            if (payload) callbacks.onSongs?.(payload)
            break
          }
          case 'confirm': {
            const payload = parseJson<{ data: PendingAction }>(data)
            if (payload?.data) callbacks.onConfirm?.(payload.data)
            break
          }
          case 'task': {
            const payload = parseJson<AgentTaskEvent>(data)
            if (payload) callbacks.onTask?.(payload)
            break
          }
          default:
            break
        }
        currentEvent = ''
        dataLines = []
      }

      try {
        while (!completed) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let boundary: number
          while ((boundary = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, boundary).replace(/\r$/, '')
            buffer = buffer.slice(boundary + 1)
            if (line.startsWith('event:')) currentEvent = line.slice(6).trim()
            else if (line.startsWith('data:'))
              dataLines.push(line.slice(5).replace(/^ /, ''))
            else if (line === '') dispatch()
            if (completed) break
          }
        }
        if (!completed && !signal?.aborted)
          throw new Error('连接已中断，请重新发送')
      } finally {
        await reader.cancel().catch(() => undefined)
        reader.releaseLock()
      }
    })
    .catch((error: Error) => {
      if (signal?.aborted || error.name === 'AbortError') return
      callbacks.onError(error)
    })
}
