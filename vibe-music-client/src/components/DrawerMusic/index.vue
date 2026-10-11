<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDark, useDateFormat, useNow, useToggle } from '@vueuse/core'
import Left from './left.vue'
import Right from './right.vue'
import Aside from '@/layout/components/aside/index.vue'
import { getSongDetail } from '@/api/system'
import type { SongDetail } from '@/api/interface'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import { themeStore } from '@/stores/modules/theme'
import {
  DEFAULT_COVER_COLORS,
  extractCoverColors,
  type CoverColors,
} from '@/utils/coverColor'

type PlayerDrawerTab = 'lyrics' | 'comments'

const props = withDefaults(
  defineProps<{
    initialTab?: PlayerDrawerTab
  }>(),
  {
    initialTab: 'lyrics',
  }
)

const showDrawer = defineModel<boolean>({ default: false })
const formatted = useDateFormat(useNow(), 'HH:mm:ss')
const theme = themeStore()
const songDetail = ref<SongDetail | null>(null)
const loadingDetail = ref(false)
const activeTab = ref<PlayerDrawerTab>(props.initialTab)
const coverColors = ref<CoverColors>(DEFAULT_COVER_COLORS)
const { currentTrack } = useAudioPlayer()

const isDark = useDark({
  selector: 'html',
  attribute: 'class',
  valueDark: 'dark',
  valueLight: 'light',
})
const toggleDark = useToggle(isDark)
const toggleMode = () => {
  theme.setDark(!isDark.value)
  toggleDark()
}

const coverUrl = computed(
  () => songDetail.value?.coverUrl || currentTrack.value.cover
)

const coverCssUrl = computed(() => {
  const safeUrl = coverUrl.value.replace(/"/g, '%22')
  return `url("${safeUrl}")`
})

const drawerStyle = computed(() => ({
  '--track-cover-url': coverCssUrl.value,
  '--cover-primary': coverColors.value.primary,
  '--cover-secondary': coverColors.value.secondary,
}))

let detailRequestId = 0

const loadSongDetail = async (songId: string | number, clear = true) => {
  const requestId = ++detailRequestId
  if (clear) songDetail.value = null

  if (!songId) return

  loadingDetail.value = true
  try {
    const response = await getSongDetail(Number(songId))
    if (requestId === detailRequestId && response.code === 0 && response.data) {
      songDetail.value = response.data
    }
  } catch (error) {
    console.error('获取歌曲详情失败:', error)
  } finally {
    if (requestId === detailRequestId) {
      loadingDetail.value = false
    }
  }
}

watch(
  () => currentTrack.value.id,
  async (newId) => {
    activeTab.value = 'lyrics'
    if (!newId) {
      songDetail.value = null
      return
    }
    await loadSongDetail(newId)
  },
  { immediate: true }
)

watch(showDrawer, async (visible) => {
  if (!visible) return
  activeTab.value = props.initialTab
  const songId = currentTrack.value.id
  if (songId && (!songDetail.value || !songDetail.value.lyric)) {
    await loadSongDetail(songId, false)
  }
})


watch(
  () => props.initialTab,
  (tab) => {
    if (showDrawer.value) activeTab.value = tab
  }
)

watch(
  coverUrl,
  async (url) => {
    coverColors.value = await extractCoverColors(url)
  },
  { immediate: true }
)

const handleAsideNavigate = () => {
  showDrawer.value = false
}

const handleLikeChange = (status: number) => {
  if (songDetail.value) {
    songDetail.value.likeStatus = status
  }
}
</script>

<template>
  <el-drawer
    v-model="showDrawer"
    direction="btt"
    size="100%"
    :modal="false"
    :show-close="false"
    :with-header="false"
    class="music-player-drawer"
    :style="drawerStyle"
  >
    <div class="player-backdrop relative flex h-full min-h-0 overflow-hidden">
      <Aside class="player-aside" @navigate="handleAsideNavigate" />
      <div class="player-content relative z-10 flex min-h-0 min-w-0 flex-1 flex-col">
      <header class="player-drawer-header relative z-20 flex shrink-0 items-center justify-between">
        <div class="flex items-center gap-3">
          <button
            type="button"
            class="glass-icon-button"
            title="收起播放器"
            @click="showDrawer = false"
          >
            <icon-uiw:down class="text-xl" />
          </button>
          <div class="hidden sm:block">
            <p class="text-[11px] uppercase tracking-[0.28em] text-muted-foreground">
              Now Playing
            </p>
            <p class="text-sm font-medium text-foreground">沉浸式播放器</p>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <div class="glass-pill hidden items-center gap-2 px-4 py-2 sm:flex">
            <icon-meteor-icons:clock class="text-base text-muted-foreground" />
            <span class="text-sm tabular-nums text-foreground">{{ formatted }}</span>
          </div>
          <button
            type="button"
            class="glass-icon-button"
            :title="theme.isDark ? '切换浅色模式' : '切换深色模式'"
            @click="toggleMode"
          >
            <icon-ri:sun-line v-if="theme.isDark" class="text-lg" />
            <icon-ri:moon-line v-else class="text-lg" />
          </button>
        </div>
      </header>

      <main class="player-main relative z-10 grid min-h-0 min-w-0 flex-1 overflow-hidden">
        <section class="player-card glass-panel min-h-0 min-w-0 overflow-hidden rounded-[30px]">
          <Left
            :song-detail="songDetail"
            :loading="loadingDetail"
            @like-change="handleLikeChange"
          />
        </section>

        <section class="lyrics-card glass-panel min-h-0 min-w-0 overflow-hidden rounded-[30px]">
          <Right
            v-model:active-tab="activeTab"
            :song-detail="songDetail"
            :loading="loadingDetail"
          />
        </section>
      </main>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.player-aside {
  position: relative;
  z-index: 10;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.34);
  backdrop-filter: blur(26px) saturate(1.18);
}

