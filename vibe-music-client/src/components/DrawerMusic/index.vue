<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useFullscreen } from '@vueuse/core'
import Stage from './stage.vue'
import Comments from './comments.vue'
import Transport from './transport.vue'
import LyricsOverlay from './lyrics-overlay.vue'
import { getSongDetail } from '@/api/system'
import type { SongDetail } from '@/api/interface'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import {
  DEFAULT_COVER_COLORS,
  extractCoverColors,
  type CoverColors,
} from '@/utils/coverColor'

type PlayerDrawerTab = 'player' | 'lyrics' | 'comments'

const props = withDefaults(
  defineProps<{
    initialTab?: PlayerDrawerTab
  }>(),
  {
    initialTab: 'player',
  }
)

const showDrawer = defineModel<boolean>({ default: false })
const { currentTrack } = useAudioPlayer()
const { isFullscreen, toggle: toggleFullscreen } = useFullscreen()
const songDetail = ref<SongDetail | null>(null)
const loadingDetail = ref(false)
const commentsOpen = ref(false)
const lyricsOpen = ref(false)
const coverColors = ref<CoverColors>(DEFAULT_COVER_COLORS)

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

const applyInitialTab = () => {
  commentsOpen.value = props.initialTab === 'comments'
  lyricsOpen.value = props.initialTab === 'lyrics'
}

const refreshDetail = async () => {
  const songId = songDetail.value?.songId || currentTrack.value.id
  if (!songId) return
  await loadSongDetail(songId, false)
}

const handleLikeChange = (status: number) => {
  if (songDetail.value) {
    songDetail.value.likeStatus = status
  }
}

watch(
  () => currentTrack.value.id,
  async (newId) => {
    if (!newId) {
      songDetail.value = null
      commentsOpen.value = false
      lyricsOpen.value = false
      return
    }
    await loadSongDetail(newId)
  },
  { immediate: true }
)

watch(showDrawer, async (visible) => {
  if (!visible) {
    commentsOpen.value = false
    lyricsOpen.value = false
    return
  }

  applyInitialTab()
  const songId = currentTrack.value.id
  if (songId && !songDetail.value) {
    await loadSongDetail(songId, false)
  }
})

watch(
  () => props.initialTab,
  () => {
    if (showDrawer.value) applyInitialTab()
  }
)

watch(
  coverUrl,
  async (url) => {
    coverColors.value = await extractCoverColors(url)
  },
  { immediate: true }
)

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key !== 'Escape') return

  if (commentsOpen.value) {
    commentsOpen.value = false
    return
  }
  if (lyricsOpen.value) {
    lyricsOpen.value = false
    return
  }
  if (showDrawer.value) {
    showDrawer.value = false
  }
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  detailRequestId += 1
})
</script>

<template>
  <el-drawer
    v-model="showDrawer"
    direction="btt"
    size="100%"
    :modal="false"
    :show-close="false"
    :with-header="false"
    :close-on-press-escape="false"
    class="music-player-drawer"
    :style="drawerStyle"
  >
    <div class="player-backdrop">
      <div class="ambient" aria-hidden="true" />
      <div class="scrim" aria-hidden="true" />
      <div class="grain" aria-hidden="true" />

      <div class="app-shell">
        <header class="topbar">
          <div class="brand">
            <button
              type="button"
              class="round-btn"
              aria-label="收起播放页"
              @click="showDrawer = false"
            >
              <icon-ri:arrow-down-s-line />
            </button>
            <div class="brand-copy">
              <div class="brand-kicker">Immersive player</div>
              <div class="brand-name">VIBE MUSIC</div>
            </div>
          </div>

          <div class="top-actions">
            <span class="esc-hint">ESC 收起</span>
            <button
              type="button"
              class="round-btn"
              :aria-label="isFullscreen ? '退出全屏' : '进入全屏'"
              :title="isFullscreen ? '退出全屏' : '进入全屏'"
              @click="toggleFullscreen"
            >
              <icon-ri:fullscreen-exit-line v-if="isFullscreen" />
              <icon-ri:fullscreen-line v-else />
            </button>
          </div>
        </header>

        <main class="player-main">
          <Stage :song-detail="songDetail" :loading="loadingDetail" />
        </main>

        <Transport
          :song-detail="songDetail"
          @open-comments="commentsOpen = true"
          @like-change="handleLikeChange"
        />
      </div>

      <Comments
        v-model:open="commentsOpen"
        :song-detail="songDetail"
        @refresh="refreshDetail"
      />

      <LyricsOverlay v-model:open="lyricsOpen" :song-detail="songDetail" />
    </div>
  </el-drawer>
