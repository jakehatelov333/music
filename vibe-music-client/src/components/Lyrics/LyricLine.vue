<script setup lang="ts">
import type { LyricLine } from '@/utils/parsedLyrics'

const props = defineProps<{
  line: LyricLine
  active: boolean
  clickable: boolean
}>()

const emit = defineEmits<{
  seek: [time: number]
}>()

const handleClick = () => {
  if (props.active && props.line.time !== null) return
  if (props.clickable && props.line.time !== null) {
    emit('seek', props.line.time)
  }
}
</script>

<template>
  <button
    type="button"
    class="lyric-line group block w-full rounded-2xl px-3 py-3 text-center leading-relaxed transition-all duration-500 ease-out sm:px-5 sm:py-4"
    :class="[
      active
        ? 'lyric-line--active scale-[1.015] font-semibold opacity-100'
        : 'text-base opacity-35 hover:opacity-70',
      clickable && line.time !== null ? 'cursor-pointer' : 'cursor-default',
    ]"
    :disabled="!clickable || line.time === null"
    @click="handleClick"
  >
    <span class="relative z-10">{{ line.text }}</span>
  </button>
</template>

<style scoped>
.lyric-line {
  color: hsl(var(--foreground) / 0.88);
  transform-origin: center;
}

.lyric-line--active {
  color: var(--cover-primary, hsl(var(--primary)));
  font-size: clamp(1.12rem, 1.6vw, 1.5rem);
  text-shadow: 0 0 24px color-mix(in srgb, var(--cover-primary, #2a68fa) 30%, transparent);
}

.lyric-line--active::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--cover-primary, #2a68fa) 10%, transparent),
    color-mix(in srgb, var(--cover-secondary, #8b5cf6) 8%, transparent)
  );
  opacity: 0.72;
}

.lyric-line:disabled {
  opacity: 0.38;
}

.lyric-line--active:disabled {
  opacity: 1;
}
</style>
