<script setup lang="ts">
import { aiRecent, aiSongPage, aiStreamChat, listConversations } from '@/api/ai'
import type {
  AgentSongsEvent,
  AgentTaskEvent,
  AgentToolEvent,
  ConversationVO,
} from '@/api/ai'
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
  hasMoreSongs,
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

// ---------------------------------------------------------------- 会话
/**
 * 当前会话标识。
 *
 * `null` 有两种含义，靠 {@link pendingNewConversation} 区分：
 * 「还没确定」（首次打开、由服务端决定用哪段对话）与「新对话尚未落库」。
 * 真实标识一律由服务端给出（首次请求的 start 事件或会话列表），
 * 前端不自己拼 —— 归属判定在服务端，前端编出来的 ID 只会得到 404。
 */
const activeChatId = ref<string | null>(null)
/** 已点「新对话」，等待第一条消息把它落库。 */
const pendingNewConversation = ref(false)
const conversations = ref<ConversationVO[]>([])
const conversationsLoading = ref(false)
const conversationsError = ref('')
const drawerOpen = ref(false)

/** 游客没有历史会话列表（服务端 401），入口直接隐藏而不是点了报错。 */
const canListConversations = computed(() => !!userStore.userInfo?.token)

/**
 * 标题优先取本地首条用户消息，而不是会话列表里的值。
 *
 * 两者内容相同（服务端也是截断首条用户消息），但本地取值是即时的：
 * 刚发出第一条消息时列表还没刷新，用列表值会先显示「新对话」再跳变。
 */
const currentTitle = computed(() => {
  if (pendingNewConversation.value) return '新对话'
  const first = messages.value.find((m) => m.role === 'user')?.blocks[0]
  const local = first && first.kind === 'text' ? first.text : ''
  const fromList = conversations.value.find(
    (c) => c.chatId === activeChatId.value
  )?.title
  const title = (local || fromList || '').trim().replace(/\s+/g, ' ')
  return title || '新对话'
})

/** 会话列表里的相对时间。服务端下发的是无时区的本地时间，直接按本地解析。 */
const relativeTime = (value: string | null) => {
  if (!value) return ''
  const then = new Date(value).getTime()
  if (!Number.isFinite(then)) return ''
  const diff = Date.now() - then
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 604_800_000) return `${Math.floor(diff / 86_400_000)} 天前`
  const d = new Date(then)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

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
// 会话状态也必须一起清：否则上一位用户的 chatId 会被带进新身份的请求里（必然 404），
// 或者更糟 —— 会话列表里残留别人的标题。
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
    activeChatId.value = null
    pendingNewConversation.value = false
    conversations.value = []
    conversationsError.value = ''
    drawerOpen.value = false
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

/**
 * 探测「按这个条件曲库里一共有多少首」，<b>不改动卡片上已有的歌曲</b>。
 *
 * <p>只用来决定要不要显示「查看更多」以及括号里的数字。工具返回的就是按 id 排序的第一页，
 * 所以这里拿到的 `total` 必然 ≥ 已有条数；请求失败只是让入口不出现，卡片照常显示。
 */
const probeLibraryTotal = async (block: Extract<Block, { kind: 'songs' }>) => {
  if (!block.search || block.remote) return
  const epoch = identityEpoch
  try {
    const result = await aiSongPage(block.search, 1, SONGS_PAGE_SIZE)
    if (epoch !== identityEpoch || block.remote) return
    block.libraryTotal = result.total
  } catch {
    // 探测失败不影响推荐卡片本身：保持 libraryTotal = null，即不显示「查看更多」。
  }
}

/**
 * 把卡片展开成服务端翻页浏览。
 *
 * <p>展开之后 `songs` 变成「当前页」，`total` 变成曲库总数 —— 此时卡片不再是「为你挑的几首」，
 * 而是「按这个条件的全部结果」。这个语义切换必须在用户主动点击之后发生，不能自动。
 */
