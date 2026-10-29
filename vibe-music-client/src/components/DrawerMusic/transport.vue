<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Icon } from '@iconify/vue'
import type { SongDetail } from '@/api/interface'
import type { PlayMode } from '@/hooks/interface'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import { AudioStore } from '@/stores/modules/audio'
import { UserStore } from '@/stores/modules/user'
import { collectSong, cancelCollectSong } from '@/api/system'
import { formatTime } from '@/utils'
import { copyText, downloadMedia } from '@/utils/download'
import Recently from '@/layout/components/footer/components/recently.vue'

defineOptions({ name: 'PlayerTransport' })

const props = defineProps<{
  songDetail: SongDetail | null
}>()

const emit = defineEmits<{
  openComments: []
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
  volume,
  setVolume,
  playMode,
  setPlayMode,
} = useAudioPlayer()

const playModes: Record<
  PlayMode,
  { icon: string; next: PlayMode; label: string }
> = {
  order: {
    icon: 'ri:order-play-line',
    next: 'shuffle',
    label: '顺序播放',
  },
  shuffle: {
    icon: 'ri:shuffle-line',
    next: 'loop',
    label: '随机播放',
  },
  loop: {
    icon: 'ri:repeat-2-line',
    next: 'single',
    label: '列表循环',
  },
  single: {
    icon: 'ri:repeat-one-line',
    next: 'order',
    label: '单曲循环',
  },
}

const title = computed(
  () => props.songDetail?.songName || currentTrack.value.title
)
const artist = computed(
  () => props.songDetail?.artistName || currentTrack.value.artist
)
const coverUrl = computed(
  () => props.songDetail?.coverUrl || currentTrack.value.cover
)
const totalDuration = computed(
  () =>
    duration.value ||
    Number(props.songDetail?.duration || currentTrack.value.duration || 0)
)
const progressPercent = computed(() => {
  if (!totalDuration.value) return 0
  return Math.min(
    100,
    Math.max(0, (currentTime.value / totalDuration.value) * 100)
  )
})
const volumePercent = computed(() =>
  Math.min(100, Math.max(0, Number(volume.value || 0)))
)
const likeStatus = ref(0)
const lastAudibleVolume = ref(Number(volume.value || 0) || 50)

watch(
  () => [props.songDetail?.likeStatus, currentTrack.value.id],
  () => {
    likeStatus.value =
      Number(props.songDetail?.likeStatus ?? currentTrack.value.likeStatus) || 0
  },
  { immediate: true }
)

watch(volume, (value) => {
  if (Number(value) > 0) lastAudibleVolume.value = Number(value)
})

const commentCount = computed(() => props.songDetail?.comments.length || 0)
const commentLabel = computed(() => {
  const count = commentCount.value
  if (count >= 10000) {
    return `${(count / 10000).toFixed(1).replace(/\.0$/, '')}万`
  }
  return String(count)
})

const styleVars = computed(() => ({
  '--progress': `${progressPercent.value}%`,
  '--volume': `${volumePercent.value}%`,
}))

const togglePlayMode = () => {
  setPlayMode(playModes[playMode.value].next)
}

const handleSeekInput = (event: Event) => {
  currentTime.value = Number((event.target as HTMLInputElement).value)
}

const commitSeek = () => {
  seek(currentTime.value)
}

const handleVolumeInput = (event: Event) => {
  setVolume(Number((event.target as HTMLInputElement).value))
}

const toggleMute = () => {
  setVolume(volume.value === 0 ? lastAudibleVolume.value || 50 : 0)
}

