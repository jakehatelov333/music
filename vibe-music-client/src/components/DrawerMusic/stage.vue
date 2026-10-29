<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { SongDetail } from '@/api/interface'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import { parseLrc } from '@/utils/parsedLyrics'
import { formatTime } from '@/utils'
import defaultAlbum from '@/assets/default_album.jpg'

defineOptions({ name: 'PlayerStage' })

const props = withDefaults(
  defineProps<{
    songDetail: SongDetail | null
    loading?: boolean
  }>(),
  {
    loading: false,
  }
)

const { currentTrack, currentTime, duration, isPlaying, seek } =
  useAudioPlayer()

const coverUrl = computed(
  () => props.songDetail?.coverUrl || currentTrack.value.cover || defaultAlbum
)
const title = computed(
  () => props.songDetail?.songName || currentTrack.value.title
)
const artist = computed(
  () => props.songDetail?.artistName || currentTrack.value.artist
)
const album = computed(
  () => props.songDetail?.album || currentTrack.value.album || ''
)
const releaseYear = computed(() => {
  const releaseTime = props.songDetail?.releaseTime
  if (!releaseTime) return ''
  const date = new Date(releaseTime)
  return Number.isNaN(date.getTime()) ? '' : String(date.getFullYear())
})
const totalTime = computed(() => {
  const seconds =
    duration.value ||
    Number(props.songDetail?.duration || currentTrack.value.duration || 0)
  return seconds > 0 ? formatTime(seconds) : ''
})
const metaItems = computed(() =>
  [album.value, releaseYear.value, totalTime.value].filter(Boolean)
)

const parsedLyrics = computed(() => parseLrc(props.songDetail?.lyric))
const lyricLines = computed(() =>
  parsedLyrics.value.lines.filter((line) => line.text.trim())
)

const activeLyricIndex = computed(() => {
  const lines = lyricLines.value
  if (!lines.length) return -1
  if (!parsedLyrics.value.hasTimestamps) return 0

  const time = Number.isFinite(currentTime.value) ? currentTime.value : 0
  let result = 0

  for (let index = 0; index < lines.length; index += 1) {
    const lineTime = lines[index].time
    if (lineTime === null) break
    if (lineTime <= time) {
      result = index
      continue
    }
    break
  }

  return result
})

const lyricScroller = ref<HTMLElement | null>(null)

const isDraggingLyrics = ref(false)
let lyricsDragPointerId: number | null = null
let lyricsDragStartY = 0
let lyricsDragStartScrollTop = 0
let lyricsDragMoved = false

const handleLyricsPointerDown = (event: PointerEvent) => {
  if (event.pointerType !== 'mouse' || event.button !== 0) return
  const container = lyricScroller.value
  if (!container || container.scrollHeight <= container.clientHeight) return
  lyricsDragPointerId = event.pointerId
  lyricsDragStartY = event.clientY
  lyricsDragStartScrollTop = container.scrollTop
  lyricsDragMoved = false
}

const handleLyricsPointerMove = (event: PointerEvent) => {
  if (lyricsDragPointerId !== event.pointerId) return
  const container = lyricScroller.value
  if (!container) return
  const delta = event.clientY - lyricsDragStartY
  if (!lyricsDragMoved && Math.abs(delta) < 5) return
  lyricsDragMoved = true
  isDraggingLyrics.value = true
  container.scrollTop = lyricsDragStartScrollTop - delta
}

const endLyricsDrag = (event: PointerEvent) => {
  if (lyricsDragPointerId !== event.pointerId) return
  lyricsDragPointerId = null
  // click 事件在 pointerup 之后派发，延迟到下一轮再复位，避免拖动后误触歌词跳转
  window.setTimeout(() => {
    lyricsDragMoved = false
    isDraggingLyrics.value = false
  }, 0)
}

const swallowLyricsClick = (event: MouseEvent) => {
  if (!lyricsDragMoved) return
  lyricsDragMoved = false
  isDraggingLyrics.value = false
  event.preventDefault()
  event.stopPropagation()
}

