<script setup lang="ts">
import { aiRecent, aiSongPage, aiStreamChat } from '@/api/ai'
import type { AgentSongsEvent, AgentTaskEvent, AgentToolEvent } from '@/api/ai'
import {
  cancelPendingAction,
  confirmPendingAction,
  getPendingAction,
  listPendingActions,
  updatePendingAction,
} from '@/api/agent'
import type { AgentSongRef, PendingAction } from '@/api/agent'
import { UserStore } from '@/stores/modules/user'
import {
  restoreConversation,
  songBlock,
  songPageCount,
  visibleSongs,
  SONGS_PAGE_SIZE,
} from '@/utils/aiConversation'
import type { Block, ChatMessage } from '@/utils/aiConversation'
import { renderAiMarkdown } from '@/utils/aiMarkdown'

const userStore = UserStore()
const router = useRouter()
const audio = AudioStore()
const { loadTrack, play } = useAudioPlayer()

const visible = ref(false)
const minimized = ref(false)
const loading = ref(false)
const input = ref('')
const messageListRef = ref<HTMLElement | null>(null)
const messages = ref<ChatMessage[]>([])
const abortController = ref<AbortController | null>(null)
const taskId = ref<string | null>(null)
const historyLoading = ref(false)
const historyError = ref('')
const historyLoaded = ref(false)
let identityEpoch = 0

/** 确认卡片的本地编辑态，按 taskId 保存；提交时与服务端版本号一起校验。 */
const edits = reactive<
  Record<string, { title: string; isPublic: boolean; songIds: number[] }>
>({})

// ---------------------------------------------------------------- 拖拽（保持原有行为）
const pos = reactive({ x: 0, y: 0 })
const dragging = ref(false)
const dragOffset = { x: 0, y: 0 }
const widgetRef = ref<HTMLElement | null>(null)

const savePos = () => localStorage.setItem('aiWidgetPos', JSON.stringify(pos))
const loadPos = () => {
  const s = localStorage.getItem('aiWidgetPos')
  if (s) {
    try {
      const v = JSON.parse(s)
      if (typeof v.x === 'number' && typeof v.y === 'number') {
        pos.x = v.x
        pos.y = v.y
      }
    } catch {
      // 本地布局读取失败时保留当前界面，仍可继续聊天。
    }
  } else {
    const margin = 16
    const width = 360
    const height = Math.min(Math.round(window.innerHeight * 0.7), 620)
    pos.x = Math.max(0, window.innerWidth - width - margin)
    pos.y = Math.max(0, window.innerHeight - height - 24 * 4)
  }
}

const clampToViewport = () => {
  const width = widgetRef.value?.offsetWidth ?? 360
  const height =
    widgetRef.value?.offsetHeight ?? Math.round(window.innerHeight * 0.7)
  pos.x = Math.min(Math.max(0, pos.x), Math.max(0, window.innerWidth - width))
  pos.y = Math.min(Math.max(0, pos.y), Math.max(0, window.innerHeight - height))
}

const onMouseMove = (e: MouseEvent) => {
  if (!dragging.value) return
  pos.x = e.clientX - dragOffset.x
  pos.y = e.clientY - dragOffset.y
  clampToViewport()
}
const onMouseUp = () => {
  if (!dragging.value) return
  dragging.value = false
  savePos()
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
}
const startDrag = (e: MouseEvent | TouchEvent) => {
  if (e instanceof TouchEvent) {
    const t = e.touches[0]
    dragOffset.x = t.clientX - pos.x
    dragOffset.y = t.clientY - pos.y
    window.addEventListener('touchmove', onTouchMove, { passive: false })
    window.addEventListener('touchend', onTouchEnd)
  } else {
    dragOffset.x = e.clientX - pos.x
    dragOffset.y = e.clientY - pos.y
    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', onMouseUp)
  }
  dragging.value = true
}
const onTouchMove = (e: TouchEvent) => {
  if (!dragging.value) return
  const t = e.touches[0]
  pos.x = t.clientX - dragOffset.x
  pos.y = t.clientY - dragOffset.y
  clampToViewport()
}
const onTouchEnd = () => {
  if (!dragging.value) return
  dragging.value = false
  savePos()
  window.removeEventListener('touchmove', onTouchMove)
  window.removeEventListener('touchend', onTouchEnd)
}

onMounted(() => {
  loadPos()
  window.addEventListener('resize', () => {
    clampToViewport()
    savePos()
  })
})

