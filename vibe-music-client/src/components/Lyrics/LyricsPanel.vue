<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { parseLrc } from '@/utils/parsedLyrics'
import LyricLineItem from './LyricLine.vue'

const props = withDefaults(
  defineProps<{
    lyric?: string | null
    lyricStatus?: number | null
    currentTime?: number
    isPlaying?: boolean
    trackId?: string | number
  }>(),
  {
    lyric: '',
    lyricStatus: 0,
    currentTime: 0,
    isPlaying: false,
    trackId: '',
  }
)

const emit = defineEmits<{
  seek: [time: number]
}>()

const scroller = ref<HTMLElement | null>(null)
const autoFollow = ref(true)
let resumeTimer: number | undefined

const parsedLyrics = computed(() => parseLrc(props.lyric))
const hasLyrics = computed(() => parsedLyrics.value.lines.length > 0)
const isInstrumental = computed(() => props.lyricStatus === 4)

const activeIndex = computed(() => {
  const lines = parsedLyrics.value.lines
  if (!lines.length) return -1

  if (!parsedLyrics.value.hasTimestamps) return 0

  const time = Number.isFinite(props.currentTime) ? props.currentTime : 0
  let low = 0
  let high = lines.length - 1
  let result = -1

  while (low <= high) {
    const middle = Math.floor((low + high) / 2)
    const lineTime = lines[middle].time

    if (lineTime === null) {
      high = middle - 1
      continue
    }

    if (lineTime <= time) {
      result = middle
      low = middle + 1
    } else {
      high = middle - 1
    }
  }

  return result
})

const scrollToActive = (behavior: ScrollBehavior = 'smooth') => {
  const container = scroller.value
  const activeLine = container?.querySelector<HTMLElement>('.lyric-line--active')

  if (!container || !activeLine) return

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
    behavior,
  })
}

const scrollAfterRender = (behavior: ScrollBehavior = 'auto') => {
  nextTick(() => {
    window.requestAnimationFrame(() => {
      window.requestAnimationFrame(() => scrollToActive(behavior))
    })
  })
}

const markManualScroll = () => {
  autoFollow.value = false
  window.clearTimeout(resumeTimer)

  resumeTimer = window.setTimeout(() => {
    autoFollow.value = true
    scrollAfterRender('smooth')
  }, 2600)
}

const handleSeek = (time: number) => {
  emit('seek', time)
  autoFollow.value = true
  window.clearTimeout(resumeTimer)
  scrollAfterRender()
}

const resetLyrics = () => {
  autoFollow.value = true
  window.clearTimeout(resumeTimer)
  scrollAfterRender()
}

watch(
  () => props.trackId,
  () => resetLyrics()
)

watch(
  () => props.lyric,
  () => resetLyrics()
)

watch(activeIndex, async (newIndex, oldIndex) => {
  if (newIndex === oldIndex || newIndex < 0 || !autoFollow.value) return
  await nextTick()
  scrollToActive()
})

watch(
  () => parsedLyrics.value.lines.length,
  (length) => {
    if (length > 0 && autoFollow.value) scrollAfterRender()
  }
)

watch(
  () => props.isPlaying,
  (playing) => {
    if (playing && autoFollow.value) scrollAfterRender('smooth')
  }
)

onMounted(() => {
  if (hasLyrics.value) scrollAfterRender()
})

onBeforeUnmount(() => {
  window.clearTimeout(resumeTimer)
})
</script>

<template>
  <div class="relative h-full min-h-0 overflow-hidden rounded-[28px]">
    <div
      v-if="hasLyrics"
      ref="scroller"
      class="lyrics-scroller h-full overflow-y-auto overscroll-contain scroll-smooth px-1 sm:px-4"
      @wheel.passive="markManualScroll"
      @pointerdown="markManualScroll"
      @touchstart.passive="markManualScroll"
    >
      <div class="lyrics-spacer" />
      <div class="space-y-1.5">
        <LyricLineItem
          v-for="(line, index) in parsedLyrics.lines"
          :key="`${line.time ?? 'static'}-${index}-${line.text}`"
          :line="line"
          :active="index === activeIndex"
          :clickable="parsedLyrics.hasTimestamps"
          @seek="handleSeek"
        />
      </div>
      <div class="lyrics-spacer" />
    </div>

    <div
      v-else
      class="no-lyrics-state flex h-full min-h-0 flex-col items-center justify-center px-8 text-center"
    >
      <div
        class="mb-6 flex h-16 w-16 items-center justify-center rounded-full border border-white/50 bg-white/45 shadow-sm backdrop-blur-xl dark:border-white/10 dark:bg-white/5"
      >
        <icon-ri:music-2-line class="text-3xl text-primary/80" />
      </div>
      <h3 class="text-lg font-semibold text-foreground">
        {{ isInstrumental ? '这是伴奏曲目' : '这首歌暂时没有歌词' }}
      </h3>
      <p class="mt-2 max-w-64 text-sm leading-6 text-muted-foreground">
        {{
          isInstrumental
            ? '该曲目已标记为伴奏，无需歌词搜索。'
            : '歌词上线后会自动出现在这里，并跟随播放进度滚动。'
        }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.lyrics-scroller {
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--cover-primary, #2a68fa) 38%, transparent) transparent;
}

.lyrics-scroller::-webkit-scrollbar {
  width: 4px;
}

.lyrics-scroller::-webkit-scrollbar-track {
  background: transparent;
}

.lyrics-scroller::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: color-mix(in srgb, var(--cover-primary, #2a68fa) 36%, transparent);
}

.lyrics-spacer {
  height: clamp(24px, 22vh, 240px);
  flex: 0 0 auto;
  pointer-events: none;
}

.no-lyrics-state {
  overflow: hidden;
}

@media (max-height: 820px) {
  .lyrics-spacer {
    height: clamp(16px, 16vh, 120px);
  }

  .no-lyrics-state {
    padding-inline: 1rem;
  }

  .no-lyrics-state > :first-child {
    margin-bottom: 0.75rem;
    width: 3rem;
    height: 3rem;
  }
}
</style>
