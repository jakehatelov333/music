import type { AgentSongRef, PendingAction } from '@/api/agent'
import type { AgentTaskEvent, SongSearch } from '@/api/ai'

export const SONGS_PAGE_SIZE = 5

export type Block =
  | { kind: 'text'; text: string }
  | {
      kind: 'tool'
      toolCallId: string
      tool: string
      label: string
      done: boolean
      ok?: boolean | null
      code?: string | null
      durationMs?: number | null
      total?: number | null
    }
  | {
      kind: 'songs'
      songs: AgentSongRef[]
      message: string
      page: number
      total: number
      search: SongSearch | null
      busy: boolean
      error: string | null
      remote: boolean
    }
  | {
      kind: 'confirm'
      action: PendingAction
      busy: boolean
      error: string | null
      verified: boolean
    }
  | { kind: 'notice'; text: string; tone: 'info' | 'error' }

export interface ChatMessage {
  role: 'user' | 'assistant'
  blocks: Block[]
  task?: AgentTaskEvent | null
}

export const songBlock = (
  songs: AgentSongRef[],
  message: string,
  search?: SongSearch | null
): Extract<Block, { kind: 'songs' }> => ({
  kind: 'songs',
  songs,
  message,
  search: search || null,
  page: 1,
  total: songs.length,
  busy: false,
  error: null,
  remote: false,
})

export const songPageCount = (block: Extract<Block, { kind: 'songs' }>) =>
  Math.max(1, Math.ceil(block.total / SONGS_PAGE_SIZE))

export const visibleSongs = (block: Extract<Block, { kind: 'songs' }>) =>
  block.remote
    ? block.songs
    : block.songs.slice(
        (block.page - 1) * SONGS_PAGE_SIZE,
        block.page * SONGS_PAGE_SIZE
      )

function parse(value: unknown): any {
  if (typeof value !== 'string') return value
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

function searchFrom(tool: string, args: any): SongSearch | null {
  if (!args) return null
  if (tool === 'searchSongs' && typeof args.keyword === 'string')
    return { keyword: args.keyword }
  if (tool === 'searchSongsByArtist' && typeof args.artistName === 'string')
    return { artistName: args.artistName }
  if (tool === 'getSongsByStyle' && typeof args.styleName === 'string')
    return { style: args.styleName }
  return null
}

function validSongs(value: unknown): value is AgentSongRef[] {
  return (
    Array.isArray(value) &&
    value.every(
      (s) => s && Number.isFinite(s.songId) && typeof s.songName === 'string'
    )
  )
}

/** recent 按 ID 倒序；工具结果本就保存在历史中，不依赖浏览器缓存。 */
export function restoreConversation(
  rows: Array<{ messageType: string; content: string }>
): ChatMessage[] {
  const messages: ChatMessage[] = []
  const requests = new Map<
    string,
    { tool: string; search: SongSearch | null }
  >()
  const actions = new Set<string>()
  const assistant = () => {
    if (messages.at(-1)?.role !== 'assistant')
      messages.push({ role: 'assistant', blocks: [] })
    return messages.at(-1)!
  }
  for (const row of [...rows].reverse()) {
    if (row.messageType === 'USER') {
      messages.push({
        role: 'user',
        blocks: [{ kind: 'text', text: row.content }],
      })
    } else if (row.messageType === 'ASSISTANT' && row.content) {
      assistant().blocks.push({ kind: 'text', text: row.content })
    } else if (row.messageType === 'TOOL_REQUEST') {
      const entries = parse(row.content)
      if (Array.isArray(entries))
        for (const entry of entries) {
          if (entry?.id)
            requests.set(entry.id, {
              tool: entry.name,
              search: searchFrom(entry.name, parse(entry.arguments)),
            })
        }
    } else if (row.messageType === 'TOOL_RESULT') {
      const envelope = parse(row.content)
      const result = parse(envelope?.text)
      if (!result || typeof result.ok !== 'boolean') continue
      const request = requests.get(envelope.id)
      const blocks = assistant().blocks
      blocks.push({
        kind: 'tool',
        toolCallId: envelope.id || '',
        tool: envelope.toolName || request?.tool || '',
        label: result.message || '工具执行完成',
        done: true,
        ok: result.ok,
        code: result.code,
        total: result.total,
      })
      if (
        result.ok &&
        result.kind === 'SONG_LIST' &&
        validSongs(result.data) &&
        result.data.length
      ) {
        blocks.push(
          songBlock(
            result.data,
            result.message || '搜索结果',
            result.search || request?.search
          )
        )
      } else if (
        result.ok &&
        result.kind === 'PLAYLIST_PREVIEW' &&
        result.data?.taskId &&
        validSongs(result.data.songs)
      ) {
        if (actions.has(result.data.taskId)) continue
        actions.add(result.data.taskId)
        blocks.push({
          kind: 'confirm',
          action: result.data,
          busy: false,
          error: null,
          verified: false,
        })
      }
    }
  }
  return messages.slice(-10)
}