</template>

<style scoped>
.player-backdrop {
  --player-ink: #f6f1e9;
  --player-muted: rgba(246, 241, 233, 0.5);
  --player-faint: rgba(246, 241, 233, 0.24);
  --player-line: rgba(255, 255, 255, 0.1);
  --player-accent: #5be38d;
  --player-accent-deep: #20c76b;

  position: relative;
  height: 100%;
  overflow: hidden;
  isolation: isolate;
  background: radial-gradient(
      ellipse at 76% 72%,
      rgba(151, 87, 38, 0.34),
      transparent 55%
    ),
    radial-gradient(
      ellipse at 20% 18%,
      rgba(145, 163, 151, 0.13),
      transparent 42%
    ),
    radial-gradient(
      ellipse at 54% 36%,
      color-mix(in srgb, var(--cover-primary, #d88954) 14%, transparent),
      transparent 46%
    ),
    linear-gradient(
      180deg,
      rgba(4, 5, 5, 0.72) 0%,
      rgba(4, 5, 5, 0.34) 26%,
      rgba(4, 4, 4, 0.4) 62%,
      rgba(2, 2, 2, 0.9) 100%
    );
  background-color: #070808;
}

.ambient,
.scrim,
.grain {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.ambient {
  inset: -16%;
  background-image: var(--track-cover-url);
  background-position: center 38%;
  background-size: cover;
  filter: blur(78px) saturate(0.92) brightness(0.7);
  opacity: 0.72;
  transform: scale(1.18);
  animation: ambient-breathe 26s ease-in-out infinite alternate;
}

.scrim {
  z-index: 1;
  background: radial-gradient(
      ellipse at 76% 72%,
      rgba(151, 87, 38, 0.2),
      transparent 55%
    ),
    linear-gradient(
      180deg,
      rgba(4, 5, 5, 0.18) 0%,
      rgba(4, 5, 5, 0.08) 32%,
      rgba(3, 3, 3, 0.32) 68%,
      rgba(2, 2, 2, 0.72) 100%
    );
}

.scrim::after {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(
    ellipse at center,
    transparent 42%,
    rgba(0, 0, 0, 0.55) 100%
  );
}

.grain {
  z-index: 2;
  opacity: 0.055;
  mix-blend-mode: overlay;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 180 180' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.9'/%3E%3C/svg%3E");
}

.app-shell {
  position: relative;
  z-index: 3;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  height: 100%;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 32px 0;
  position: relative;
  z-index: 12;
  animation: rise-in 700ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.brand {
  display: flex;
  align-items: center;
  gap: 15px;
}

.round-btn {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.055);
  color: rgba(246, 241, 233, 0.72);
  font-size: 20px;
  backdrop-filter: blur(14px);
  transition:
    background-color 180ms ease,
    color 180ms ease,
    transform 180ms ease;
}

.round-btn:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.11);
  color: var(--player-ink);
}

.brand-copy {
  padding-top: 1px;
}

.brand-kicker {
  color: var(--player-faint);
  font-size: 9px;
  letter-spacing: 0.34em;
  line-height: 1;
  text-transform: uppercase;
}

.brand-name {
  margin-top: 6px;
  color: rgba(246, 241, 233, 0.64);
  font-size: 12px;
  letter-spacing: 0.2em;
  line-height: 1;
}

.top-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.esc-hint {
  color: var(--player-faint);
  font-size: 10px;
  letter-spacing: 0.14em;
}

.player-main {
  display: flex;
  min-height: 0;
  overflow: hidden;
}

@keyframes ambient-breathe {
  0% {
    transform: scale(1.18) translate3d(-0.8%, 0, 0);
  }
  100% {
    transform: scale(1.23) translate3d(1.2%, -0.6%, 0);
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
  .esc-hint {
    display: none;
  }
}

@media (max-width: 900px) {
  .topbar {
    padding: 16px 18px 0;
  }

  .brand-copy {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ambient {
    animation: none;
  }

  .topbar {
    animation: none;
  }
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