const handleLike = async () => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }

  const songId = Number(props.songDetail?.songId || currentTrack.value.id)
  if (!songId) return

  try {
    const nextStatus = likeStatus.value === 1 ? 0 : 1
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
      if (Number(track.id) === songId) track.likeStatus = nextStatus
    })
    emit('likeChange', nextStatus)
    ElMessage.success(nextStatus === 1 ? '已添加到我的喜欢' : '已取消喜欢')
  } catch {
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
  <footer class="transport" :style="styleVars">
    <div class="now-mini">
      <div class="now-mini__top">
        <div class="mini-cover">
          <img :src="coverUrl" :alt="`${title} 封面`" />
        </div>
        <div class="mini-copy">
          <p class="mini-title">{{ title }}</p>
          <p class="mini-artist">{{ artist }}</p>
        </div>
      </div>

      <div class="mini-actions">
        <button
          type="button"
          class="mini-btn"
          :class="{ 'is-liked': likeStatus === 1 }"
          :aria-label="likeStatus === 1 ? '取消喜欢' : '喜欢'"
          :aria-pressed="likeStatus === 1"
          @click="handleLike"
        >
          <Icon
            :icon="
              likeStatus === 1 ? 'mdi:cards-heart' : 'mdi:cards-heart-outline'
            "
          />
        </button>

        <button
          type="button"
          class="mini-btn"
          aria-label="打开评论"
          @click="emit('openComments')"
        >
          <icon-ri:chat-3-line />
          <span v-if="commentCount" class="count">{{ commentLabel }}</span>
        </button>
      </div>
    </div>

    <div class="center">
      <div class="transport-row">
        <button
          type="button"
          class="ctrl-btn"
          :title="playModes[playMode].label"
          :aria-label="playModes[playMode].label"
          @click="togglePlayMode"
        >
          <Icon :icon="playModes[playMode].icon" />
        </button>

        <button
          type="button"
          class="ctrl-btn"
          aria-label="上一首"
          @click="prevTrack"
        >
          <icon-solar:skip-previous-bold />
        </button>

        <button
          type="button"
          class="play-btn"
          :aria-label="isPlaying ? '暂停' : '播放'"
          @click="togglePlayPause"
        >
          <Icon
            :icon="
              isPlaying
                ? 'ri:pause-fill'
                : 'material-symbols:play-arrow-rounded'
            "
          />
        </button>

        <button
          type="button"
          class="ctrl-btn"
          aria-label="下一首"
          @click="nextTrack"
        >
          <icon-solar:skip-previous-bold class="scale-x-[-1]" />
        </button>

        <div class="volume-wrap">
          <button
            type="button"
            class="ctrl-btn"
            :aria-label="volume === 0 ? '取消静音' : '静音'"
            @click="toggleMute"
          >
            <Icon
              :icon="volume === 0 ? 'ri:volume-mute-line' : 'ri:volume-up-line'"
            />
          </button>
          <input
            class="volume-range"
            type="range"
            min="0"
            max="100"
            step="1"
            :value="volume"
            aria-label="音量"
            @input="handleVolumeInput"
          />
        </div>
      </div>

      <div class="timeline">
        <span>{{ formatTime(currentTime) }}</span>
        <input
          class="progress-range"
          type="range"
          min="0"
          :max="totalDuration || 0"
          step="1"
          :value="currentTime"
          aria-label="播放进度"
          @input="handleSeekInput"
          @change="commitSeek"
        />
        <span class="time-end">{{ formatTime(totalDuration) }}</span>
      </div>
    </div>

    <div class="tools">
      <Recently immersive />
      <button
        type="button"
        class="tool-btn"
        aria-label="下载"
        title="下载"
        @click="handleDownload"
      >
        <icon-ri:download-2-line />
      </button>
      <button
        type="button"
        class="tool-btn"
        aria-label="分享"
        title="分享"
        @click="handleShare"
      >
        <icon-ri:share-line />
      </button>
    </div>
  </footer>
</template>

<style scoped>
.transport {
  position: relative;
  z-index: 12;
  display: grid;
  grid-template-columns:
    minmax(280px, 1fr) minmax(500px, 620px)
    minmax(280px, 1fr);
  align-items: end;
  gap: 24px;
  padding: 0 38px 20px;
  animation: rise-in 900ms 320ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.transport::before {
  content: '';
  position: absolute;
  z-index: -1;
  inset: -78px 0 0;
  background: linear-gradient(
    180deg,
    transparent,
    rgba(3, 3, 3, 0.52) 58%,
    rgba(2, 2, 2, 0.8)
  );
  pointer-events: none;
}

.now-mini {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 11px;
}

.now-mini__top {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.mini-cover {
  width: 50px;
  height: 50px;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.04);
  box-shadow:
    0 8px 20px rgba(0, 0, 0, 0.42),
    inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}

.mini-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.mini-copy {
  min-width: 0;
}

.mini-title {
  overflow: hidden;
  margin: 0;
  color: rgba(246, 241, 233, 0.84);
  font-size: 13px;
  letter-spacing: 0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-artist {
  overflow: hidden;
  margin: 5px 0 0;
  color: rgba(246, 241, 233, 0.35);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-actions {
  display: flex;
  align-items: center;
  gap: 7px;
  padding-left: 62px;
}

.mini-btn {
  display: inline-flex;
  height: 31px;
  min-width: 31px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 999px;
  color: rgba(246, 241, 233, 0.52);
  transition:
    background-color 180ms ease,
    color 180ms ease,
    transform 180ms ease;
}

.mini-btn:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.065);
  color: var(--player-ink);
}

.mini-btn.is-liked {
  color: #ff6f88;
}

.mini-btn .count {
  font-size: 10px;
  letter-spacing: 0.02em;
}

.center {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 9px;
}

.transport-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 15px;
}

.ctrl-btn {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  color: rgba(246, 241, 233, 0.64);
  font-size: 20px;
  transition:
    color 180ms ease,
    background-color 180ms ease,
    transform 180ms ease;
}

.ctrl-btn:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.play-btn {
  display: grid;
  width: 56px;
  height: 56px;
  place-items: center;
  border-radius: 50%;
  background: linear-gradient(145deg, #6aec9e, var(--player-accent-deep));
  color: #05140b;
  font-size: 26px;
  box-shadow:
    0 14px 34px rgba(43, 218, 119, 0.26),
    inset 0 1px 0 rgba(255, 255, 255, 0.45);
  transition:
    transform 200ms cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 200ms ease;
}

.play-btn:hover {
  transform: scale(1.045);
  box-shadow:
    0 18px 40px rgba(43, 218, 119, 0.34),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.play-btn:active {
  transform: scale(0.97);
}

.volume-wrap {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: 3px;
}

.volume-range,
.progress-range {
  appearance: none;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.volume-range {
  width: 66px;
  height: 16px;
}

.volume-range::-webkit-slider-runnable-track {
  height: 3px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    rgba(246, 241, 233, 0.56) 0 var(--volume),
    rgba(255, 255, 255, 0.14) var(--volume) 100%
  );
}

.volume-range::-webkit-slider-thumb {
  width: 6px;
  height: 6px;
  margin-top: -1.5px;
  appearance: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
  opacity: 0;
  transition: opacity 160ms ease;
}

.volume-wrap:hover .volume-range::-webkit-slider-thumb,
.volume-range:focus-visible::-webkit-slider-thumb {
  opacity: 1;
}

.volume-range::-moz-range-track {
  height: 3px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
}

.volume-range::-moz-range-progress {
  height: 3px;
  border-radius: 999px;
  background: rgba(246, 241, 233, 0.56);
}

.volume-range::-moz-range-thumb {
  width: 6px;
  height: 6px;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
  opacity: 0;
}

.volume-wrap:hover .volume-range::-moz-range-thumb {
  opacity: 1;
}

.timeline {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 40px;
  align-items: center;
  gap: 11px;
  color: rgba(246, 241, 233, 0.42);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.03em;
}

.timeline .time-end {
  text-align: right;
}

.progress-range {
  width: 100%;
  height: 16px;
}

.progress-range::-webkit-slider-runnable-track {
  height: 3px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    rgba(91, 227, 141, 0.62) 0 var(--progress),
    rgba(255, 255, 255, 0.15) var(--progress) 100%
  );
  box-shadow: 0 0 14px rgba(91, 227, 141, 0.12);
}

.progress-range::-webkit-slider-thumb {
  width: 7px;
  height: 7px;
  margin-top: -2px;
  appearance: none;
  border-radius: 50%;
  background: #f8fff9;
  box-shadow: 0 0 0 4px rgba(91, 227, 141, 0.1);
  opacity: 0;
  transition:
    opacity 160ms ease,
    transform 160ms ease;
}

.progress-range:hover::-webkit-slider-thumb,
.progress-range:focus-visible::-webkit-slider-thumb {
  opacity: 1;
}

.progress-range::-moz-range-track {
  height: 3px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.15);
}

.progress-range::-moz-range-progress {
  height: 3px;
  border-radius: 999px;
  background: rgba(91, 227, 141, 0.8);
}

.progress-range::-moz-range-thumb {
  width: 7px;
  height: 7px;
  border: 0;
  border-radius: 50%;
  background: #f8fff9;
  opacity: 0;
}

.progress-range:hover::-moz-range-thumb {
  opacity: 1;
}

.tools {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.tools :deep(.el-popover__reference-wrapper) {
  display: inline-flex;
}

.tool-btn {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  color: rgba(246, 241, 233, 0.58);
  font-size: 19px;
  transition:
    background-color 180ms ease,
    color 180ms ease,
    transform 180ms ease;
}

.tool-btn:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.06);
  color: var(--player-ink);
}

