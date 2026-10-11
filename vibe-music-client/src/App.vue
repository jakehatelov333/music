<script setup lang="ts">
import DefaultLayout from '@/layout/index.vue'
import { useRoute } from 'vue-router'
import { AudioPlayer } from '@/hooks/useAudioPlayer'

const route = useRoute()

// 播放器在 App 根组件 provide，而不是放在 layout 里。
// 否则切到 /login、/landing 这类 standalone 路由时 layout 被卸载，
// 播放器会跟着一起销毁。
provide('audioPlayer', AudioPlayer())
</script>

<template>
  <router-view v-if="route.meta.standalone" />
  <DefaultLayout v-else />
</template>
