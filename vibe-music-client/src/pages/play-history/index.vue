<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  clearPlayHistory,
  deletePlayHistory,
  getPlayHistoryPage,
} from '@/api/playHistory'
import type { PlayHistoryItem } from '@/api/playHistory'
import type { Result } from '@/api/system'
import { UserStore } from '@/stores/modules/user'
import { AudioStore } from '@/stores/modules/audio'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import AuthTabs from '@/components/Auth/AuthTabs.vue'
import defaultAlbum from '@/assets/default_album.jpg'
import { formatMillisecondsToTime } from '@/utils'

/**
 * 「最近播放」页面。
 *
 * <p>刻意不复用 `components/Table.vue`：那张表的列是「喜欢 / 时长 / 下载」，
 * 而这里需要的是「最近播放时间 / 播放次数 / 移除」。硬塞进去要么改 Table 的语义
 * （会影响曲库、歌单、专辑等所有页面），要么加一堆开关。这里用同源的样式变量
 * 自己排一张表，视觉上与曲库一致，但互不影响。
 *
 * <p>状态机：loading → (empty | error | list)。三种状态都有明确的出口，
 * 不会出现「转圈转到天荒地老」或「失败了还显示旧数据」。
 */

const PAGE_SIZE = 20

const route = useRoute()
const user = UserStore()
const audio = AudioStore()
const { loadTrack, play } = useAudioPlayer()