const expandSongs = async (block: Extract<Block, { kind: 'songs' }>) => {
  if (block.busy || !block.search) return
  block.remote = true
  await changeSongPage(block, 1)
}

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
    block.libraryTotal = result.total
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

/**
 * 拉取当前用户的最近会话。登录用户之外不会调用（服务端 401）。
 *
 * <p>这里刻意不设置 `activeChatId`：它只负责「列表」这一件事，
 * 要不要采纳列表里的第一条由调用方决定（首次打开采纳，切换时不采纳）。
 */
const refreshConversations = async () => {
  if (!canListConversations.value) return
  const epoch = identityEpoch
  conversationsLoading.value = true
  conversationsError.value = ''
  try {
    const res = await listConversations(20)
    if (epoch !== identityEpoch) return
    if (res.code !== 0) throw new Error(res.message || '加载失败')
    conversations.value = res.data ?? []
  } catch {
    if (epoch === identityEpoch)
      conversationsError.value = '历史对话加载失败，请重试。'
  } finally {
    if (epoch === identityEpoch) conversationsLoading.value = false
  }
}

/**
 * 把指定会话的历史消息与待确认卡片加载到界面上。
 *
 * @param chatId 目标会话；`null` 表示交给服务端决定（登录用户是默认会话，游客是当前会话）
 * @return 历史是否加载成功。失败时调用方负责决定是留在原地还是回退。
 */
const loadConversation = async (chatId: string | null): Promise<boolean> => {
  const epoch = identityEpoch
  historyLoading.value = true
  historyError.value = ''
  historyLoaded.value = false
  messages.value = []
  Object.keys(edits).forEach((key) => delete edits[key])
  try {
    // 工具调用同样占历史行数，取足够窗口后按会话轮次恢复。
    const list = await aiRecent(100, chatId)
    if (epoch !== identityEpoch) return false
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
        .map((b) => probeLibraryTotal(b))
    )
    if (epoch !== identityEpoch) return false
    await restorePending()
    return true
  } catch {
    if (epoch === identityEpoch)
      historyError.value = '聊天记录加载失败，请重试。'
    return false
  } finally {
    if (epoch === identityEpoch) {
      historyLoading.value = false
      void scrollToLatest()
    }
  }
}

/** 重新加载当前会话。覆盖两种失败：首次打开失败、以及切换失败后的重试。 */
const retryHistory = () => {
  historyError.value = ''
  if (pendingNewConversation.value) return
  historyLoaded.value = false
  void loadConversation(activeChatId.value)
}

const open = async () => {
  visible.value = true
  if (historyLoading.value || loading.value) return
  if (historyLoaded.value) {
    historyError.value = ''
    await restorePending()
    return
  }
  const epoch = identityEpoch
  // 先取会话列表：登录用户的「当前会话」就是列表第一条，
  // 而且这是拿到 chatId 的唯一途径 —— 待确认卡片必须按会话查询，不能全省。
  if (canListConversations.value) {
    await refreshConversations()
    if (epoch !== identityEpoch) return
    if (
      !activeChatId.value &&
      !pendingNewConversation.value &&
      conversations.value.length
    ) {
      activeChatId.value = conversations.value[0].chatId
    }
  }
  await loadConversation(activeChatId.value)
}

/** 切换会话。失败时保留原会话界面 —— 否则用户未发送的内容会随着界面一起消失。 */
const switchConversation = async (chatId: string) => {
  drawerOpen.value = false
  if (chatId === activeChatId.value && !pendingNewConversation.value) return

  // 切换必须中断当前生成：否则上一段的 token 会继续追加到新会话的界面上，
  // 变成两段对话的文字混在一起。
  abortController.value?.abort()
  abortController.value = null
  loading.value = false

  const previous = {
    chatId: activeChatId.value,
    pending: pendingNewConversation.value,
    messages: messages.value,
    taskId: taskId.value,
  }
  activeChatId.value = chatId
  pendingNewConversation.value = false
  taskId.value = null

  if (await loadConversation(chatId)) return

  activeChatId.value = previous.chatId
  pendingNewConversation.value = previous.pending
  taskId.value = previous.taskId
  messages.value = previous.messages
  historyLoaded.value = true
  historyError.value = '切换会话失败，已保留当前会话。请重试。'
}

