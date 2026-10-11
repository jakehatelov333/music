<script setup lang="ts">
import { ref } from 'vue'
import Left from './components/left.vue'
import Center from './components/center.vue'
import Right from './components/right.vue'
import DrawerMusic from '@/components/DrawerMusic/index.vue'

type PlayerDrawerTab = 'lyrics' | 'comments'

const showPlayerDetail = ref(false)
const playerDrawerTab = ref<PlayerDrawerTab>('lyrics')

const openPlayerDetail = (tab: PlayerDrawerTab = 'lyrics') => {
  playerDrawerTab.value = tab
  showPlayerDetail.value = true
}
</script>

<template>
  <footer class="app-player-footer border-t flex min-h-0 items-center justify-between overflow-hidden shadow-2xl shadow-black">
    <!-- 左边：歌曲封面和歌曲名称 -->
    <Left @open="openPlayerDetail('lyrics')" />
    <!-- 中间：控制区 -->
    <Center />
    <!-- 右边：历史播放、音量和歌词入口 -->
    <Right @open-lyrics="openPlayerDetail('lyrics')" />
  </footer>
  <DrawerMusic v-model="showPlayerDetail" :initial-tab="playerDrawerTab" />
</template>

<style scoped>
.app-player-footer {
  flex: 0 0 clamp(4rem, 8vh, 5.5rem);
}
</style>

