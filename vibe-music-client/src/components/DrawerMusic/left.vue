<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Icon } from '@iconify/vue'
import type { SongDetail } from '@/api/interface'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import { AudioStore } from '@/stores/modules/audio'
import { UserStore } from '@/stores/modules/user'
import { collectSong, cancelCollectSong } from '@/api/system'
import { formatTime } from '@/utils'
import { copyText, downloadMedia } from '@/utils/download'
import Recently from '@/layout/components/footer/components/recently.vue'

const props = withDefaults(
  defineProps<{
    songDetail: SongDetail | null
    loading?: boolean
  }>(),
  {
    loading: false,
  }
)

const emit = defineEmits<{
  likeChange: [status: number]
}>()

const audioStore = AudioStore()
const userStore = UserStore()
const {
  currentTrack,
  isPlaying,
  currentTime,
  duration,
  nextTrack,
  prevTrack,
  togglePlayPause,
  seek,
  setPlayMode,
} = useAudioPlayer()

const likeStatus = ref(0)
const playModes = {
  order: {
    icon: 'ri:order-play-line',
    next: 'shuffle',
    tooltip: '顺序播放',
  },
  shuffle: {
    icon: 'ri:shuffle-line',
    next: 'loop',
    tooltip: '随机播放',
  },
  loop: {
    icon: 'ri:repeat-2-line',
    next: 'single',
    tooltip: '列表循环',
  },
  single: {
    icon: 'ri:repeat-one-line',
    next: 'order',
    tooltip: '单曲循环',
  },
} as const

const currentMode = ref<keyof typeof playModes>('order')
const coverUrl = computed(
  () => props.songDetail?.coverUrl || currentTrack.value.cover
)
const title = computed(
  () => props.songDetail?.songName || currentTrack.value.title
)
const artist = computed(
  () => props.songDetail?.artistName || currentTrack.value.artist
)
const album = computed(() => props.songDetail?.album || currentTrack.value.album)
const releaseYear = computed(() => {
  const releaseTime = props.songDetail?.releaseTime
  return releaseTime ? new Date(releaseTime).getFullYear() : null
})

watch(
  () => [props.songDetail?.likeStatus, currentTrack.value.id],
  () => {
    likeStatus.value =
      Number(props.songDetail?.likeStatus ?? currentTrack.value.likeStatus) || 0
  },
  { immediate: true }
)

const togglePlayMode = () => {
  const nextMode = playModes[currentMode.value].next
  currentMode.value = nextMode
  setPlayMode(nextMode)
}

const handleLike = async () => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }

  const songId = Number(props.songDetail?.songId || currentTrack.value.id)
  if (!songId) return

  try {
    const nextStatus = likeStatus.value === 0 ? 1 : 0
    const response =
      nextStatus === 1
        ? await collectSong(songId)
        : await cancelCollectSong(songId)

    if (response.code !== 0) {
      ElMessage.error(response.message || '操作失败')
      return
    }

    likeStatus.value = nextStatus
    audioStore.trackList.forEach((track) => {
      if (Number(track.id) === songId) {
        track.likeStatus = nextStatus
      }
    })
    emit('likeChange', nextStatus)
    ElMessage.success(nextStatus === 1 ? '已添加到我的喜欢' : '已取消喜欢')
  } catch (error) {
    ElMessage.error('操作失败')
  }
}

const handleDownload = async () => {
  const audioUrl = props.songDetail?.audioUrl || currentTrack.value.url
  if (!audioUrl) return

  try {
    await downloadMedia(audioUrl, `${title.value} - ${artist.value}.mp3`)
    ElMessage.success('已开始下载')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '下载失败')
  }
}

const handleShare = async () => {
  const url = window.location.href
  const text = `正在听 ${title.value} - ${artist.value}`
  const shareData = {
    title: `${title.value} - ${artist.value}`,
    text,
    url,
  }

  try {
    if (navigator.share) {
      await navigator.share(shareData)
      ElMessage.success('已打开分享')
      return
    }
    await copyText(`${text}\n${url}`)
    ElMessage.success('分享链接已复制')
  } catch (error) {
    if ((error as DOMException)?.name === 'AbortError') return
    try {
      await copyText(`${text}\n${url}`)
      ElMessage.success('系统分享不可用，链接已复制')
    } catch {
      ElMessage.error('分享失败，请复制浏览器地址')
    }
  }
}
</script>

