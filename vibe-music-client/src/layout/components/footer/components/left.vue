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
    class="footer-track-button flex min-w-0 cursor-pointer select-none items-center gap-2 rounded-lg p-1 text-left transition-colors hover:bg-hoverMenuBg"
    @click="emit('open')"
  >
    <div class="h-10 w-10 shrink-0">
      <img
        :src="currentTrack.cover + '?param=90y90'"
        :alt="currentTrack.title"
        class="h-full w-full rounded-lg object-cover"
      />
    </div>
    <div class="hidden min-w-0 sm:block">
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

@media (max-width: 639px) {
  .footer-track-button {
    width: auto;
    flex-shrink: 0;
  }
}
</style>
