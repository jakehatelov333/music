<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addPlaylistSongs,
  createMyPlaylist,
  createPlaylistIntent,
  deleteMyPlaylist,
  getMyPlaylists,
  getOwnedPlaylist,
  removePlaylistSong,
  updateMyPlaylist,
} from '@/api/myPlaylists'
import type { OwnedPlaylist, PlaylistInput } from '@/api/myPlaylists'
import type { PlaylistDetail, Song } from '@/api/interface'
import type { Result } from '@/api/system'
import { UserStore } from '@/stores/modules/user'
import { AudioStore } from '@/stores/modules/audio'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import PlaylistForm from '@/components/Playlist/PlaylistForm.vue'
import SongPicker from '@/components/Playlist/SongPicker.vue'
import PlaylistCoverDialog from '@/components/Playlist/PlaylistCoverDialog.vue'
import Table from '@/components/Table.vue'
import AuthTabs from '@/components/Auth/AuthTabs.vue'
import coverImg from '@/assets/cover.png'

const route = useRoute()
const router = useRouter()
const user = UserStore()
const audio = AudioStore()
const { loadTrack, play } = useAudioPlayer()
const activeRoute = computed(() => route.path.startsWith('/my-playlists'))
const activeId = computed(() =>
  route.params.id ? Number(route.params.id) : null
)
const account = computed(() =>
  user.isLoggedIn ? `${user.userInfo?.userId}:${user.userInfo?.token}` : ''
)
const playlists = ref<OwnedPlaylist[]>([])
const detail = ref<PlaylistDetail | null>(null)
const page = ref(1)
const total = ref(0)
const keyword = ref('')
const loading = ref(false)
const saving = ref(false)
const playing = ref(false)
const loadError = ref('')
const actionError = ref('')
const formError = ref('')
const pickerError = ref('')
const formOpen = ref(false)
const pickerOpen = ref(false)
const coverOpen = ref(false)
const authOpen = ref(false)
const editing = ref<PlaylistInput>()
const createIntent = createPlaylistIntent()
const songPage = ref(1)
const shownSongs = computed(
  () =>
    detail.value?.songs.slice((songPage.value - 1) * 20, songPage.value * 20) ??
    []
)
const canEdit = computed(
  () =>
    detail.value?.editable === true &&
    detail.value.ownerUserId === user.userInfo?.userId
)
const existingIds = computed(
  () => detail.value?.songs.map((song) => song.songId) ?? []
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
  if (result.code !== 0 || result.data === undefined || result.data === null)
    throw new Error(result.message || '操作失败，请重试')
  return result.data
}
function applyDetail(value: PlaylistDetail) {
  if (!value.editable || value.ownerUserId !== user.userInfo?.userId)
    throw new Error('只能管理自己创建的歌单')
  detail.value = value
  songPage.value = Math.min(
    songPage.value,
    Math.max(1, Math.ceil(value.songs.length / 20))
  )
}

async function load() {
  if (!account.value || !activeRoute.value) return
  const current = ++request
  const currentEpoch = epoch
  loading.value = true
  loadError.value = ''
  try {
    if (activeId.value !== null) {
      if (!Number.isSafeInteger(activeId.value) || activeId.value <= 0)
        throw new Error('歌单地址无效')
      const res = await getOwnedPlaylist(activeId.value, controller.signal)
      if (current !== request || currentEpoch !== epoch) return
      applyDetail(requireData(res))
    } else {
      const res = await getMyPlaylists(
        page.value,
        keyword.value.trim(),
        controller.signal
      )
      if (current !== request || currentEpoch !== epoch) return
      const data = requireData(res)
      playlists.value = data.items
      total.value = data.total
    }
  } catch (error) {
    if (current !== request || currentEpoch !== epoch) return
    loadError.value = message(error)
    playlists.value = []
    detail.value = null
  } finally {
    if (current === request && currentEpoch === epoch) loading.value = false
  }
}