@keyframes rise-in {
  from {
    opacity: 0;
    transform: translateY(18px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1180px) {
  .transport {
    grid-template-columns:
      minmax(220px, 1fr) minmax(440px, 560px)
      minmax(220px, 1fr);
    padding-inline: 28px;
  }

  .volume-range {
    width: 44px;
  }
}

@media (max-width: 900px) {
  .transport {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 0 22px 16px;
  }

  .now-mini {
    width: 100%;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .now-mini__top {
    min-width: 0;
    flex: 1;
  }

  .mini-cover {
    width: 38px;
    height: 38px;
  }

  .mini-actions {
    flex: 0 0 auto;
    padding-left: 0;
  }

  .center {
    width: 100%;
  }

  .transport-row {
    gap: 12px;
  }

  .tools {
    width: 100%;
    justify-content: center;
  }

  .tools :deep(.el-popover__reference-wrapper) {
    display: inline-flex;
  }
}

@media (max-width: 600px) {
  .transport {
    padding-inline: 18px;
  }

  .ctrl-btn {
    width: 32px;
    height: 32px;
    font-size: 18px;
  }

  .play-btn {
    width: 50px;
    height: 50px;
    font-size: 23px;
  }

  .timeline {
    grid-template-columns: 34px minmax(0, 1fr) 34px;
    gap: 8px;
  }

  .tools {
    gap: 4px;
  }
}

@media (max-height: 760px) and (min-width: 901px) {
  .transport {
    padding-bottom: 14px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .transport {
    animation: none;
  }
}
</style>