.player-drawer-header {
  padding: clamp(0.5rem, 1.6vh, 1rem) clamp(0.75rem, 2vw, 2rem);
}

.player-main {
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: clamp(0.5rem, 1.1vh, 0.75rem);
  padding: 0 clamp(0.75rem, 1.4vw, 1.25rem) clamp(0.75rem, 1.5vh, 1.25rem);
}

.player-card,
.lyrics-card {
  height: 100%;
  contain: layout paint;
}

@media (max-width: 899px) {
  .player-main {
    grid-template-rows: auto auto;
    align-content: start;
    overflow-y: auto;
    overscroll-behavior: contain;
    scrollbar-gutter: stable;
  }

  .player-card {
    height: auto;
    min-height: clamp(30rem, 64vh, 38rem);
  }

  .lyrics-card {
    height: auto;
    min-height: clamp(26rem, 58vh, 34rem);
  }
}

@media (min-width: 900px) {
  .player-main {
    grid-template-columns: minmax(0, 1.08fr) minmax(17rem, 0.92fr);
    grid-template-rows: minmax(0, 1fr);
    align-content: stretch;
    gap: clamp(0.75rem, 1.2vw, 1.25rem);
    overflow: hidden;
  }

  .player-card,
  .lyrics-card {
    height: 100%;
    min-height: 0;
  }
}

@media (max-height: 820px) {
  .player-drawer-header {
    padding-block: 0.4rem;
  }

  .player-main {
    gap: 0.5rem;
    padding-bottom: 0.5rem;
  }
}

.player-backdrop {
  font-family:
    Inter, 'SF Pro Display', 'PingFang SC', 'Microsoft YaHei', system-ui,
    sans-serif;
  background:
    radial-gradient(circle at 18% 12%, color-mix(in srgb, var(--cover-primary) 22%, transparent), transparent 38%),
    radial-gradient(circle at 82% 88%, color-mix(in srgb, var(--cover-secondary) 18%, transparent), transparent 42%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(238, 242, 255, 0.82));
}

.dark .player-backdrop {
  background:
    radial-gradient(circle at 18% 12%, color-mix(in srgb, var(--cover-primary) 30%, transparent), transparent 40%),
    radial-gradient(circle at 82% 88%, color-mix(in srgb, var(--cover-secondary) 24%, transparent), transparent 44%),
    linear-gradient(135deg, rgba(13, 18, 30, 0.96), rgba(18, 24, 42, 0.9));
}

.player-backdrop::before {
  content: '';
  position: absolute;
  inset: -12%;
  z-index: 0;
  background-image: var(--track-cover-url);
  background-position: center;
  background-size: cover;
  filter: blur(78px) saturate(1.35);
  opacity: 0.28;
  transform: scale(1.15);
  pointer-events: none;
}

.player-backdrop::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
  background: linear-gradient(
    180deg,
    rgba(255, 255, 255, 0.05),
    rgba(255, 255, 255, 0.28)
  );
  pointer-events: none;
}

.dark .player-backdrop::after {
  background: linear-gradient(
    180deg,
    rgba(6, 10, 18, 0.08),
    rgba(6, 10, 18, 0.48)
  );
}

.glass-panel {
  border: 1px solid rgba(255, 255, 255, 0.5);
  background: rgba(255, 255, 255, 0.5);
  box-shadow:
    0 24px 70px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.65);
  backdrop-filter: blur(28px) saturate(1.22);
}

.dark .glass-panel {
  border-color: rgba(255, 255, 255, 0.09);
  background: rgba(15, 23, 42, 0.46);
  box-shadow:
    0 24px 70px rgba(0, 0, 0, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

.glass-pill {
  border: 1px solid rgba(255, 255, 255, 0.52);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.48);
  backdrop-filter: blur(18px);
}

.dark .glass-pill {
  border-color: rgba(255, 255, 255, 0.08);
  background: rgba(15, 23, 42, 0.42);
}

.glass-icon-button {
  display: inline-flex;
  width: 42px;
  height: 42px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.55);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.5);
  color: hsl(var(--foreground) / 0.82);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  transition:
    transform 180ms ease,
    background-color 180ms ease,
    color 180ms ease;
  backdrop-filter: blur(18px);
}

.glass-icon-button:hover {
  color: var(--cover-primary);
  background: rgba(255, 255, 255, 0.76);
  transform: translateY(-1px);
}

.dark .glass-icon-button {
  border-color: rgba(255, 255, 255, 0.08);
  background: rgba(15, 23, 42, 0.48);
}

.dark .glass-icon-button:hover {
  background: rgba(30, 41, 59, 0.72);
}

</style>

<style>
.music-player-drawer,
.music-player-drawer .el-drawer__body {
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  padding: 0 !important;
  background: transparent !important;
}

.music-player-drawer .el-drawer__body {
  overflow: hidden;
}
</style>

