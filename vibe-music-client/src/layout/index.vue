<script setup lang="ts">
import Header from './components/header/index.vue'
import Aside from './components/aside/index.vue'
import Main from './components/main/index.vue'
import Footer from './components/footer/index.vue'
// import BG from './components/bg/index.vue'
import AiWidget from '@/components/Common/AiWidget.vue'

// 注意：播放器（provide('audioPlayer')）已经上移到 App.vue。
// 放在这里会导致切到 /login、/landing 时 layout 卸载、音乐中断。
</script>
<template>
  <!-- <BG /> -->
  <div class="app-shell absolute w-full flex min-h-0 flex-col">
    <div
      class="w-full flex min-h-0 flex-1 flex-col overflow-hidden bg-themeBgColor backdrop-blur-lg"
    >
      <Header />
      <div class="flex min-h-0 min-w-0 flex-1 overflow-hidden">
        <Aside />
        <Main />
      </div>
      <Footer />
      <AiWidget />
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  overflow: hidden;
}
</style>
