<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getRecentPlays } from '@/api/playHistory'
import type { PlayHistoryItem } from '@/api/playHistory'
import { UserStore } from '@/stores/modules/user'
import { AudioStore } from '@/stores/modules/audio'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import { formatMillisecondsToTime } from '@/utils'
import defaultAlbum from '@/assets/default_album.jpg'

/**
 * 播放器右侧的「刚刚听过」浮层。
 *
 * <p>与 `recently.vue`（播放队列）刻意分开：那个是**本次会话的队列**（存在 localStorage），
 * 这个是**服务端的历史**（跨设备、跨会话）。两者混在一个浮层里会让用户分不清
 * 「我待会要听的」和「我上次听过的」。
 *
 * <p>只在打开浮层时才请求（懒加载）：每次切歌都拉一次历史是纯浪费。
 */

const router = useRouter()
const user = UserStore()
const audio = AudioStore()
const { loadTrack, play } = useAudioPlayer()

const visible = ref(false)
const items = ref<PlayHistoryItem[]>([])
const loading = ref(false)
const failed = ref(false)
const playingId = ref<number | null>(null)
let request = 0

const loggedIn = computed(() => user.isLoggedIn)
const isEmpty = computed(
  () => !loading.value && !failed.value && items.value.length === 0
)

async function load() {
  if (!loggedIn.value) return
  const current = ++request
  loading.value = true
  failed.value = false
  try {
    const res = await getRecentPlays(10)
    if (current !== request) return
    if (res.code === 0 && Array.isArray(res.data)) {
      items.value = res.data
    } else {
      failed.value = true
      items.value = []
    }
  } catch {
    if (current === request) {
      failed.value = true
      items.value = []
    }
  } finally {
    if (current === request) loading.value = false
  }
}

/** 浮层打开时刷新一次；关闭时不请求。 */
watch(visible, (open) => {
  if (open) load()
})

/** 换号 / 退出后必须清空：不能让下一个人看到上一个人的历史。 */
watch(
  () =>
    loggedIn.value ? `${user.userInfo?.userId}:${user.userInfo?.token}` : '',
  () => {
    request++
    items.value = []
    failed.value = false
    loading.value = false
    if (visible.value) load()
  }
)

async function playSong(item: PlayHistoryItem) {
  if (!item.audioUrl || playingId.value !== null) return
  playingId.value = item.songId
  try {
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
    play()
    visible.value = false
  } catch {
    // 播放失败保持浮层打开，用户可以换一首
  } finally {
    playingId.value = null
  }
}

function openFullPage() {
  visible.value = false
  router.push('/play-history')
}
</script>

<template>
  <el-popover
    v-model:visible="visible"
    :width="420"
    trigger="click"
    placement="top-end"
    popper-class="!rounded-lg !p-0"
  >
    <template #reference>
      <button
        type="button"
        class="p-2 rounded-full hover:bg-hoverMenuBg transition w-9 h-9"
        title="刚刚听过"
        aria-label="刚刚听过"
      >
        <icon-ri:history-line class="w-full h-full" />
      </button>
    </template>

    <div class="bg-popoverBg rounded-lg p-2">
      <div class="flex items-center justify-between mb-2 px-1">
        <span class="text-sm text-gray-400">刚刚听过</span>
        <button
          v-if="loggedIn"
          type="button"
          class="text-xs text-muted-foreground hover:text-primary transition"
          @click="openFullPage"
        >
          查看全部
        </button>
      </div>

      <div
        v-if="!loggedIn"
        class="py-8 text-center text-sm text-muted-foreground"
      >
        登录后可以查看最近播放
      </div>

      <div
        v-else-if="loading"
        class="py-8 text-center text-sm text-muted-foreground"
        role="status"
      >
        正在加载…
      </div>

      <div v-else-if="failed" class="py-6 text-center" role="alert">
        <p class="text-sm text-muted-foreground mb-3">加载失败</p>
        <el-button size="small" @click="load">重试</el-button>
      </div>

      <div
        v-else-if="isEmpty"
        class="py-8 text-center text-sm text-muted-foreground"
      >
        还没有播放记录
      </div>

      <el-scrollbar v-else class="max-h-96">
        <button
          v-for="item in items"
          :key="item.historyId"
          type="button"
          class="ph-mini-row group flex w-full items-center gap-2 p-2 my-1 rounded-lg transition hover:bg-gray-300 dark:hover:bg-[#414243] text-left"
          :class="{ 'opacity-60': playingId === item.songId }"
          @click="playSong(item)"
        >
          <span
            class="w-10 h-10 rounded-lg overflow-hidden relative flex-shrink-0 bg-muted"
          >
            <img
              :src="item.coverUrl || defaultAlbum"
              :alt="item.songName"
              loading="lazy"
              class="w-full h-full object-cover"
            />
            <span
              class="absolute inset-0 flex items-center justify-center text-white opacity-0 transition-opacity duration-300 group-hover:opacity-100 group-hover:bg-black/50 rounded-md"
            >
              <icon-tabler:player-play-filled class="text-lg" />
            </span>
          </span>
          <span class="flex-1 min-w-0">
            <span class="block text-sm truncate">{{ item.songName }}</span>
            <span class="block text-xs text-gray-400 truncate">{{
              item.artistName
            }}</span>
          </span>
          <span class="text-xs text-gray-400 whitespace-nowrap">
            {{ formatMillisecondsToTime(Number(item.duration) * 1000) }}
          </span>
        </button>
      </el-scrollbar>
    </div>
  </el-popover>
</template>