const scrollActiveIntoView = () => {
  nextTick(() => {
    const activeLine = lyricScroller.value?.querySelector<HTMLElement>(
      '.scroll-line.is-active'
    )
    if (!activeLine) return

    const container = lyricScroller.value
    if (!container) return
    const containerRect = container.getBoundingClientRect()
    const lineRect = activeLine.getBoundingClientRect()
    const top =
      container.scrollTop +
      lineRect.top -
      containerRect.top -
      container.clientHeight / 2 +
      lineRect.height / 2

    container.scrollTo({
      top: Math.max(0, top),
      behavior: 'smooth',
    })
  })
}

const handleSeek = (time: number | null) => {
  if (time === null) return
  seek(time)
}

watch(
  () => props.songDetail?.songId,
  () => scrollActiveIntoView()
)

watch(activeLyricIndex, () => {
  if (!isDraggingLyrics.value) scrollActiveIntoView()
})
</script>

<template>
  <section class="stage">
    <div class="cover-wrap">
      <div class="cover-glow" aria-hidden="true" />
      <div class="cover-art" :class="{ 'is-playing': isPlaying }">
        <img :src="coverUrl" :alt="`${title} 专辑封面`" />
      </div>
    </div>

    <div class="info">
      <div class="eyebrow">
        <span class="eyebrow-dot" />
        <span>Now playing</span>
        <span v-if="releaseYear">·</span>
        <span v-if="releaseYear">{{ releaseYear }}</span>
      </div>

      <h1 class="title">{{ title }}</h1>
      <p class="artist">{{ artist }}</p>

      <div v-if="metaItems.length" class="meta-row">
        <template v-for="(item, index) in metaItems" :key="item">
          <span v-if="index > 0" class="meta-sep" />
          <span>{{ item }}</span>
        </template>
      </div>

      <div
        v-if="lyricLines.length"
        ref="lyricScroller"
        class="lyrics-scroll"
        :class="{ 'is-dragging': isDraggingLyrics }"
        @pointerdown="handleLyricsPointerDown"
        @pointermove="handleLyricsPointerMove"
        @pointerup="endLyricsDrag"
        @pointercancel="endLyricsDrag"
        @click.capture="swallowLyricsClick"
      >
        <button
          v-for="(line, index) in lyricLines"
          :key="`${line.time ?? 'static'}-${index}-${line.text}`"
          type="button"
          class="scroll-line"
          :class="{ 'is-active': index === activeLyricIndex }"
          :disabled="line.time === null"
          @click="handleSeek(line.time)"
        >
          {{ line.text }}
        </button>
      </div>

      <div v-else-if="songDetail?.lyricStatus === 4" class="instrumental-hint">
        这是伴奏曲目
      </div>

      <div v-else class="no-lyrics-hint">这首歌暂时没有歌词</div>
    </div>
  </section>
</template>

<style scoped>
.stage {
  display: grid;
  grid-template-columns: minmax(280px, 360px) minmax(400px, 560px);
  align-items: center;
  justify-content: center;
  gap: clamp(60px, 7vw, 112px);
  width: 100%;
  min-height: 0;
  padding: 0 64px;
  transform: translateY(-1.2vh);
}

.cover-wrap {
  position: relative;
  width: 100%;
  max-width: 360px;
  aspect-ratio: 1;
  justify-self: end;
  animation: rise-in 900ms 80ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.cover-glow {
  position: absolute;
  inset: 9%;
  border-radius: 42px;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--cover-primary, #d88954) 48%, transparent),
    color-mix(in srgb, var(--cover-secondary, #79a393) 30%, transparent)
  );
  filter: blur(52px);
  opacity: 0.55;
  transform: scale(1.04);
}

.cover-art {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.03);
  box-shadow:
    0 46px 90px rgba(0, 0, 0, 0.56),
    0 10px 28px rgba(0, 0, 0, 0.38),
    inset 0 0 0 1px rgba(255, 255, 255, 0.08);
  transform-origin: center;
  transition:
    transform 500ms cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 500ms ease;
}

.cover-art img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  pointer-events: none;
  user-select: none;
}

.cover-art::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    128deg,
    rgba(255, 255, 255, 0.1),
    transparent 26%,
    transparent 72%,
    rgba(255, 255, 255, 0.045)
  );
  pointer-events: none;
}

.cover-art.is-playing {
  animation: cover-breathe 7s ease-in-out infinite;
}

.cover-wrap:hover .cover-art {
  transform: scale(1.012);
  box-shadow:
    0 52px 100px rgba(0, 0, 0, 0.6),
    0 12px 30px rgba(0, 0, 0, 0.42),
    inset 0 0 0 1px rgba(255, 255, 255, 0.12);
}