<template>
  <div class="player-card-content flex h-full min-h-0 flex-col">
    <div class="player-card-header flex shrink-0 items-center justify-between">
      <div>
        <p class="text-[11px] font-medium uppercase tracking-[0.24em] text-muted-foreground">
          Playing from queue
        </p>
        <p class="mt-1 text-sm text-foreground/80">
          {{ audioStore.currentSongIndex + 1 }} / {{ audioStore.trackList.length || 1 }}
        </p>
      </div>
      <span
        class="rounded-full border border-white/60 bg-white/45 px-3 py-1 text-xs text-muted-foreground backdrop-blur-xl dark:border-white/10 dark:bg-white/5"
      >
        {{ isPlaying ? '播放中' : '已暂停' }}
      </span>
    </div>

    <div class="player-visual flex min-h-0 flex-1 flex-col items-center justify-center">
      <div class="player-cover" :class="{ 'is-playing': isPlaying }">
        <div class="player-cover__halo" />
        <img :src="coverUrl" :alt="title" class="player-cover__image" />
        <div class="player-cover__shine" />
      </div>

      <div class="player-track w-full max-w-lg text-center">
        <el-skeleton :loading="loading && !songDetail" animated>
          <template #template>
            <el-skeleton-item variant="h1" class="!h-8 !w-2/3 mx-auto" />
            <el-skeleton-item variant="text" class="!h-5 !w-1/3 mx-auto mt-4" />
          </template>
          <template #default>
            <h2 class="player-title text-balance font-semibold tracking-tight text-foreground">
              {{ title }}
            </h2>
            <p class="player-artist text-muted-foreground">{{ artist }}</p>
            <div class="player-metadata flex flex-wrap items-center justify-center">
              <span v-if="album" class="metadata-chip">{{ album }}</span>
              <span v-if="releaseYear" class="metadata-chip">{{ releaseYear }}</span>
            </div>
          </template>
        </el-skeleton>

        <div class="player-actions flex items-center justify-center">
          <button
            type="button"
            class="action-button"
            :class="{ 'text-rose-500': likeStatus === 1 }"
            title="喜欢"
            @click="handleLike"
          >
            <icon-mdi:cards-heart v-if="likeStatus === 1" />
            <icon-mdi:cards-heart-outline v-else />
          </button>
          <button type="button" class="action-button" title="下载" @click="handleDownload">
            <icon-material-symbols:download-rounded />
          </button>
          <button type="button" class="action-button" title="分享" @click="handleShare">
            <icon-ri:share-line />
          </button>
          <Recently />
        </div>
      </div>
    </div>

    <div class="player-controls mx-auto w-full max-w-2xl">
      <div class="player-progress flex items-center">
        <span class="w-10 text-right text-xs tabular-nums text-muted-foreground">
          {{ formatTime(currentTime) }}
        </span>
        <el-slider
          v-model="currentTime"
          class="flex-1"
          :show-tooltip="false"
          :max="duration"
          size="small"
          @change="seek"
        />
        <span class="w-10 text-xs tabular-nums text-muted-foreground">
          {{ formatTime(duration) }}
        </span>
      </div>

      <div class="player-transport flex items-center justify-center">
        <el-tooltip :content="playModes[currentMode].tooltip" placement="top" effect="dark">
          <button type="button" class="control-button" @click="togglePlayMode">
            <Icon :icon="playModes[currentMode].icon" />
          </button>
        </el-tooltip>

        <button type="button" class="control-button" title="上一首" @click="prevTrack">
          <icon-solar:skip-previous-bold />
        </button>

        <button
          type="button"
          class="play-button"
          :class="{ 'is-playing': isPlaying }"
          title="播放/暂停"
          @click="togglePlayPause"
        >
          <Icon
            :icon="isPlaying ? 'ic:round-pause' : 'material-symbols:play-arrow-rounded'"
          />
        </button>

        <button type="button" class="control-button" title="下一首" @click="nextTrack">
          <icon-solar:skip-previous-bold class="scale-x-[-1]" />
        </button>

        <Recently />


      </div>
    </div>
  </div>
</template>

<style scoped>
.metadata-chip {
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.42);
  padding: clamp(3px, 0.45vh, 5px) clamp(8px, 0.8vw, 11px);
  color: hsl(var(--muted-foreground));
  font-size: clamp(10px, 1.1vh, 12px);
  backdrop-filter: blur(12px);
}

.player-card-content {
  padding: clamp(0.75rem, 2.3vh, 2.5rem) clamp(1rem, 2.3vw, 2.5rem);
  overflow: hidden;
}

.player-card-header {
  margin-bottom: clamp(0.5rem, 1.4vh, 1.25rem);
}

.player-visual {
  container-type: size;
  overflow: hidden;
}

.dark .metadata-chip {
  border-color: rgba(255, 255, 255, 0.08);
  background: rgba(15, 23, 42, 0.35);
}

.player-cover {
  position: relative;
  width: clamp(120px, 34vh, 370px);
  width: min(100%, 46cqh, 370px);
  aspect-ratio: 1;
  border-radius: 28px;
  isolation: isolate;
}