// 身份变化时清理上一位用户的本地消息和正在执行的请求。
watch(
  () => userStore.userInfo?.token,
  () => {
    identityEpoch++
    abortController.value?.abort()
    abortController.value = null
    messages.value = []
    loading.value = false
    taskId.value = null
    historyLoading.value = false
    historyLoaded.value = false
    historyError.value = ''
    Object.keys(edits).forEach((key) => delete edits[key])
    if (visible.value) void nextTick(() => open())
  },
  { flush: 'sync' }
)

// ---------------------------------------------------------------- 歌曲与确认卡片
const toMs = (duration: string | null) => {
  if (!duration) return 0
  const parts = duration.split(':').map((v) => Number(v) || 0)
  if (parts.length === 3)
    return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
  if (parts.length === 2) return (parts[0] * 60 + parts[1]) * 1000
  return parts[0] * 1000
}

const formatDuration = (duration: string | null) => {
  const seconds = Math.floor(toMs(duration) / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

const scrollToLatest = () =>
  nextTick(() => {
    const list = messageListRef.value
    if (list) list.scrollTop = list.scrollHeight
  })

const playSong = async (song: AgentSongRef) => {
  if (!song.audioUrl) return
  audio.addTracks({
    id: String(song.songId),
    title: song.songName,
    artist: song.artistName || '未知歌手',
    album: song.album || '',
    cover: song.coverUrl || '',
    url: song.audioUrl,
    duration: toMs(song.duration),
    likeStatus: 0,
  })
  await loadTrack()
  play()
}

const ensureEdit = (action: PendingAction) => {
  if (!edits[action.taskId]) {
    edits[action.taskId] = {
      title: action.title,
      isPublic: action.isPublic ?? false,
      songIds: action.songs.map((s) => s.songId),
    }
  }
  return edits[action.taskId]
}

const editSongs = (action: PendingAction) =>
  action.songs.filter((s) => ensureEdit(action).songIds.includes(s.songId))

const removeSong = (action: PendingAction, songId: number) => {
  const edit = ensureEdit(action)
  edit.songIds = edit.songIds.filter((id) => id !== songId)
}

const isDirty = (action: PendingAction) => {
  const edit = ensureEdit(action)
  const original = action.songs.map((s) => s.songId)
  return (
    edit.title !== action.title ||
    edit.isPublic !== (action.isPublic ?? false) ||
    edit.songIds.length !== original.length ||
    edit.songIds.some((id) => !original.includes(id))
  )
}

/** 确认：先提交本地改动（如果有）拿到新版本号，再用该版本号确认，避免“确认旧内容执行新内容”。 */
const confirmAction = async (block: Extract<Block, { kind: 'confirm' }>) => {
  if (block.busy || !block.verified) return
  const action = block.action
  const edit = ensureEdit(action)
  if (edit.songIds.length === 0) {
    block.error = '歌单至少需要一首歌曲'
    return
  }
  block.busy = true
  block.error = null
  try {
    let version = action.version
    if (isDirty(action)) {
      const updated = await updatePendingAction(action.taskId, {
        title: edit.title,
        introduction: action.introduction || '',
        style: action.style || '',
        isPublic: edit.isPublic,
        songIds: edit.songIds,
        version: action.version,
      })
      if (updated.code !== 0) throw new Error(updated.message || '修改预览失败')
      block.action = updated.data!
      version = updated.data!.version
    }
    const res = await confirmPendingAction(action.taskId, version)
    if (res.code !== 0) throw new Error(res.message || '创建失败')
    block.action = res.data!
    delete edits[action.taskId]
  } catch (error: any) {
    block.error = error?.message || '创建失败，请稍后重试'
    // 状态可能已被其他操作改变，拉一次真实状态而不是本地猜测。
    try {
      const latest = await getPendingAction(action.taskId)
      if (latest.code !== 0 || !latest.data) throw new Error('状态读取失败')
      block.action = latest.data
      delete edits[action.taskId]
    } catch {
      block.verified = false
    }
  } finally {
    block.busy = false
  }
}

const cancelAction = async (block: Extract<Block, { kind: 'confirm' }>) => {
  if (block.busy || !block.verified) return
  block.busy = true
  block.error = null
  try {
    const res = await cancelPendingAction(block.action.taskId)
    if (res.code !== 0) throw new Error(res.message || '取消失败')
    block.action = res.data!
    delete edits[block.action.taskId]
  } catch (error: any) {
    block.error = error?.message || '取消失败，请稍后重试'
  } finally {
    block.busy = false
  }
}

const openPlaylist = (playlistId: number) => {
  visible.value = false
  router.push(`/playlist/${playlistId}`)
}

// ---------------------------------------------------------------- 会话

const changeSongPage = async (
  block: Extract<Block, { kind: 'songs' }>,
  page: number
) => {
  if (block.busy || page < 1) return
  if (!block.search) {
    block.page = Math.min(page, songPageCount(block))
    return
  }
  const epoch = identityEpoch
  block.busy = true
  block.error = null
  try {
    let result = await aiSongPage(block.search, page, SONGS_PAGE_SIZE)
    const lastPage = Math.max(1, Math.ceil(result.total / SONGS_PAGE_SIZE))
    if (page > lastPage)
      result = await aiSongPage(block.search, lastPage, SONGS_PAGE_SIZE)
    if (epoch !== identityEpoch) return
    block.songs = result.songs
    block.total = result.total
    block.page = result.page
    block.remote = true
  } catch {
    if (epoch === identityEpoch)
      block.error = '歌曲加载失败，请重试。当前页已保留。'
  } finally {
    block.busy = false
  }
}

const refreshAction = async (block: Extract<Block, { kind: 'confirm' }>) => {
  const epoch = identityEpoch
  block.busy = true
  block.error = null
  try {
    const result = await getPendingAction(block.action.taskId)
    if (epoch !== identityEpoch) return
    if (result.code !== 0 || !result.data) throw new Error('状态读取失败')
    if (block.action.version !== result.data.version)
      delete edits[block.action.taskId]
    block.action = result.data
    block.verified = true
  } catch {
    if (epoch === identityEpoch) {
      block.verified = false
      block.error = '无法获取最新状态，请重试后再操作。'
    }
  } finally {
    block.busy = false
  }
}

const blockFor = (blocks: Block[], toolCallId: string) =>
  blocks.find(
    (b): b is Extract<Block, { kind: 'tool' }> =>
      b.kind === 'tool' && b.toolCallId === toolCallId
  )

const open = async () => {
  visible.value = true
  if (historyLoading.value || loading.value) return
  if (historyLoaded.value) {
    historyError.value = ''
    await restorePending()
    return
  }
  const epoch = identityEpoch
  historyLoading.value = true
  historyError.value = ''
  try {
    // 工具调用同样占历史行数，取足够窗口后按会话轮次恢复。
    const list = await aiRecent(100)
    if (epoch !== identityEpoch) return
    if (!Array.isArray(list)) throw new Error('历史格式错误')
    messages.value = restoreConversation(list)
    historyLoaded.value = true
    await Promise.all(
      messages.value
        .flatMap((m) => m.blocks)
        .filter(
          (b): b is Extract<Block, { kind: 'songs' }> =>
            b.kind === 'songs' && !!b.search
        )
        .map((b) => changeSongPage(b, 1))
    )
    if (epoch === identityEpoch) await restorePending()
  } catch {
    if (epoch === identityEpoch)
      historyError.value = '聊天记录加载失败，请重试。'
  } finally {
    if (epoch === identityEpoch) {
      historyLoading.value = false
      void scrollToLatest()
    }
  }
}

/** 页面刷新后按服务端持久化的状态重建确认卡片。 */
const restorePending = async () => {
  if (!userStore.userInfo?.token) return
  const epoch = identityEpoch
  const cards = messages.value
    .flatMap((m) => m.blocks)
    .filter(
      (b): b is Extract<Block, { kind: 'confirm' }> => b.kind === 'confirm'
    )
  await Promise.all(cards.map(refreshAction))
  if (epoch !== identityEpoch) return
  try {
    const res = await listPendingActions()
    if (epoch !== identityEpoch) return
    if (res.code !== 0 || !res.data?.length) return
    const existing = new Set(
      messages.value.flatMap((m) =>
        m.blocks
          .filter(
            (b): b is Extract<Block, { kind: 'confirm' }> =>
              b.kind === 'confirm'
          )
          .map((b) => b.action.taskId)
      )
    )
    const restored = res.data.filter((a) => !existing.has(a.taskId))
    if (restored.length === 0) return
    messages.value.push({
      role: 'assistant',
      blocks: [
        { kind: 'notice', text: '你还有待确认的歌单操作：', tone: 'info' },
        ...restored.map((action) => ({
          kind: 'confirm' as const,
          action,
          busy: false,
          error: null,
          verified: true,
        })),
      ],
      task: null,
    })
  } catch {
    if (epoch === identityEpoch)
      historyError.value = '待确认操作加载失败，可重新加载。'
  }
}

const close = () => {
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
  loading.value = false
  visible.value = false
}

const toggleMin = () => {
  minimized.value = !minimized.value
}

const send = () => {
  const text = input.value.trim()
  if (!text || loading.value || historyLoading.value) return
  if (text.length > 2000) return

  abortController.value?.abort()
  messages.value.push({ role: 'user', blocks: [{ kind: 'text', text }] })
  historyLoaded.value = true
  input.value = ''
  loading.value = true

  const controller = new AbortController()
  abortController.value = controller

  messages.value.push({ role: 'assistant', blocks: [], task: null })
  // 后续 SSE 回调必须写响应式代理，才能在流式期间即时显示卡片。
  const message = messages.value[messages.value.length - 1]
  const blocks = message.blocks
  const finish = () => {
    if (controller.signal.aborted) return
    loading.value = false
    abortController.value = null
    if (blocks.length === 0)
      blocks.push({ kind: 'text', text: '（AI 未生成回复）' })
    void scrollToLatest()
  }

  aiStreamChat(
    text,
    {
      onStart: (payload) => {
        if (controller.signal.aborted) return
        taskId.value = payload.taskId
      },
      onTool: (payload: AgentToolEvent) => {
        if (controller.signal.aborted) return
        const existing = blockFor(blocks, payload.toolCallId)
        if (payload.phase === 'start') {
          if (existing) {
            existing.done = false
            existing.label = payload.message || existing.label
          } else {
            blocks.push({
              kind: 'tool',
              toolCallId: payload.toolCallId,
              tool: payload.tool,
              label: payload.message || '正在调用工具…',
              done: false,
              ok: null,
              code: null,
              durationMs: null,
              total: null,
            })
          }
        } else if (existing) {
          existing.done = true
          existing.ok = payload.ok ?? null
          existing.code = payload.code ?? null
          existing.durationMs = payload.durationMs ?? null
          existing.total = payload.total ?? null
          existing.label = payload.message || existing.label
        }
      },
      onSongs: (payload: AgentSongsEvent) => {
        if (controller.signal.aborted) return
        if (!payload.data?.length) return
        blocks.push(songBlock(payload.data, payload.message, payload.search))
        const block = blocks[blocks.length - 1] as Extract<
          Block,
          { kind: 'songs' }
        >
        if (block.search) void changeSongPage(block, 1)
      },
      onConfirm: (action: PendingAction) => {
        if (controller.signal.aborted) return
        blocks.push({
          kind: 'confirm',
          action,
          busy: false,
          error: null,
          verified: true,
        })
        ensureEdit(action)
      },
      onTask: (payload: AgentTaskEvent) => {
        if (controller.signal.aborted) return
        message.task = payload
      },
      onToken: (token) => {
        if (controller.signal.aborted) return
        const last = blocks[blocks.length - 1]
        if (last && last.kind === 'text') last.text += token
        else blocks.push({ kind: 'text', text: token })
      },
      onDone: (fullText) => {
        if (controller.signal.aborted) return
        if (fullText) {
          const last = blocks[blocks.length - 1]
          if (last && last.kind === 'text') last.text = fullText
          else blocks.push({ kind: 'text', text: fullText })
        }
        finish()
      },
      onError: (error) => {
        if (controller.signal.aborted) return
        blocks.push({
          kind: 'notice',
          text: `回复中断：${error.message}`,
          tone: 'error',
        })
        finish()
      },
    },
    controller.signal
  )
}

defineExpose({ open, close })
</script>

<template>
  <!-- 唤起按钮（固定右下角） -->
  <button
    aria-label="打开 AI 助手"
    class="fixed right-4 bottom-24 z-40 rounded-full w-12 h-12 bg-primary text-white shadow-lg flex items-center justify-center hover:opacity-90"
    @click="open"
    v-if="!visible"
  >
    <icon-tabler:message-2 />
  </button>

  <!-- 小窗主体 -->
  <div
    v-if="visible"
    ref="widgetRef"
    role="dialog"
    aria-label="Vibe AI 助手"
    class="ai-widget fixed z-40 w-[360px] max-w-[calc(100vw-16px)] max-h-[70vh] bg-white border rounded-xl shadow-xl flex flex-col overflow-hidden"
    :style="{ left: pos.x + 'px', top: pos.y + 'px' }"
  >
    <div
      class="h-10 px-3 flex items-center justify-between border-b cursor-move select-none"
      @mousedown="startDrag"
      @touchstart.prevent="startDrag"
    >
      <div class="font-medium text-sm">Vibe AI 助手</div>
      <div class="flex items-center gap-2">
        <button
          :aria-label="minimized ? '展开 AI 助手' : '最小化 AI 助手'"
          class="text-sm text-muted-foreground hover:text-foreground"
          @click="toggleMin"
        >
          <icon-tabler:minus v-if="!minimized" />
          <icon-tabler:plus v-else />
        </button>
        <button
          aria-label="关闭 AI 助手"
          class="text-sm text-muted-foreground hover:text-foreground"
          @click="close"
        >
          <icon-tabler:x />
        </button>
      </div>
    </div>

    <div
      v-show="!minimized"
      ref="messageListRef"
      class="flex-1 min-h-0 overflow-auto p-3 space-y-3 bg-white"
    >
      <p
        v-if="historyLoading"
        role="status"
        class="text-xs text-muted-foreground"
      >
        正在恢复聊天记录与卡片…
      </p>
      <div v-if="historyError" role="alert" class="text-xs text-red-500">
        {{ historyError }}
        <button class="ml-2 underline min-h-11" @click="open">重新加载</button>
      </div>
      <div v-for="(m, mi) in messages" :key="mi" class="text-sm space-y-2">
        <div :class="m.role === 'user' ? 'text-right' : 'text-left'">
          <span
            v-if="m.role === 'user'"
            class="inline-block bg-primary text-white px-3 py-2 rounded-lg max-w-[90%] whitespace-pre-wrap"
            >{{ m.blocks[0].kind === 'text' ? m.blocks[0].text : '' }}</span
          >
        </div>

        <template v-if="m.role === 'assistant'">
          <template v-for="(b, bi) in m.blocks" :key="bi">
            <!-- 普通文本 -->
            <div v-if="b.kind === 'text'" class="text-left">
              <div
                class="ai-markdown inline-block bg-muted px-3 py-2 rounded-lg max-w-[95%]"
                v-html="renderAiMarkdown(b.text)"
              />
            </div>

            <!-- 工具执行状态 -->
            <div
              v-else-if="b.kind === 'tool'"
              class="flex items-center gap-2 text-xs text-muted-foreground"
            >
              <icon-tabler:loader-2
                v-if="!b.done"
                class="animate-spin w-3.5 h-3.5"
              />
              <icon-tabler:circle-check
                v-else-if="b.ok"
                class="w-3.5 h-3.5 text-green-600"
              />
              <icon-tabler:alert-circle
                v-else
                class="w-3.5 h-3.5 text-amber-500"
              />
              <span>{{ b.label }}</span>
              <span v-if="b.done && b.durationMs != null" class="opacity-60"
                >{{ b.durationMs }}ms</span
              >
              <span v-if="b.done && b.code && !b.ok" class="opacity-60"
                >[{{ b.code }}]</span
              >
            </div>

            <!-- 结构化歌曲卡片 -->
            <div
              v-else-if="b.kind === 'songs'"
              class="border rounded-lg divide-y overflow-hidden"
              :aria-busy="b.busy"
            >
              <div
                class="px-2 py-1.5 text-xs text-muted-foreground bg-muted/40 flex items-center justify-between"
              >
                <span class="font-semibold">{{
                  b.search ? '歌曲搜索结果' : b.message
                }}</span>
                <span class="shrink-0"
                  >共 <strong>{{ b.total }}</strong> 首</span
                >
              </div>
              <div
                v-for="song in visibleSongs(b)"
                :key="song.songId"
                class="px-2 py-1.5 flex items-center gap-2 hover:bg-muted/40"
              >
                <img
                  v-if="song.coverUrl"
                  :src="song.coverUrl"
                  class="w-8 h-8 rounded object-cover"
                  alt=""
                />
                <div
                  v-else
                  class="w-8 h-8 rounded bg-muted flex items-center justify-center"
                >
                  <icon-tabler:music class="w-4 h-4 text-muted-foreground" />
                </div>
                <div class="flex-1 min-w-0">
                  <div
                    class="truncate text-sm font-bold"
                    :title="song.songName"
                  >
                    {{ song.songName }}
                  </div>
                  <div class="truncate text-xs text-muted-foreground">
                    {{ song.artistName || '未知歌手' }}
                    <span v-if="song.duration">
                      · {{ formatDuration(song.duration) }}</span
                    >
                  </div>
                </div>
                <button
                  class="w-11 h-11 shrink-0 flex items-center justify-center rounded hover:bg-muted disabled:opacity-40"
                  :disabled="!song.audioUrl"
                  :aria-label="`播放 ${song.songName}`"
                  title="播放"
                  @click="playSong(song)"
                >
                  <icon-tabler:player-play class="w-4 h-4" />
                </button>
              </div>
              <p
                v-if="!b.total && !b.busy"
                class="p-3 text-xs text-muted-foreground"
              >
                暂无匹配歌曲
              </p>
              <nav
                v-if="b.total > SONGS_PAGE_SIZE || b.search"
                aria-label="歌曲搜索分页"
                class="px-2 flex items-center justify-between gap-2 text-xs"
              >
                <button
                  class="min-h-11 px-2 text-primary disabled:opacity-40"
                  :disabled="b.busy || b.page <= 1"
                  @click="changeSongPage(b, b.page - 1)"
                >
                  上一页
                </button>
                <span
                  role="status"
                  aria-live="polite"
                  class="text-muted-foreground"
                >
                  {{
                    b.busy ? '加载中…' : `第 ${b.page} / ${songPageCount(b)} 页`
                  }}
                </span>
                <button
                  class="min-h-11 px-2 text-primary disabled:opacity-40"
                  :disabled="b.busy || b.page >= songPageCount(b)"
                  @click="changeSongPage(b, b.page + 1)"
                >
                  下一页
                </button>
              </nav>
              <div
                v-if="b.error"
                role="alert"
                class="px-2 py-1 text-xs text-red-500"
              >
                {{ b.error }}
                <button
                  class="min-h-11 ml-1 underline"
                  :disabled="b.busy"
                  @click="changeSongPage(b, b.page)"
                >
                  重试
                </button>
              </div>
            </div>

            <!-- 待确认卡片 -->
            <div
              v-else-if="b.kind === 'confirm'"
              class="border-2 border-primary/40 rounded-lg p-2 space-y-2"
            >
              <template v-if="b.action.status === 'PENDING'">
                <div class="text-sm font-bold">待确认：创建歌单</div>
                <input
                  v-model="ensureEdit(b.action).title"
                  maxlength="255"
                  class="w-full px-2 py-1 text-xs border rounded outline-none"
                  placeholder="歌单名称"
                  aria-label="歌单名称"
                  :disabled="b.busy || !b.verified"
                />
                <div
                  class="flex items-center justify-between text-[11px] text-muted-foreground"
                >
                  <span>共 {{ editSongs(b.action).length }} 首歌曲</span>
                  <label class="flex items-center gap-1 cursor-pointer">
                    <input
                      type="checkbox"
                      v-model="ensureEdit(b.action).isPublic"
                      :disabled="b.busy || !b.verified"
                    />
                    公开
                  </label>
                </div>
                <div class="max-h-40 overflow-auto divide-y border rounded">
                  <div
                    v-for="song in editSongs(b.action)"
                    :key="song.songId"
                    class="px-2 py-1 flex items-center gap-2"
                  >
                    <span class="flex-1 truncate text-[11px]"
                      ><strong>{{ song.songName }}</strong> —
                      {{ song.artistName || '未知歌手' }}</span
                    >
                    <button
                      class="p-0.5 rounded hover:bg-muted"
                      title="播放"
                      @click="playSong(song)"
                    >
                      <icon-tabler:player-play class="w-3.5 h-3.5" />
                    </button>
                    <button
                      class="p-0.5 rounded hover:bg-muted text-red-500"
                      title="移除"
                      :disabled="b.busy || !b.verified"
                      @click="removeSong(b.action, song.songId)"
                    >
                      <icon-tabler:trash class="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
                <div class="flex items-center gap-2">
                  <button
                    class="flex-1 px-2 py-1.5 text-xs rounded bg-primary text-white disabled:opacity-60"
                    :disabled="b.busy || !b.verified"
                    @click="confirmAction(b)"
                  >
                    {{ b.busy ? '处理中…' : '确认创建' }}
                  </button>
                  <button
                    class="flex-1 px-2 py-1.5 text-xs rounded border disabled:opacity-60"
                    :disabled="b.busy || !b.verified"
                    @click="cancelAction(b)"
                  >
                    取消
                  </button>
                </div>
              </template>

              <template v-else-if="b.action.status === 'SUCCEEDED'">
                <div class="flex items-center gap-1 text-xs text-green-700">
                  <icon-tabler:circle-check class="w-4 h-4" />
                  已创建歌单<strong>「{{ b.action.title }}」</strong>，共
                  {{ b.action.songCount }} 首歌曲
                </div>
                <button
                  v-if="b.action.playlistId"
                  class="w-full px-2 py-1.5 text-xs rounded bg-primary text-white"
                  @click="openPlaylist(b.action.playlistId!)"
                >
                  查看歌单
                </button>
              </template>

              <template v-else>
                <p class="text-sm font-bold">{{ b.action.title }}</p>
                <div class="flex items-center gap-1 text-xs text-amber-600">
                  <icon-tabler:alert-circle class="w-4 h-4" />
                  {{ b.action.statusDescription || b.action.status }}
                  <span v-if="b.action.errorMessage"
                    >：{{ b.action.errorMessage }}</span
                  >
                </div>
              </template>
              <div
                v-if="!b.verified || b.error"
                role="status"
                class="text-xs text-muted-foreground"
              >
                {{
                  b.busy ? '正在核对最新状态…' : b.error || '等待核对最新状态'
                }}
                <button
                  v-if="!b.busy && !b.verified"
                  class="ml-1 min-h-11 underline text-primary"
                  @click="refreshAction(b)"
                >
                  重试
                </button>
              </div>
            </div>

            <!-- 提示 -->
            <div
              v-else-if="b.kind === 'notice'"
              class="text-[11px]"
              :class="
                b.tone === 'error' ? 'text-red-500' : 'text-muted-foreground'
              "
            >
              {{ b.text }}
            </div>
          </template>

          <!-- 任务指标（真实耗时与用量） -->
          <div
            v-if="m.task"
            class="text-[10px] text-muted-foreground/80 border-t pt-1"
          >
            任务 {{ m.task.data.taskId.slice(0, 8) }} · 模型调用
            {{ m.task.data.modelCalls }} 次 · 工具
            {{ m.task.data.toolCalls }} 次 · 耗时 {{ m.task.data.latencyMs }}ms
            <span v-if="m.task.data.inputTokens != null">
              · Token {{ m.task.data.inputTokens }}/{{
                m.task.data.outputTokens
              }}
            </span>
            <span v-else>· Token 用量不可获取</span>
          </div>
        </template>
      </div>

      <div v-if="loading" class="text-xs text-muted-foreground">
        AI 正在处理...
      </div>
    </div>

    <div v-show="!minimized" class="p-2 border-t flex items-center gap-2">
      <input
        v-model="input"
        maxlength="2000"
        aria-label="发给 AI 助手的消息"
        type="text"
        placeholder="试试：帮我建一个适合开车听的中文歌单"
        @keyup.enter="send"
        class="flex-1 min-w-0 px-3 py-2 rounded-md bg-white border text-sm"
      />
      <button
        class="px-3 h-9 rounded-md bg-primary text-white disabled:opacity-60 text-sm"
        :disabled="loading || historyLoading"
        @click="send"
      >
        发送
      </button>
    </div>
  </div>
</template>

<style scoped>
.ai-widget :is(button, input):focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}
.ai-markdown {
  overflow-wrap: anywhere;
  line-height: 1.75;
}
.ai-markdown :deep(p + p),
.ai-markdown :deep(ul),
.ai-markdown :deep(ol) {
  margin-top: 0.65em;
}
.ai-markdown :deep(ul) {
  list-style: disc;
  padding-left: 1.25em;
}
.ai-markdown :deep(ol) {
  list-style: decimal;
  padding-left: 1.5em;
}
.ai-markdown :deep(li + li) {
  margin-top: 0.3em;
}
.ai-markdown :deep(strong) {
  font-weight: 700;
}
.ai-markdown :deep(pre) {
  white-space: pre-wrap;
}
.ai-markdown :deep(h1),
.ai-markdown :deep(h2),
.ai-markdown :deep(h3) {
  font-size: 1em;
  font-weight: 700;
  margin-block: 0.5em;
}
</style>