function openCreate() {
  editing.value = undefined
  formError.value = ''
  formOpen.value = true
}
function openEdit() {
  if (!detail.value || !canEdit.value) return
  editing.value = {
    title: detail.value.title,
    introduction: detail.value.introduction || '',
    style: detail.value.style || '',
    isPublic: detail.value.isPublic === true,
  }
  formError.value = ''
  formOpen.value = true
}
async function save(input: PlaylistInput) {
  if (saving.value || !account.value) return
  const currentEpoch = epoch
  const id = editing.value ? activeId.value : null
  if (editing.value && (!id || !canEdit.value)) return
  saving.value = true
  formError.value = ''
  try {
    const res = id
      ? await updateMyPlaylist(id, input, controller.signal)
      : await createMyPlaylist(
          input,
          createIntent.keyFor(input),
          controller.signal
        )
    if (currentEpoch !== epoch) return
    const result = requireData(res)
    formOpen.value = false
    if (id) {
      applyDetail(result)
      ElMessage.success('歌单已更新')
    } else {
      createIntent.reset()
      ElMessage.success('歌单已创建，可以开始添加歌曲')
      await router.push(`/my-playlists/${result.playlistId}`)
    }
  } catch (error) {
    if (currentEpoch === epoch) formError.value = message(error)
  } finally {
    if (currentEpoch === epoch) saving.value = false
  }
}
function openPicker() {
  pickerError.value = ''
  pickerOpen.value = true
}
function openCover() {
  if (!detail.value || !canEdit.value) return
  coverOpen.value = true
}
/** 封面更新接口返回最新详情，直接复用同一个应用逻辑，避免再发一次请求 */
function onCoverUpdated(value: PlaylistDetail) {
  applyDetail(value)
  ElMessage.success('封面已更新')
}
async function addSongs(songIds: number[]) {
  if (!activeId.value || !canEdit.value || saving.value) return
  const currentEpoch = epoch
  saving.value = true
  pickerError.value = ''
  try {
    const res = await addPlaylistSongs(
      activeId.value,
      [...songIds],
      controller.signal
    )
    if (currentEpoch !== epoch) return
    applyDetail(requireData(res))
    pickerOpen.value = false
    ElMessage.success('歌曲已添加，已存在的歌曲不会重复添加')
  } catch (error) {
    if (currentEpoch === epoch) pickerError.value = message(error)
  } finally {
    if (currentEpoch === epoch) saving.value = false
  }
}
async function removeSong(song: Song) {
  if (!activeId.value || !canEdit.value || saving.value) return
  const currentEpoch = epoch
  const id = activeId.value
  try {
    await ElMessageBox.confirm(
      `将《${song.songName}》移出这份歌单？`,
      '移除歌曲',
      { confirmButtonText: '移除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  if (currentEpoch !== epoch || saving.value) return
  saving.value = true
  actionError.value = ''
  try {
    const res = await removePlaylistSong(id, song.songId, controller.signal)
    if (currentEpoch !== epoch) return
    applyDetail(requireData(res))
    ElMessage.success('歌曲已移出歌单')
  } catch (error) {
    if (currentEpoch === epoch) actionError.value = message(error)
  } finally {
    if (currentEpoch === epoch) saving.value = false
  }
}
async function removePlaylist() {
  if (!activeId.value || !detail.value || !canEdit.value || saving.value) return
  const currentEpoch = epoch
  const id = activeId.value
  try {
    await ElMessageBox.confirm(
      `删除「${detail.value.title}」？删除后无法恢复，歌曲本身会保留。`,
      '删除歌单',
      {
        confirmButtonText: '删除歌单',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    return
  }
  if (currentEpoch !== epoch || saving.value) return
  saving.value = true
  actionError.value = ''
  try {
    const res = await deleteMyPlaylist(id, controller.signal)
    if (currentEpoch !== epoch) return
    if (res.code !== 0) throw new Error(res.message || '删除失败，请重试')
    ElMessage.success('歌单已删除')
    await router.push('/my-playlists')
  } catch (error) {
    if (currentEpoch === epoch) actionError.value = message(error)
  } finally {
    if (currentEpoch === epoch) saving.value = false
  }
}
async function playSongs(startId?: number) {
  const songs = detail.value?.songs ?? []
  if (!songs.length || playing.value) return
  const currentEpoch = epoch
  playing.value = true
  audio.setAudioStore(
    'trackList',
    songs.map((song) => ({
      id: String(song.songId),
      title: song.songName,
      artist: song.artistName,
      album: song.album,
      cover: song.coverUrl || coverImg,
      url: song.audioUrl,
      duration: parseFloat(song.duration) * 1000,
      likeStatus: song.likeStatus,
    }))
  )
  audio.setAudioStore(
    'currentSongIndex',
    Math.max(
      0,
      songs.findIndex((song) => song.songId === startId)
    )
  )
  try {
    await loadTrack()
    if (currentEpoch === epoch) play()
  } catch {
    if (currentEpoch === epoch) ElMessage.error('暂时无法播放这首歌曲')
  } finally {
    if (currentEpoch === epoch) playing.value = false
  }
}
function search() {
  page.value = 1
  load()
}

watch(
  [account, () => route.path],
  () => {
    epoch++
    request++
    controller.abort()
    controller = new AbortController()
    playlists.value = []
    detail.value = null
    total.value = 0
    page.value = 1
    songPage.value = 1
    loading.value = false
    saving.value = false
    playing.value = false
    formOpen.value = false
    pickerOpen.value = false
    coverOpen.value = false
    loadError.value = ''
    actionError.value = ''
    createIntent.reset()
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
    class="personal-playlists h-full min-h-0 overflow-y-auto bg-background p-4 md:p-6"
  >
    <template v-if="!user.isLoggedIn">
      <h1 class="text-2xl font-semibold mb-2">我的歌单</h1>
      <el-empty description="登录后，创建和整理属于你的歌单">
        <el-button type="primary" @click="authOpen = true">登录</el-button>
      </el-empty>
    </template>
    <template v-else-if="!activeId">
      <header class="flex items-start justify-between gap-4 mb-6">
        <div>
          <h1 class="text-2xl font-semibold">我的歌单</h1>
          <p class="text-sm text-muted-foreground mt-2">
            为不同的时刻，收藏一份自己的声音。
          </p>
        </div>
        <el-button type="primary" @click="openCreate">创建歌单</el-button>
      </header>
      <form class="flex gap-3 max-w-lg mb-6" @submit.prevent="search">
        <el-input
          v-model="keyword"
          aria-label="搜索我的歌单"
          maxlength="200"
          placeholder="搜索我的歌单"
          clearable
        />
        <el-button native-type="submit" :loading="loading">搜索</el-button>
      </form>
      <p v-if="loading" class="py-12 text-center" role="status">
        正在加载歌单…
      </p>
      <div v-else-if="loadError" class="py-10 text-center" role="alert">
        <p class="mb-4">{{ loadError }}</p>
        <el-button @click="load">重新加载</el-button>
      </div>
      <el-empty
        v-else-if="!playlists.length"
        :description="
          keyword ? '没有找到匹配的歌单' : '还没有歌单，从一首喜欢的歌开始吧'
        "
      >
        <el-button v-if="!keyword" type="primary" @click="openCreate"
          >创建第一份歌单</el-button
        >
      </el-empty>
      <div
        v-else
        class="grid grid-cols-1 min-[480px]:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5"
      >
        <RouterLink
          v-for="item in playlists"
          :key="item.playlistId"
          :to="`/my-playlists/${item.playlistId}`"
          class="playlist-card rounded-xl overflow-hidden border bg-card text-card-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <div class="relative aspect-square overflow-hidden bg-muted">
            <img
              :src="item.coverUrl || coverImg"
              alt=""
              loading="lazy"
              class="w-full h-full object-cover"
            />
            <span
              class="absolute top-3 left-3 rounded-full bg-background px-3 py-1 text-xs font-medium"
              >{{ item.isPublic ? '公开' : '私有' }}</span
            >
          </div>
          <div class="p-4">
            <h2 class="font-semibold text-lg truncate" :title="item.title">
              {{ item.title }}
            </h2>
            <p class="text-sm text-muted-foreground mt-1">
              {{ item.songCount }} 首歌曲<span v-if="item.style">
                · {{ item.style }}</span
              >
            </p>
            <p class="text-sm text-muted-foreground line-clamp-2 mt-3">
              {{ item.introduction || '记录这一刻的音乐。' }}
            </p>
          </div>
        </RouterLink>
      </div>
      <el-pagination
        v-if="total > 12"
        v-model:current-page="page"
        :page-size="12"
        :total="total"
        layout="prev, pager, next"
        :pager-count="5"
        :disabled="loading"
        class="mt-6 justify-center"
        @current-change="load"
      />
    </template>
    <template v-else>
      <RouterLink
        to="/my-playlists"
        class="inline-flex items-center min-h-11 mb-4 text-sm underline underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring"
        >返回我的歌单</RouterLink
      >
      <p v-if="loading" class="py-12 text-center" role="status">
        正在加载歌单…
      </p>
      <div v-else-if="loadError" class="py-10 text-center" role="alert">
        <p class="mb-4">{{ loadError }}</p>
        <el-button @click="load">重新加载</el-button>
      </div>
      <template v-else-if="detail">
        <header class="mb-8 flex flex-col gap-5 sm:flex-row sm:gap-6">
          <div class="relative w-40 h-40 md:w-48 md:h-48 shrink-0 group">
            <img
              :src="detail.coverUrl || coverImg"
              alt=""
              class="w-full h-full object-cover rounded-xl bg-muted"
            />
            <button
              v-if="canEdit"
              type="button"
              class="absolute inset-x-0 bottom-0 rounded-b-xl bg-black/60 text-white text-sm py-2 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity"
              @click="openCover"
            >
              修改封面
            </button>
          </div>
          <div class="min-w-0 flex-1">
            <div class="text-sm text-muted-foreground mb-2">
              我的歌单 · {{ detail.isPublic ? '公开' : '私有，仅自己可见' }}
              <span v-if="detail.coverIsCustom"> · 自定义封面</span>
              <span v-else> · 默认封面</span>
            </div>
            <h1 class="text-2xl md:text-3xl font-semibold break-words">
              {{ detail.title }}
            </h1>
            <p class="text-sm text-muted-foreground mt-3">
              {{ detail.songs.length }} 首歌曲<span v-if="detail.style">
                · {{ detail.style }}</span
              >
            </p>
            <p
              v-if="detail.introduction"
              class="text-sm leading-relaxed mt-3 whitespace-pre-wrap break-words"
            >
              {{ detail.introduction }}
            </p>
            <div class="flex flex-wrap items-center gap-3 mt-6">
              <button
                type="button"
                class="pa-action pa-action--primary"
                :disabled="!detail.songs.length || playing"
                @click="playSongs()"
              >
                <icon-solar:play-line-duotone class="text-lg" />
                {{ playing ? '正在播放…' : '播放全部' }}
              </button>
              <button
                v-if="canEdit"
                type="button"
                class="pa-action"
                :disabled="saving"
                @click="openEdit"
              >
                <icon-feather:edit class="text-base" />
                编辑信息
              </button>
              <button
                v-if="canEdit"
                type="button"
                class="pa-action pa-action--danger ml-auto"
                :disabled="saving"
                @click="removePlaylist"
              >
                <icon-tabler:trash class="text-base" />
                删除歌单
              </button>
            </div>
          </div>
        </header>
        <div class="mb-3 flex flex-wrap items-center justify-between gap-3 border-b pb-4">
          <h2 class="text-lg font-semibold">歌单歌曲</h2>
          <button
            v-if="canEdit"
            type="button"
            class="pa-action pa-action--primary pa-action--sm"
            :disabled="saving"
            @click="openPicker"
          >
            <icon-tabler:plus class="text-base" />
            添加歌曲
          </button>
        </div>
        <p v-if="actionError" role="alert" class="text-destructive mb-4">
          {{ actionError }}
        </p>
        <el-empty
          v-if="!detail.songs.length"
          description="歌单还是空的，搜索并添加喜欢的歌曲吧"
        />
        <!--
          与曲库共用同一个表格组件：variant="owned" 只把末尾两列换成「时长 / 移出」，
          标题/歌手/专辑列宽与行样式完全一致，所以这里的展示与曲库是同一张表。
          play-queue 传完整歌单 —— 分页只影响显示，点任意一行都把整张歌单入队。
        -->
        <Table
          v-else
          :data="shownSongs"
          :play-queue="detail.songs"
          variant="owned"
          page-scroll
          @remove="removeSong"
        />
        <el-pagination
          v-if="detail.songs.length > 20"
          v-model:current-page="songPage"
          :page-size="20"
          :total="detail.songs.length"
          layout="prev, pager, next"
          :pager-count="5"
          class="mt-6 justify-center"
        />
      </template>
    </template>
    <PlaylistForm
      v-model="formOpen"
      :initial="editing"
      :saving="saving"
      :error="formError"
      @save="save"
    />
    <SongPicker
      v-if="user.isLoggedIn"
      :key="account"
      v-model="pickerOpen"
      :existing-ids="existingIds"
      :saving="saving"
      :error="pickerError"
      @add="addSongs"
    />
    <PlaylistCoverDialog
      v-if="detail && canEdit"
      v-model="coverOpen"
      :playlist-id="detail.playlistId"
      :title="detail.title"
      :cover-url="detail.coverUrl"
      :cover-is-custom="detail.coverIsCustom === true"
      @updated="onCoverUpdated"
    />
    <AuthTabs v-model="authOpen" />
  </section>
</template>

<style scoped>
.personal-playlists :deep(.el-button) {
  min-height: 44px;
  margin-left: 0;
}
.personal-playlists :deep(.el-input__wrapper) {
  min-height: 44px;
}

/*
 * 歌单操作按钮（播放全部 / 编辑信息 / 删除歌单 / 添加歌曲）。
 *
 * 用原生 button + 设计变量而不是 el-button：这里需要「主操作实心、次操作描边、
 * 危险操作只留红色文字」三级层次，el-button 的默认层级表达不出来。
 * 尺寸与「公开歌单详情」页的播放全部按钮一致（h-10 / rounded-lg / 图标 + 文字）。
 */
.pa-action {
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
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    color 0.18s ease,
    filter 0.18s ease;
}
.pa-action:hover:not(:disabled) {
  background: hsl(var(--muted));
}
.pa-action:focus-visible {
  outline: 2px solid hsl(var(--ring));
  outline-offset: 2px;
}
.pa-action:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* 主操作：实心主色。悬停用 filter 压暗，换主色时不必再改一处硬编码色值 */
.pa-action--primary {
  border-color: transparent;
  background: var(--primary);
  color: #fff;
  padding: 0 1.5rem;
}
.pa-action--primary:hover:not(:disabled) {
  background: var(--primary);
  filter: brightness(0.9);
}

/* 危险操作：平时只有红字，悬停才铺一层淡红底，避免与主操作抢注意力。
   红色刻意不取 --destructive：那个变量在暗色主题里是「深红背景色」（0 62.8% 30.6%），
   当文字色用在深色底上几乎看不见。这里用固定的红 500，两套主题下都够亮。 */
.pa-action--danger {
  color: #ef4444;
  border-color: rgb(239 68 68 / 0.4);
}
.pa-action--danger:hover:not(:disabled) {
  background: rgb(239 68 68 / 0.12);
  border-color: rgb(239 68 68 / 0.55);
}

/* 区块内的次级主操作（添加歌曲）用矮一号的尺寸，与页头 CTA 拉开层次 */
.pa-action--sm {
  height: 2.25rem;
  padding: 0 1rem;
}

@media (prefers-reduced-motion: reduce) {
  .playlist-card,
  .pa-action {
    transition: none;
  }
}
</style>