.player-title {
  font-size: clamp(1.25rem, calc(2.2vh + 0.45vw), 1.875rem);
  line-height: 1.15;
}

.player-artist {
  margin-top: clamp(0.25rem, 0.55vh, 0.5rem);
  font-size: clamp(0.875rem, 1.6vh, 1.125rem);
  line-height: 1.35;
}

.player-track {
  margin-top: clamp(0.5rem, 1.8vh, 1.75rem);
}

.player-metadata {
  margin-top: clamp(0.5rem, 1.3vh, 1rem);
  gap: clamp(0.25rem, 0.6vw, 0.5rem);
}

.player-actions {
  margin-top: clamp(0.5rem, 1.3vh, 1.25rem);
  gap: clamp(0.35rem, 0.8vw, 0.5rem);
}

.player-controls {
  margin-top: clamp(0.5rem, 1.7vh, 1.5rem);
  flex: 0 0 auto;
}

.player-progress {
  gap: clamp(0.375rem, 0.8vw, 0.75rem);
}

.player-transport {
  margin-top: clamp(0.4rem, 1.2vh, 1rem);
  gap: clamp(0.75rem, 2.2vw, 2rem);
}

.player-cover__image {
  position: relative;
  z-index: 2;
  width: 100%;
  height: 100%;
  border-radius: 26px;
  object-fit: cover;
  box-shadow:
    0 28px 60px rgba(15, 23, 42, 0.22),
    0 8px 20px rgba(15, 23, 42, 0.14);
  transition: transform 800ms cubic-bezier(0.22, 1, 0.36, 1);
}

.player-cover.is-playing .player-cover__image {
  animation: cover-breathe 5.8s ease-in-out infinite;
}

.player-cover__halo {
  position: absolute;
  inset: 7%;
  z-index: 0;
  border-radius: 30px;
  background: linear-gradient(
    135deg,
    var(--cover-primary),
    var(--cover-secondary)
  );
  filter: blur(34px);
  opacity: 0.38;
}

.player-cover__shine {
  position: absolute;
  inset: 0;
  z-index: 3;
  overflow: hidden;
  border-radius: 26px;
  background: linear-gradient(
    120deg,
    rgba(255, 255, 255, 0.2),
    transparent 30%,
    transparent 70%,
    rgba(255, 255, 255, 0.12)
  );
  pointer-events: none;
}

.action-button {
  display: inline-flex;
  width: clamp(32px, 4.2vh, 42px);
  height: clamp(32px, 4.2vh, 42px);
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.58);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.48);
  color: hsl(var(--foreground) / 0.7);
  font-size: clamp(16px, 2.1vh, 20px);
  transition:
    transform 180ms ease,
    color 180ms ease,
    background-color 180ms ease;
  backdrop-filter: blur(16px);
}

.action-button:hover,
.control-button:hover {
  color: var(--cover-primary);
  transform: translateY(-2px);
}

.dark .action-button {
  border-color: rgba(255, 255, 255, 0.08);
  background: rgba(15, 23, 42, 0.38);
}

.control-button {
  display: inline-flex;
  width: clamp(32px, 4vh, 40px);
  height: clamp(32px, 4vh, 40px);
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: hsl(var(--foreground) / 0.74);
  font-size: clamp(18px, 2.3vh, 23px);
  transition:
    transform 180ms ease,
    color 180ms ease,
    background-color 180ms ease;
}

.control-button:hover {
  background: rgba(255, 255, 255, 0.48);
}

.dark .control-button:hover {
  background: rgba(255, 255, 255, 0.07);
}

.play-button {
  display: inline-flex;
  width: clamp(48px, 6.4vh, 64px);
  height: clamp(48px, 6.4vh, 64px);
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: var(--cover-primary);
  color: white;
  font-size: clamp(28px, 3.6vh, 36px);
  box-shadow: 0 16px 34px color-mix(in srgb, var(--cover-primary) 32%, transparent);
  transition:
    transform 220ms cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 220ms ease;
}

@media (max-height: 820px) {
  .player-card-content {
    padding-block: 0.6rem;
  }

  .player-card-header {
    margin-bottom: 0.5rem;
  }

  .player-track,
  .player-controls,
  .player-actions {
    margin-top: 0.5rem;
  }

  .player-metadata {
    margin-top: 0.375rem;
  }

  .player-transport {
    margin-top: 0.4rem;
  }
}

.play-button:hover {
  transform: translateY(-3px) scale(1.035);
  box-shadow: 0 20px 40px color-mix(in srgb, var(--cover-primary) 42%, transparent);
}

.play-button:active {
  transform: scale(0.96);
}

@keyframes cover-breathe {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.018);
  }
}

@media (prefers-reduced-motion: reduce) {
  .player-cover.is-playing .player-cover__image {
    animation: none;
  }
}
</style>
