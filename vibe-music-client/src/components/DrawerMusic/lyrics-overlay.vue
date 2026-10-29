<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { SongDetail } from '@/api/interface'
import { parseLrc } from '@/utils/parsedLyrics'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'

defineOptions({ name: 'PlayerLyricsOverlay' })

const props = defineProps<{
  songDetail: SongDetail | null
}>()

const open = defineModel<boolean>('open', { default: false })
const { currentTime, seek } = useAudioPlayer()
const listRef = ref<HTMLElement | null>(null)

const parsedLyrics = computed(() => parseLrc(props.songDetail?.lyric))
const lines = computed(() =>
  parsedLyrics.value.lines.filter((line) => line.text.trim())
)
const title = computed(() => props.songDetail?.songName || '完整歌词')
const artist = computed(() => props.songDetail?.artistName || '')

const activeIndex = computed(() => {
  if (!lines.value.length) return -1
  if (!parsedLyrics.value.hasTimestamps) return 0

  const time = Number.isFinite(currentTime.value) ? currentTime.value : 0
  let result = 0

  for (let index = 0; index < lines.value.length; index += 1) {
    const lineTime = lines.value[index].time
    if (lineTime === null) break
    if (lineTime <= time) {
      result = index
      continue
    }
    break
  }

  return result
})

const scrollToActive = () => {
  nextTick(() => {
    const activeLine = listRef.value?.querySelector<HTMLElement>(
      '.lyric-item.is-active'
    )
    if (!activeLine) return

    const container = listRef.value
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

const close = () => {
  open.value = false
}

const handleSeek = (time: number | null) => {
  if (time === null) return
  seek(time)
  close()
}

watch(open, (visible) => {
  if (visible) scrollToActive()
})

watch(activeIndex, () => {
  if (open.value) scrollToActive()
})
</script>

<template>
  <div
    class="lyrics-layer"
    :class="{ 'is-open': open }"
    :aria-hidden="!open"
    :inert="!open"
    @click.self="close"
  >
    <section
      class="lyrics-sheet"
      role="dialog"
      aria-modal="true"
      aria-label="完整歌词"
    >
      <div class="lyrics-sheet-head">
        <div>
          <p class="panel-kicker">Full lyrics</p>
          <h2 class="lyrics-sheet-title">
            {{ title }}
            <span v-if="artist"> · {{ artist }}</span>
          </h2>
        </div>
        <button
          type="button"
          class="panel-close"
          aria-label="关闭歌词"
          @click="close"
        >
          <icon-ri:close-line />
        </button>
      </div>

      <div v-if="lines.length" ref="listRef" class="lyrics-list">
        <button
          v-for="(line, index) in lines"
          :key="`${line.time ?? 'static'}-${index}-${line.text}`"
          type="button"
          class="lyric-item"
          :class="{ 'is-active': index === activeIndex }"
          :disabled="line.time === null"
          @click="handleSeek(line.time)"
        >
          {{ line.text }}
        </button>
      </div>

      <div v-else class="lyrics-empty">
        <icon-ri:music-2-line />
        <p>
          {{
            songDetail?.lyricStatus === 4
              ? '这是伴奏曲目，无需歌词'
              : '这首歌暂时没有歌词'
          }}
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.lyrics-layer {
  position: absolute;
  z-index: 45;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgba(4, 5, 5, 0.72);
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  backdrop-filter: blur(24px);
  transition:
    opacity 350ms ease,
    visibility 0s linear 350ms;
}

.lyrics-layer.is-open {
  opacity: 1;
  visibility: visible;
  pointer-events: auto;
  transition-delay: 0s;
}

.lyrics-sheet {
  display: flex;
  width: min(680px, 90vw);
  max-height: 74vh;
  flex-direction: column;
  padding: 38px 48px 0;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 18px;
  background: rgba(10, 11, 11, 0.92);
  box-shadow: 0 34px 110px rgba(0, 0, 0, 0.58);
  transform: translateY(10px) scale(0.985);
  transition: transform 350ms cubic-bezier(0.22, 1, 0.36, 1);
}

.lyrics-layer.is-open .lyrics-sheet {
  transform: none;
}

.lyrics-sheet-head {
  display: flex;
  flex: 0 0 auto;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 26px;
}

.panel-kicker {
  margin: 0;
  color: var(--player-faint);
  font-size: 9px;
  letter-spacing: 0.3em;
  text-transform: uppercase;
}

.lyrics-sheet-title {
  margin: 8px 0 0;
  color: var(--player-ink);
  font-size: 22px;
  font-weight: 500;
  letter-spacing: 0.03em;
}

.lyrics-sheet-title span {
  color: var(--player-faint);
  font-size: 13px;
  font-weight: 400;
}

.panel-close {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  color: rgba(246, 241, 233, 0.52);
  font-size: 19px;
  transition:
    background-color 180ms ease,
    color 180ms ease;
}

.panel-close:hover {
  background: rgba(255, 255, 255, 0.07);
  color: var(--player-ink);
}

.lyrics-list {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
  padding: 0 0 42px;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.16) transparent;
}

.lyric-item {
  padding: 7px 10px;
  border-radius: 8px;
  color: rgba(246, 241, 233, 0.48);
  font-size: 16px;
  line-height: 1.7;
  text-align: left;
  transition:
    background-color 180ms ease,
    color 180ms ease;
}

.lyric-item:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.045);
  color: rgba(246, 241, 233, 0.78);
}

.lyric-item.is-active {
  color: var(--player-accent);
  text-shadow: 0 0 24px rgba(91, 227, 141, 0.18);
}

.lyric-item:disabled {
  cursor: default;
}

.lyrics-empty {
  display: flex;
  min-height: 220px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding-bottom: 42px;
  color: var(--player-faint);
  font-size: 30px;
}

.lyrics-empty p {
  margin: 0;
  font-size: 13px;
  letter-spacing: 0.04em;
}

@media (max-width: 600px) {
  .lyrics-sheet {
    width: min(92vw, 680px);
    max-height: 78vh;
    padding: 26px 24px 0;
  }

  .lyrics-sheet-title {
    font-size: 19px;
  }

  .lyric-item {
    font-size: 15px;
  }
}
</style>
