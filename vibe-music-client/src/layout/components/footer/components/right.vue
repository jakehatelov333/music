<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import Recently from './recently.vue'
import RecentPlays from './recent-plays.vue'

defineOptions({ name: 'FooterControls' })

const emit = defineEmits<{
  openLyrics: []
}>()

const { volume, setVolume, setPlayMode, playMode } = useAudioPlayer()

const isMuted = computed(() => volume.value === 0)

const toggleVolume = () => {
  setVolume(isMuted.value ? 50 : 0)
}

// 添加播放模式相关逻辑
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

const togglePlayMode = () => {
  const nextMode = playModes[playMode.value].next
  setPlayMode(nextMode)
}
</script>

<template>
  <div class="flex shrink-0 items-center pr-1 md:pr-4">
    <div class="mx-2 hidden items-center sm:flex">
      <el-tooltip
        :content="playModes[playMode].tooltip"
        placement="top"
        effect="dark"
      >
        <button
          class="p-2 rounded-full hover:bg-hoverMenuBg transition w-9 h-9"
          @click="togglePlayMode"
        >
          <Icon :icon="playModes[playMode].icon" class="w-full h-full" />
        </button>
      </el-tooltip>
    </div>
    <button
      type="button"
      class="group flex h-10 items-center gap-1 rounded-full px-2 py-2 text-xs text-muted-foreground transition hover:bg-hoverMenuBg hover:text-primary sm:px-3"
      title="打开歌词"
      @click="emit('openLyrics')"
    >
      <icon-ri:article-line
        class="text-lg transition-transform group-hover:-translate-y-0.5"
      />
      <span class="hidden lg:inline">歌词</span>
    </button>
    <button
      @click="toggleVolume"
      class="hidden h-9 w-9 rounded-full p-2 transition hover:bg-hoverMenuBg sm:block"
    >
      <Icon
        :icon="isMuted ? 'ic:round-volume-off' : 'ic:round-volume-up'"
        class="w-full h-full"
      />
    </button>
    <el-slider
      v-model="volume"
      :show-tooltip="false"
      @change="setVolume"
      class="!w-24 mr-4 hidden lg:block"
      size="small"
      :max="100"
    />
    <RecentPlays class="hidden sm:block" />
    <Recently />
  </div>
</template>

<style scoped>
.el-slider__button-wrapper {
  display: none !important;
}
</style>