const items = ref<PlayHistoryItem[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const clearing = ref(false)
const loadError = ref('')
const actionError = ref('')
const authOpen = ref(false)
const playingId = ref<number | null>(null)

/** 当前账号标识：换号/退出时据此丢弃迟到的响应，避免把上一个账号的数据显示给下一个人。 */
const account = computed(() =>
  user.isLoggedIn ? `${user.userInfo?.userId}:${user.userInfo?.token}` : ''
)

const hasMore = computed(() => items.value.length < total.value)
const isEmpty = computed(
  () => !loading.value && !loadError.value && items.value.length === 0
)

let epoch = 0
let request = 0
let controller = new AbortController()

function message(error: unknown) {
  if (error && typeof error === 'object' && 'response' in error) {
    const response = error.response as
      | { data?: { message?: unknown } }
      | undefined
    const reason = response?.data?.message
    if (typeof reason === 'string' && reason.trim()) return reason
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}

function requireData<T>(result: Result<T>): T {
  if (result.code !== 0 || result.data === undefined || result.data === null) {
    throw new Error(result.message || '操作失败，请重试')
  }
  return result.data
}

/**
 * 格式化「最近播放时间」。
 *
 * <p>刚刚 / N 分钟前 / 今天 HH:mm / 昨天 HH:mm / MM-DD HH:mm / YYYY-MM-DD HH:mm。
 * 相对时间比绝对时间更容易扫读，但超过「昨天」之后相对时间就失去意义了，
 * 所以直接切回绝对时间。
 */
function formatPlayedAt(value: string | null | undefined): string {
  if (!value) return '—'
  // 后端返回 yyyy-MM-dd HH:mm:ss（不带时区）。Safari 不接受带空格的格式，
  // 因此先换成 ISO 风格再解析，避免在部分浏览器里得到 Invalid Date。
  const parsed = new Date(value.replace(' ', 'T'))
  if (Number.isNaN(parsed.getTime()))
    return value.replace('T', ' ').slice(0, 16)

  const now = new Date()
  const diffMs = now.getTime() - parsed.getTime()
  const diffMinutes = Math.floor(diffMs / 60000)
  if (diffMinutes < 1) return '刚刚'
  if (diffMinutes < 60) return `${diffMinutes} 分钟前`

  const pad = (n: number) => String(n).padStart(2, '0')
  const time = `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
  const sameDay =
    parsed.getFullYear() === now.getFullYear() &&
    parsed.getMonth() === now.getMonth() &&
    parsed.getDate() === now.getDate()
  if (sameDay) return `今天 ${time}`

  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  const isYesterday =
    parsed.getFullYear() === yesterday.getFullYear() &&
    parsed.getMonth() === yesterday.getMonth() &&
    parsed.getDate() === yesterday.getDate()
  if (isYesterday) return `昨天 ${time}`

  const date = `${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}`
  if (parsed.getFullYear() === now.getFullYear()) return `${date} ${time}`
  return `${parsed.getFullYear()}-${date} ${time}`
}

/** 加载第一页（或重试）。 */
async function load() {
  if (!account.value) return
  const current = ++request
  const currentEpoch = epoch
  loading.value = true
  loadError.value = ''
  actionError.value = ''
  try {
    const res = await getPlayHistoryPage(
      page.value,
      PAGE_SIZE,
      '',
      controller.signal
    )
    if (current !== request || currentEpoch !== epoch) return
    const data = requireData(res)
    items.value = data.items
    total.value = data.total
  } catch (error) {
    if (current !== request || currentEpoch !== epoch) return
    loadError.value = message(error)
    items.value = []
    total.value = 0
  } finally {
    if (current === request && currentEpoch === epoch) loading.value = false
  }
}

/** 加载下一页并追加（「加载更多」）。 */
async function loadMore() {
  if (!account.value || loading.value || !hasMore.value) return
  const current = ++request
  const currentEpoch = epoch
  loading.value = true
  actionError.value = ''
  const nextPage = page.value + 1
  try {
    const res = await getPlayHistoryPage(
      nextPage,
      PAGE_SIZE,
      '',
      controller.signal
    )
    if (current !== request || currentEpoch !== epoch) return
    const data = requireData(res)
    // 按 historyId 去重：翻页期间如果有人重复播放，同一首歌会跳到第一页，
    // 直接 concat 会在列表里出现两条一样的记录。
    const known = new Set(items.value.map((item) => item.historyId))
    items.value = [
      ...items.value,
      ...data.items.filter((item) => !known.has(item.historyId)),
    ]
    total.value = data.total
    page.value = nextPage
  } catch (error) {
    if (current === request && currentEpoch === epoch)
      actionError.value = message(error)
  } finally {
    if (current === request && currentEpoch === epoch) loading.value = false
  }
}

/** 点击一行：用现有播放器播放这首歌。 */
async function playSong(item: PlayHistoryItem) {
  if (!item.audioUrl) {
    ElMessage.error('这首歌暂时无法播放')
    return
  }
  if (playingId.value !== null) return
  playingId.value = item.songId
  const currentEpoch = epoch
  try {
    // 只播这一首：历史页的顺序就是「最近播放」，整页入队会让「下一首」变成
    // 「上一首刚听过的歌」，反而奇怪。
    audio.setAudioStore('trackList', [
      {
        id: String(item.songId),
        title: item.songName,
        artist: item.artistName,
        album: item.album,
        cover: item.coverUrl || defaultAlbum,
        url: item.audioUrl,
        duration: Number(item.duration) * 1000,
        likeStatus: 0,
      },
    ])
    audio.setAudioStore('currentSongIndex', 0)
    await loadTrack()
    if (currentEpoch === epoch) play()
  } catch {
    if (currentEpoch === epoch) ElMessage.error('暂时无法播放这首歌曲')
  } finally {
    if (currentEpoch === epoch) playingId.value = null
  }
}

/** 删除单条。成功后本地移除，不整页重刷 —— 用户不会看到列表闪一下。 */
async function removeOne(item: PlayHistoryItem) {
  const currentEpoch = epoch
  actionError.value = ''
  try {
    await deletePlayHistory(item.historyId, controller.signal)
  } catch (error) {
    if (currentEpoch === epoch) actionError.value = message(error)
    return
  }
  if (currentEpoch !== epoch) return
  items.value = items.value.filter((row) => row.historyId !== item.historyId)
  total.value = Math.max(0, total.value - 1)
  ElMessage.success('已从最近播放中移除')
}

/** 清空历史：必须二次确认，且说清楚「只影响你自己」。 */
async function clearAll() {
  if (clearing.value) return
  const currentEpoch = epoch
  try {
    await ElMessageBox.confirm(
      `清空后「最近播放」将不再保留任何记录（共 ${total.value} 首），此操作无法撤销。只影响你自己的历史，不会影响其他人。`,
      '清空最近播放',
      {
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    return // 用户取消
  }
  if (currentEpoch !== epoch) return
  clearing.value = true
  actionError.value = ''
  try {
    const res = await clearPlayHistory(controller.signal)
    if (currentEpoch !== epoch) return
    if (res.code !== 0) throw new Error(res.message || '清空失败，请重试')
    items.value = []
    total.value = 0
    page.value = 1
    loadError.value = ''
    ElMessage.success('最近播放已清空')
  } catch (error) {
    if (currentEpoch === epoch) actionError.value = message(error)
  } finally {
    if (currentEpoch === epoch) clearing.value = false
  }
}

/**
 * 换号 / 退出 / 重新进入页面时重置全部状态。
 *
 * <p>`epoch` 让所有在途请求作废：账号 A 的响应绝不能落到账号 B 的界面上。
 */
watch(
  [account, () => route.path],
  () => {
    epoch++
    request++
    controller.abort()
    controller = new AbortController()
    items.value = []
    total.value = 0
    page.value = 1
    loading.value = false
    clearing.value = false
    playingId.value = null
    loadError.value = ''
    actionError.value = ''
    load()
  },
  { immediate: true, flush: 'sync' }
)

onBeforeUnmount(() => {
  epoch++
  request++
  controller.abort()
})
</script>

<template>
  <section
    class="play-history h-full min-h-0 overflow-y-auto bg-background p-4 md:p-6"
  >
    <template v-if="!user.isLoggedIn">
      <h1 class="text-2xl font-semibold mb-2">最近播放</h1>
      <el-empty description="登录后可以查看你最近听过的歌曲">
        <el-button type="primary" @click="authOpen = true">登录</el-button>
      </el-empty>
    </template>

    <template v-else>
      <header class="flex flex-wrap items-start justify-between gap-4 mb-6">
        <div>
          <h1 class="text-2xl font-semibold">最近播放</h1>
          <p class="text-sm text-muted-foreground mt-2">
            记录你真正听过的歌<span v-if="total > 0">，共 {{ total }} 首</span>
          </p>
        </div>
        <button
          type="button"
          class="ph-action ph-action--danger"
          :disabled="clearing || loading || total === 0"
          @click="clearAll"
        >
          <icon-tabler:trash class="text-base" />
          {{ clearing ? '正在清空…' : '清空历史' }}
        </button>
      </header>

      <p v-if="actionError" role="alert" class="text-destructive mb-4">
        {{ actionError }}
      </p>

      <!-- 加载中：首次进入才显示整块骨架，加载更多时只让按钮转圈 -->
      <div
        v-if="loading && items.length === 0"
        class="py-16 text-center"
        role="status"
      >
        正在加载最近播放…
      </div>

      <!-- 请求失败：给明确的重试入口 -->
      <div v-else-if="loadError" class="py-14 text-center" role="alert">
        <p class="mb-4">{{ loadError }}</p>
        <el-button @click="load">重新加载</el-button>
      </div>

      <!-- 空数据 -->
      <el-empty v-else-if="isEmpty" description="还没有播放记录，去听一首歌吧">
        <RouterLink to="/library" class="ph-action ph-action--primary">
          <icon-solar:play-line-duotone class="text-lg" />
          去曲库逛逛
        </RouterLink>
      </el-empty>

      <template v-else>
        <div class="ph-table">
          <div class="ph-head" aria-hidden="true">
            <span class="ph-col-index">#</span>
            <span class="ph-col-cover"></span>
            <span class="ph-col-title">标题</span>
            <span class="ph-col-artist">歌手</span>
            <span class="ph-col-album">专辑</span>
            <span class="ph-col-time">最近播放</span>
            <span class="ph-col-count">次数</span>
            <span class="ph-col-duration">时长</span>
            <span class="ph-col-remove">操作</span>
          </div>

          <!--
            行内用 div + role="button" 而不是 <button>：这一行内部还要放
            RouterLink（歌手 / 专辑跳转），而 <a> 嵌套在 <button> 里是无效 HTML，
            浏览器会把行拆开、点击落到链接上 —— 实测点「歌曲名」会跳到专辑页而不是播放。
            div 上保留 tabindex/role/键盘事件，键盘可达性与 button 一致。

            注意：这段注释必须放在 v-for 之外。放在 <li> 内部时 Vue 会把它
            当作 v-for 内容的一部分渲染成文本节点（实测污染了无障碍名称）。
          -->
          <ul class="ph-body">
            <li
              v-for="(item, index) in items"
              :key="item.historyId"
              class="ph-row"
              :class="{ 'is-playing': playingId === item.songId }"
            >
              <div
                class="ph-row-main"
                role="button"
                tabindex="0"
                :aria-label="`播放《${item.songName}》`"
                @click="playSong(item)"
                @keydown.enter.prevent="playSong(item)"
                @keydown.space.prevent="playSong(item)"
              >
                <span class="ph-col-index">{{ index + 1 }}</span>
                <span class="ph-col-cover group">
                  <img
                    :src="item.coverUrl || defaultAlbum"
                    :alt="item.songName"
                    loading="lazy"
                  />
                  <span class="ph-cover-play">
                    <icon-tabler:player-play-filled class="text-lg" />
                  </span>
                </span>
                <span class="ph-col-title" :title="item.songName">
                  {{ item.songName }}
                </span>
                <span class="ph-col-artist" :title="item.artistName">
                  <template v-if="item.artists?.length">
                    <template
                      v-for="(artist, i) in item.artists"
                      :key="artist.artistId"
                    >
                      <span v-if="i > 0" class="text-muted-foreground">
                        /
                      </span>
                      <RouterLink
                        class="meta-link"
                        :to="`/artist/${artist.artistId}`"
                        :title="`进入 ${artist.artistName} 的主页`"
                        @click.stop
                        >{{ artist.artistName }}</RouterLink
                      >
                    </template>
                  </template>
                  <template v-else>{{ item.artistName }}</template>
                </span>
                <span class="ph-col-album" :title="item.album">
                  <RouterLink
                    v-if="item.albumId"
                    class="meta-link"
                    :to="`/album/${item.albumId}`"
                    :title="`进入《${item.album}》专辑页`"
                    @click.stop
                    >{{ item.album }}</RouterLink
                  >
                  <template v-else>{{ item.album }}</template>
                </span>
                <span class="ph-col-time">{{
                  formatPlayedAt(item.lastPlayedAt)
                }}</span>
                <span class="ph-col-count">{{ item.playCount }}</span>
                <span class="ph-col-duration">
                  {{ formatMillisecondsToTime(Number(item.duration) * 1000) }}
                </span>
              </div>
              <span class="ph-col-remove">
                <button
                  type="button"
                  class="ph-remove"
                  :aria-label="`将《${item.songName}》从最近播放中移除`"
                  :title="`将《${item.songName}》从最近播放中移除`"
                  @click.stop="removeOne(item)"
                >
                  <icon-material-symbols:delete-outline class="text-lg" />
                </button>
              </span>
            </li>
          </ul>
        </div>

        <!-- 分页：一次只加载 20 条，避免历史很长时首屏卡住 -->
        <div v-if="hasMore" class="mt-6 flex justify-center">
          <el-button :loading="loading" @click="loadMore">
            {{ loading ? '正在加载…' : '加载更多' }}
          </el-button>
        </div>
        <p v-else class="mt-6 text-center text-sm text-muted-foreground">
          已经到底了
        </p>
      </template>
    </template>

    <AuthTabs v-model="authOpen" />
  </section>
</template>

<style scoped>
/*
 * 列宽与 components/Table.vue 的节奏保持一致（gap-6 / 10 号封面 / 行内 padding），
 * 但列数是历史页自己的（多了「最近播放 / 次数」，少了「喜欢 / 下载」）。
 *
 * 之所以用 CSS 变量而不是 Tailwind 的 grid-cols-[...]：这里列多且要响应式隐藏，
 * 变量写法更容易在中途改列而不影响其他页面。
 */
.ph-table {
  --ph-gap: 1.5rem;
  --ph-cols: 2rem 2.5rem minmax(0, 4fr) minmax(0, 3fr) minmax(0, 3fr) 7.5rem
    3.5rem 4rem 3rem;
}

.ph-head {
  display: grid;
  grid-template-columns: var(--ph-cols);
  gap: var(--ph-gap);
  align-items: center;
  padding: 0.5rem;
  font-size: 0.875rem;
  color: hsl(var(--muted-foreground));
}

.ph-body {
  display: flex;
  flex-direction: column;
}

.ph-row {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  border-radius: 1rem;
  transition: background-color 0.18s ease;
}

.ph-row:hover,
.ph-row:focus-within {
  background: hsl(var(--hover-menu-bg));
}

.ph-row.is-playing {
  background: hsl(var(--hover-menu-bg));
}

.ph-row-main {
  display: grid;
  grid-template-columns: var(--ph-cols);
  gap: var(--ph-gap);
  align-items: center;
  width: 100%;
  padding: 0.5rem;
  border: none;
  background: transparent;
  color: inherit;
  text-align: left;
  font: inherit;
  cursor: pointer;
  border-radius: 1rem;
  /* 用 div 模拟按钮后需要自己处理：浏览器默认会给 button 加 user-select:none */
  user-select: none;
}

.ph-row-main:focus-visible {
  outline: 2px solid hsl(var(--ring));
  outline-offset: -2px;
}

.ph-col-index {
  text-align: center;
  font-size: 0.875rem;
  color: hsl(var(--muted-foreground));
}

.ph-col-cover {
  position: relative;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 0.375rem;
  overflow: hidden;
  background: hsl(var(--muted));
  flex-shrink: 0;
}

.ph-col-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.ph-cover-play {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: rgb(0 0 0 / 0.5);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.ph-row:hover .ph-cover-play,
.ph-row:focus-within .ph-cover-play {
  opacity: 1;
}

.ph-col-title,
.ph-col-artist,
.ph-col-album {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ph-col-time,
.ph-col-count,
.ph-col-duration {
  font-size: 0.8125rem;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
}

.ph-col-remove {
  display: flex;
  align-items: center;
  justify-content: center;
  padding-right: 0.25rem;
}

.ph-remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border: none;
  border-radius: 9999px;
  background: transparent;
  color: hsl(var(--muted-foreground));
  cursor: pointer;
  opacity: 0;
  transition:
    opacity 0.18s ease,
    color 0.18s ease,
    background-color 0.18s ease;
}

.ph-row:hover .ph-remove,
.ph-row:focus-within .ph-remove {
  opacity: 1;
}

/* 悬停变红：与「我的歌单」的移出按钮同一套语义。刻意不用 --destructive，
   它在暗色主题里是深红背景色，当文字色会看不清。 */
.ph-remove:hover {
  color: #ef4444;
  background-color: rgb(239 68 68 / 0.12);
}

.ph-remove:focus-visible {
  opacity: 1;
  outline: 2px solid hsl(var(--ring));
  outline-offset: 2px;
}

/* 歌手 / 专辑：与曲库同一套可点击样式（悬停变绿），保证跨页一致 */
.meta-link {
  color: inherit;
  border-radius: 2px;
  transition: color 0.15s ease;
}

.meta-link:hover,
.meta-link:focus-visible {
  color: var(--link-hover);
}

.meta-link:focus-visible {
  outline: 2px solid var(--link-hover);
  outline-offset: 1px;
}

/* 操作按钮：主操作实心、危险操作只留红字，与「我的歌单」的层级一致 */
.ph-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  height: 2.5rem;
  padding: 0 1.25rem;
  border: 1px solid hsl(var(--input));
  border-radius: var(--radius);
  background: transparent;
  color: hsl(var(--foreground));
  font-size: 0.875rem;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  text-decoration: none;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    filter 0.18s ease;
}

.ph-action:hover:not(:disabled) {
  background: hsl(var(--muted));
}

.ph-action:focus-visible {
  outline: 2px solid hsl(var(--ring));
  outline-offset: 2px;
}

.ph-action:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.ph-action--primary {
  border-color: transparent;
  background: var(--primary);
  color: #fff;
  padding: 0 1.5rem;
}

.ph-action--primary:hover:not(:disabled) {
  background: var(--primary);
  filter: brightness(0.9);
}

.ph-action--danger {
  color: #ef4444;
  border-color: rgb(239 68 68 / 0.4);
}

.ph-action--danger:hover:not(:disabled) {
  background: rgb(239 68 68 / 0.12);
  border-color: rgb(239 68 68 / 0.55);
}

/* 窄屏：藏掉专辑与次数，保留「歌名 + 歌手 + 最近播放 + 时长」这几个必需信息 */
@media (max-width: 1100px) {
  .ph-table {
    --ph-cols: 2rem 2.5rem minmax(0, 4fr) minmax(0, 3fr) 7.5rem 4rem 3rem;
  }
  .ph-col-album,
  .ph-col-count {
    display: none;
  }
}

@media (max-width: 768px) {
  .ph-table {
    --ph-cols: 2.5rem minmax(0, 4fr) 5rem 3rem;
    --ph-gap: 0.75rem;
  }
  .ph-col-index,
  .ph-col-time,
  .ph-col-duration {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ph-row,
  .ph-cover-play,
  .ph-remove,
  .ph-action {
    transition: none;
  }
}
</style>