.info {
  width: 100%;
  max-width: 560px;
  padding-bottom: 10px;
  animation: rise-in 900ms 180ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: 11px;
  color: var(--player-faint);
  font-size: 9px;
  letter-spacing: 0.34em;
  text-transform: uppercase;
}

.eyebrow-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--player-accent);
  box-shadow: 0 0 12px rgba(91, 227, 141, 0.9);
}

.title {
  margin: 18px 0 0;
  color: var(--player-ink);
  font-size: clamp(30px, 2.7vw, 40px);
  font-weight: 500;
  letter-spacing: 0.025em;
  line-height: 1.16;
  text-wrap: balance;
}

.artist {
  margin: 10px 0 0;
  color: rgba(246, 241, 233, 0.48);
  font-size: 15px;
  letter-spacing: 0.06em;
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 24px;
  color: rgba(246, 241, 233, 0.3);
  font-size: 10px;
  letter-spacing: 0.14em;
}

.meta-sep {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: rgba(246, 241, 233, 0.22);
}

.lyrics-scroll {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: clamp(24px, 4vh, 40px);
  max-height: clamp(200px, 34vh, 300px);
  overflow-y: auto;
  padding: 18px 22px 18px 0;
  mask-image: linear-gradient(
    180deg,
    transparent,
    #000 16%,
    #000 84%,
    transparent
  );
  scrollbar-width: none;
  cursor: grab;
}

.lyrics-scroll::-webkit-scrollbar {
  display: none;
}

.lyrics-scroll.is-dragging {
  cursor: grabbing;
}

.scroll-line {
  padding: 6px 12px;
  border: 0;
  border-radius: 10px;
  background: none;
  color: rgba(246, 241, 233, 0.34);
  font-family: inherit;
  font-size: clamp(15px, 1.5vw, 19px);
  letter-spacing: 0.03em;
  line-height: 1.6;
  text-align: left;
  user-select: none;
  transition:
    background-color 180ms ease,
    color 180ms ease;
}

.scroll-line:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.06);
  color: rgba(246, 241, 233, 0.72);
}

.scroll-line.is-active {
  color: #fffaf3;
  font-size: clamp(19px, 2vw, 25px);
  font-weight: 500;
  text-shadow: 0 0 24px rgba(255, 187, 132, 0.18);
}

.scroll-line:disabled {
  cursor: default;
}

.instrumental-hint,
.no-lyrics-hint {
  margin-top: clamp(24px, 4vh, 40px);
  padding-left: 22px;
  border-left: 2px solid rgba(91, 227, 141, 0.5);
  color: rgba(246, 241, 233, 0.34);
  font-size: 14px;
  letter-spacing: 0.04em;
}

@keyframes cover-breathe {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.014);
  }
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
  .stage {
    gap: 64px;
    padding: 0 44px;
  }
}

@media (max-width: 900px) {
  .stage {
    grid-template-columns: minmax(0, 1fr);
    align-content: center;
    justify-items: center;
    gap: 34px;
    padding: 18px 26px 8px;
    transform: none;
  }

  .cover-wrap {
    width: min(46vw, 260px);
    max-width: 260px;
    justify-self: center;
  }

  .info {
    max-width: 520px;
    text-align: center;
  }

  .eyebrow,
  .meta-row {
    justify-content: center;
  }

  .lyrics-scroll {
    margin-top: 26px;
    max-height: clamp(160px, 30vh, 240px);
    padding-right: 0;
  }

  .instrumental-hint,
  .no-lyrics-hint {
    margin-top: 26px;
    padding-left: 0;
    border-left: 0;
    text-align: center;
  }

  .scroll-line {
    text-align: center;
  }
}

@media (max-width: 600px) {
  .cover-wrap {
    width: min(58vw, 220px);
  }

  .title {
    font-size: 26px;
  }

  .scroll-line.is-active {
    font-size: 19px;
  }

  .meta-row {
    margin-top: 16px;
  }
}

@media (max-height: 760px) and (min-width: 901px) {
  .cover-wrap {
    max-width: 300px;
  }

  .lyrics-scroll {
    max-height: clamp(160px, 30vh, 240px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .cover-art.is-playing {
    animation: none;
  }

  .cover-wrap,
  .info {
    animation: none;
  }
}
</style>
