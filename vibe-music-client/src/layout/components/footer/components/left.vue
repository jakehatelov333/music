<script setup lang="ts">
import { useAudioPlayer } from '@/hooks/useAudioPlayer'

const emit = defineEmits<{
  open: []
}>()

const { currentTrack } = useAudioPlayer()
</script>

<template>
  <button
    type="button"
    class="footer-track-button flex min-w-0 items-center gap-2 cursor-pointer select-none hover:bg-hoverMenuBg transition-colors rounded-lg p-1 text-left"
    @click="emit('open')"
  >
    <div class="min-w-12 max-w-12 h-full">
      <img
        :src="currentTrack.cover + '?param=90y90'"
        :alt="currentTrack.title"
        class="w-full h-full object-cover rounded-lg m-1"
      />
    </div>
    <div class="min-w-0">
      <div
        class="text-base text-primary-foreground line-clamp-1 mb-0.5 mx-2"
        :title="currentTrack.title"
      >
        {{ currentTrack.title }}
      </div>
      <div class="text-xs text-muted-foreground line-clamp-1 h-4 mt-0.5 mx-2">
        {{ currentTrack.artist }}
      </div>
    </div>
  </button>
</template>

<style scoped>
.footer-track-button {
  width: clamp(11rem, 15vw, 16rem);
}
</style>