/**
 * 新对话。
 *
 * <p><b>只清界面、不建会话。</b>会话标识由服务端在收到第一条消息时才创建，
 * 前端拿不到也就不可能"顺手"建出一堆点开什么都没有的空会话。
 * 用户点开又没说话，服务端不会留下任何痕迹。
 */
const startNewConversation = () => {
  abortController.value?.abort()
  abortController.value = null
  loading.value = false
  taskId.value = null
  messages.value = []
  Object.keys(edits).forEach((key) => delete edits[key])
  historyLoading.value = false
  historyError.value = ''
  // 新对话没有历史要拉，直接标成已加载，避免 open() 再走一次空请求。
  historyLoaded.value = true
  drawerOpen.value = false
  activeChatId.value = null
  pendingNewConversation.value = true
  void scrollToLatest()
}

const toggleDrawer = () => {
  drawerOpen.value = !drawerOpen.value
  if (drawerOpen.value) void refreshConversations()
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
  // 会话还没确定（新对话尚未落库）时没有"本会话的待确认任务"可查。
  // 这里必须带上 chatId：不传的话服务端会返回该用户**全部会话**的待确认任务，
  // 于是会话 B 里会冒出会话 A 的确认卡片，而那张卡片点下去是真的会建歌单的。
  const chatId = activeChatId.value
  if (!chatId) return
  try {
    const res = await listPendingActions(chatId)
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
  // 读一次就固定下来：onStart 里会把它清掉，而这次请求要一直用同一个目标。
  const isNewConversation = pendingNewConversation.value
  const targetChatId = activeChatId.value

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
    // 标题与排序都变了，刷新一次列表让「最近对话」抽屉保持真实。
    if (canListConversations.value) void refreshConversations()
  }

  aiStreamChat(
    text,
    {
      onStart: (payload) => {
        if (controller.signal.aborted) return
        taskId.value = payload.taskId
        // 认领会话标识。新对话的 chatId 是服务端在这一刻才创建的，
        // 前端只有从这里才能知道自己在哪段对话里 —— 后续的历史查询、
        // 待确认卡片查询、以及下一轮消息都依赖它。
        if (payload.chatId) {
          activeChatId.value = payload.chatId
          pendingNewConversation.value = false
        }
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
        if (block.search) void probeLibraryTotal(block)
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
    controller.signal,
    // 新对话用 newConversation 标志（chatId 还不存在）；否则带上当前会话。
    isNewConversation ? { newConversation: true } : { chatId: targetChatId }
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
      class="h-10 px-2 flex items-center justify-between gap-1 border-b cursor-move select-none"
      @mousedown="startDrag"
      @touchstart.prevent="startDrag"
    >
      <div class="flex items-center gap-1 min-w-0">
        <button
          aria-label="新对话"
          title="新对话"
          class="shrink-0 w-8 h-8 flex items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          @mousedown.stop
          @click.stop="startNewConversation"
        >
          <icon-tabler:plus />
        </button>
        <span class="truncate text-sm font-medium" :title="currentTitle">{{
          currentTitle
        }}</span>
      </div>
      <div class="flex items-center gap-1 shrink-0">
        <button
          v-if="canListConversations"
          :aria-label="drawerOpen ? '关闭最近对话' : '最近对话'"
          title="最近对话"
          class="w-8 h-8 flex items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          @mousedown.stop
          @click.stop="toggleDrawer"
        >
          <icon-tabler:history />
        </button>
        <button
          :aria-label="minimized ? '展开 AI 助手' : '最小化 AI 助手'"
          class="w-8 h-8 flex items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          @mousedown.stop
          @click.stop="toggleMin"
        >
          <icon-tabler:minus v-if="!minimized" />
          <icon-tabler:plus v-else />
        </button>
        <button
          aria-label="关闭 AI 助手"
          class="w-8 h-8 flex items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          @mousedown.stop
          @click.stop="close"
        >
          <icon-tabler:x />
        </button>
      </div>
    </div>

    <!--
      最近对话。刻意用浮窗内的内嵌面板而不是全局抽屉：
      浮窗本身是拖拽定位的，全局抽屉会和它的层级、定位互相打架，
      而这里只需要盖住消息区，交互范围小得多。
    -->
    <div
      v-if="drawerOpen && !minimized"
      class="absolute inset-x-0 top-10 bottom-0 z-10 bg-white flex flex-col"
    >
      <div
        class="px-3 py-2 border-b flex items-center justify-between shrink-0"
      >
        <span class="text-sm font-medium">最近对话</span>
        <button
          aria-label="关闭最近对话"
          class="w-8 h-8 flex items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          @click="drawerOpen = false"
        >
          <icon-tabler:x />
        </button>
      </div>
      <p
        v-if="conversationsLoading"
        role="status"
        class="p-3 text-xs text-muted-foreground"
      >
        正在加载…
      </p>
      <div
        v-else-if="conversationsError"
        role="alert"
        class="p-3 text-xs text-red-500"
      >
        {{ conversationsError }}
        <button class="ml-1 underline" @click="refreshConversations">
          重试
        </button>
      </div>
      <p
        v-else-if="!conversations.length"
        class="p-3 text-xs text-muted-foreground"
      >
        还没有历史对话。发送第一条消息后就会出现在这里。
      </p>
      <ul v-else class="flex-1 min-h-0 overflow-auto divide-y">
        <li v-for="item in conversations" :key="item.chatId">
          <button
            class="w-full text-left px-3 py-2 hover:bg-muted/50"
            :class="item.chatId === activeChatId ? 'bg-muted/60' : ''"
            @click="switchConversation(item.chatId)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="truncate text-sm font-medium">{{
                item.title || '未命名对话'
              }}</span>
              <span class="shrink-0 text-[11px] text-muted-foreground">{{
                relativeTime(item.lastActiveAt)
              }}</span>
            </div>
            <div class="truncate text-xs text-muted-foreground mt-0.5">
              {{ item.summaryText || item.lastMessage || '暂无内容' }}
            </div>
          </button>
        </li>
      </ul>
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
        <button class="ml-2 underline min-h-11" @click="retryHistory">
          重新加载
        </button>
      </div>
      <p
        v-if="!historyLoading && !historyError && !messages.length"
        class="text-xs text-muted-foreground"
      >
        想听点什么？告诉我喜欢的歌手、音乐风格或当前的心情吧。
      </p>
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
                  b.remote ? '歌曲搜索结果' : b.message
                }}</span>
                <!--
                  「为你挑的 N 首」与「曲库共 M 首」是两件事，必须分开显示：
                  展开前 N 是工具实际返回的条数（推荐默认 5），展开后才是筛选结果总数。
                  曾经把曲库总数直接写在这里，导致只推荐了 5 首却显示「共 57 首」。
                -->
                <span class="shrink-0">
                  <template v-if="b.remote"
                    >共 <strong>{{ b.total }}</strong> 首</template
                  >
                  <template v-else
                    >为你挑的 <strong>{{ b.songs.length }}</strong> 首</template
                  >
                </span>
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
              <!--
                「查看更多」只在曲库里确实还有更多时才出现（hasMoreSongs 依赖探测到的 libraryTotal）。
                它是把推荐卡片切换成检索结果浏览的唯一入口 —— 不点就永远只看到模型挑的那几首。
              -->
              <button
                v-if="hasMoreSongs(b)"
                class="w-full min-h-11 px-2 text-xs text-primary hover:bg-muted/40"
                :disabled="b.busy"
                @click="expandSongs(b)"
              >
                查看更多（曲库共 {{ b.libraryTotal }} 首）
              </button>
              <nav
                v-if="b.remote || b.total > SONGS_PAGE_SIZE"
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
