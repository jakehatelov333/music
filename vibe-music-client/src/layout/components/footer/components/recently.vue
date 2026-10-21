<script setup lang="ts">
import { formatMillisecondsToTime } from '@/utils'
import { trackModel } from '@/stores/interface'
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
    popper-class="!rounded-lg !p-0"
  >
    <template #reference>
      <div class="flex items-center">
        <button class="p-2 rounded-full hover:bg-hoverMenuBg transition w-9">
          <icon-ri-play-list-2-fill class="w-full h-full" />
        </button>
      </div>
    </template>
    <div class="bg-popoverBg rounded-lg p-2">
      <div class="flex items-center justify-between mb-2">
        <span class="text-sm text-gray-400">播放列表</span>
        <button class="p-2 rounded-full hover:bg-hoverMenuBg transition" @click="handleClearAll">
          <icon-material-symbols:delete-outline-rounded class="text-sm text-gray-500" />
        </button>
      </div>
      <div class="flex flex-col">
        <el-scrollbar class="h-96">
          <div v-for="(item, index) in audio.trackList" :key="index" @click="playMusic(item)"
            @mouseover="mouseOverIndex = index" @mouseleave="mouseOverIndex = -1"
            class="flex items-center gap-2 p-2 my-1 rounded-lg transition group cursor-pointer"
            :class="`hover:bg-gray-300 ${audio.currentSongIndex == index ? 'bg-gray-300 dark:bg-[#414243]' : ''} dark:hover:bg-[#414243] `">
            <div class="w-10 h-10 shrink-0 rounded-lg overflow-hidden relative">
              <img :src="item.cover" alt="" />
              <!-- Play 按钮，使用 group-hover 控制透明度 -->
              <div
                class="absolute inset-0 flex items-center justify-center text-white opacity-0 transition-opacity duration-300 z-10 group-hover:opacity-100 group-hover:bg-black/50 rounded-md">
                <icon-tabler:player-play-filled class="text-lg" />
              </div>
            </div>
            <div class="min-w-0 flex-1">
              <div class="truncate text-sm">{{ item.title }}</div>
              <div class="truncate text-xs text-gray-400">{{ item.artist }}</div>
            </div>
            <div class="text-xs text-gray-400">
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
              <icon-material-symbols:delete-outline-rounded class="text-lg text-gray-500" />
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
</style>
