<script setup lang="ts">
import { formatMillisecondsToTime } from '@/utils'
import { trackModel } from '@/stores/interface'

defineOptions({ name: 'PlayerQueue' })

const props = withDefaults(
  defineProps<{
    immersive?: boolean
  }>(),
  {
    immersive: false,
  }
)

const audio = AudioStore()
const { loadTrack, play, audioElement } = useAudioPlayer()

const mouseOverIndex = ref(-1) // 用于跟踪鼠标悬停的索引

const playMusic = async (song: trackModel) => {
  audio.addTracks(song)
  // 加载
  await loadTrack()
  play()
}

const handleClearAll = () => {
  audio.setAudioStore('trackList', [])
  audio.setAudioStore('currentSongIndex', 0)
  if (audioElement.value) {
    audioElement.value.pause()
    audioElement.value.src = ''
  }
}
</script>
<template>
  <el-popover
    :width="450"
    :popper-style="{ width: 'min(450px, calc(100vw - 24px))' }"
    trigger="click"
    placement="top-end"
    :popper-class="props.immersive ? 'player-queue-popper' : '!rounded-lg !p-0'"
  >
    <template #reference>
      <div class="flex items-center">
        <button
          :class="
            props.immersive
              ? 'player-queue-trigger'
              : 'p-2 rounded-full hover:bg-hoverMenuBg transition w-9 h-9'
          "
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" class="h-full w-full">
            <path
              d="M3.4 5.6h12.4M3.4 11.2h12.4M3.4 16.8h7"
              stroke="currentColor"
              stroke-width="2.3"
              stroke-linecap="round"
            />
            <path
              d="M14.4 13.8v6l5-3-5-3Z"
              fill="currentColor"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linejoin="round"
            />
          </svg>
        </button>
      </div>
    </template>
    <div
      class="bg-popoverBg rounded-lg p-2"
      :class="{ 'queue-content--immersive': props.immersive }"
    >
      <div class="flex items-center justify-between mb-2">
        <span
          class="text-sm text-gray-400"
          :class="{ 'queue-heading': props.immersive }"
        >
          播放列表
        </span>
        <button
          class="p-2 rounded-full hover:bg-hoverMenuBg transition"
          @click="handleClearAll"
        >
          <icon-material-symbols:delete-outline-rounded
            class="text-sm text-gray-500"
            :class="{ 'queue-clear-icon': props.immersive }"
          />
        </button>
      </div>
      <div class="flex flex-col">
        <el-scrollbar class="h-96">
          <div
            v-for="(item, index) in audio.trackList"
            :key="index"
            @click="playMusic(item)"
            @mouseover="mouseOverIndex = index"
            @mouseleave="mouseOverIndex = -1"
            class="flex items-center gap-2 p-2 my-1 rounded-lg transition group cursor-pointer"
            :class="
              props.immersive
                ? [
                    'queue-row--immersive',
                    audio.currentSongIndex == index
                      ? 'queue-row--immersive--active'
                      : '',
                  ]
                : `hover:bg-gray-300 ${audio.currentSongIndex == index ? 'bg-gray-300 dark:bg-[#414243]' : ''} dark:hover:bg-[#414243] `
            "
          >
            <div class="w-10 h-10 shrink-0 rounded-lg overflow-hidden relative">
              <img :src="item.cover" alt="" />
              <!-- Play 按钮，使用 group-hover 控制透明度 -->
              <div
                class="absolute inset-0 flex items-center justify-center text-white opacity-0 transition-opacity duration-300 z-10 group-hover:opacity-100 group-hover:bg-black/50 rounded-md"
              >
                <icon-tabler:player-play-filled class="text-lg" />
              </div>
            </div>
            <div class="min-w-0 flex-1">
              <div
                class="truncate text-sm"
                :class="{ 'queue-title': props.immersive }"
              >
                {{ item.title }}
              </div>
              <div
                class="truncate text-xs text-gray-400"
                :class="{ 'queue-subtitle': props.immersive }"
              >
                {{ item.artist }}
              </div>
            </div>
            <div
              class="text-xs text-gray-400"
              :class="{ 'queue-duration': props.immersive }"
            >
              {{ formatMillisecondsToTime(Number(item.duration) * 1000) }}
            </div>
            <el-button
              type="primary"
              text
              circle
              class="queue-delete"
              :class="{ 'queue-delete--visible': mouseOverIndex === index }"
              @click.stop="audio.deleteTrack(item.id)"
            >
              <icon-material-symbols:delete-outline-rounded
                class="text-lg text-gray-500"
              />
            </el-button>
          </div>
        </el-scrollbar>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
/* 触屏设备没有 hover，删除按钮必须始终可点。 */
.queue-delete {
  flex-shrink: 0;
}

@media (hover: hover) {
  .queue-delete {
    opacity: 0;
    pointer-events: none;
  }

  .queue-delete--visible {
    opacity: 1;
    pointer-events: auto;
  }
}

.player-queue-trigger {
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

.player-queue-trigger:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.06);
  color: var(--player-ink, #f6f1e9);
}

.queue-content--immersive {
  border-radius: 14px !important;
  background: rgba(16, 18, 17, 0.97) !important;
  color: var(--player-ink, #f6f1e9);
}

.queue-heading {
  color: rgba(246, 241, 233, 0.48) !important;
  font-size: 12px;
  letter-spacing: 0.06em;
}

.queue-clear-icon {
  color: rgba(246, 241, 233, 0.38) !important;
}

.queue-row--immersive {
  color: rgba(246, 241, 233, 0.78);
}

.queue-row--immersive:hover {
  background: rgba(255, 255, 255, 0.06) !important;
}

.queue-row--immersive--active {
  background: rgba(255, 255, 255, 0.085) !important;
}

.queue-row--immersive .el-button {
  color: rgba(246, 241, 233, 0.38);
}

.queue-title {
  color: rgba(246, 241, 233, 0.86);
  letter-spacing: 0.02em;
}

.queue-subtitle,
.queue-duration {
  color: rgba(246, 241, 233, 0.34) !important;
}
</style>

<style>
.player-queue-popper.el-popover.el-popper {
  overflow: hidden;
  padding: 0 !important;
  border: 1px solid rgba(255, 255, 255, 0.08) !important;
  border-radius: 14px !important;
  background: rgba(14, 16, 15, 0.97) !important;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.58) !important;
  backdrop-filter: blur(28px);
}

.player-queue-popper .el-popper__arrow::before {
  border-color: rgba(255, 255, 255, 0.08) !important;
  background: rgba(14, 16, 15, 0.97) !important;
}
</style>
