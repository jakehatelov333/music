<script setup lang="ts">
const route = useRoute()
const mainRef = ref<HTMLElement | null>(null)
const isAlbumDetail = computed(() => route.path.startsWith('/album/'))
let headerResizeObserver: ResizeObserver | undefined

const syncHeaderHeight = () => {
  const shell = mainRef.value?.closest('.app-shell')
  const header = shell?.querySelector('header')
  if (!mainRef.value || !header) return

  const headerHeight = header.getBoundingClientRect().height
  mainRef.value.style.setProperty('--app-header-height', `${headerHeight}px`)
}

onMounted(() => {
  const header = mainRef.value?.closest('.app-shell')?.querySelector('header')
  syncHeaderHeight()

  if (header && 'ResizeObserver' in window) {
    headerResizeObserver = new ResizeObserver(syncHeaderHeight)
    headerResizeObserver.observe(header)
  }
  window.addEventListener('resize', syncHeaderHeight)
})

onBeforeUnmount(() => {
  headerResizeObserver?.disconnect()
  window.removeEventListener('resize', syncHeaderHeight)
})
</script>

<template>
  <main
    ref="mainRef"
    class="min-h-0 min-w-0 flex-1 overflow-x-hidden overflow-y-auto bg-themeBgColor"
    :class="{ 'album-main-scroll': isAlbumDetail }"
  >
    <router-view v-slot="{ Component }">
      <keep-alive>
        <component :is="Component" />
      </keep-alive>
    </router-view>
  </main>
</template>

<style scoped>
.album-main-scroll {
  margin-top: calc(-1 * var(--app-header-height, 0px));
}
</style>
