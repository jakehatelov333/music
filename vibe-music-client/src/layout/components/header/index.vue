<script setup lang="ts">
import { Icon } from '@iconify/vue'
import Avatar from './components/avatar.vue'
import Aside from '@/layout/components/aside/index.vue'
import { globalSearch } from '@/api/system'
import type { Album, GlobalSearchResult, Song } from '@/api/interface'
import default_album from '@/assets/default_album.jpg'

const route = useRoute()
const router = useRouter()
const currentIcon = ref('material-symbols:wb-sunny-outline-rounded')
const theme = themeStore()
import { useDark, useToggle } from '@vueuse/core'

const audio = AudioStore()
const { loadTrack, play } = useAudioPlayer()

const searchText = ref('')
const mobileMenuOpen = ref(false)
const isSearchFocused = ref(false)
const isSearchLoading = ref(false)
const searchResults = ref<GlobalSearchResult>({
  songs: [],
  artists: [],
  albums: [],
  playlists: [],
})
let searchTimer: ReturnType<typeof setTimeout> | undefined
let closeTimer: number | undefined
let searchRequestId = 0

const normalizedKeyword = computed(() => searchText.value.trim())
const showDropdown = computed(
  () => isSearchFocused.value && normalizedKeyword.value.length > 0
)
const hasResults = computed(
  () =>
    searchResults.value.songs.length > 0 ||
    searchResults.value.artists.length > 0 ||
    searchResults.value.albums.length > 0 ||
    searchResults.value.playlists.length > 0
)

const resetSearchResults = () => {
  searchResults.value = { songs: [], artists: [], albums: [], playlists: [] }
}

const executeSearch = async () => {
  const keyword = normalizedKeyword.value
  if (!keyword) {
    resetSearchResults()
    isSearchLoading.value = false
    return
  }

  const requestId = ++searchRequestId
  isSearchLoading.value = true
  try {
    const res = await globalSearch(keyword, 8)
    if (requestId !== searchRequestId) return

    searchResults.value =
      res.code === 0 && res.data
        ? {
            songs: res.data.songs || [],
            artists: res.data.artists || [],
            albums: res.data.albums || [],
            playlists: res.data.playlists || [],
          }
        : { songs: [], artists: [], albums: [], playlists: [] }
  } catch (error) {
    if (requestId === searchRequestId) {
      resetSearchResults()
      console.error('全局搜索失败:', error)
    }
  } finally {
    if (requestId === searchRequestId) {
      isSearchLoading.value = false
    }
  }
}

watch(searchText, () => {
  if (searchTimer) clearTimeout(searchTimer)
  if (!normalizedKeyword.value) {
    searchRequestId++
    resetSearchResults()
    isSearchLoading.value = false
    return
  }
  searchTimer = setTimeout(executeSearch, 250)
})

const closeSearchDropdown = () => {
  isSearchFocused.value = false
}

const handleSearchFocus = () => {
  if (closeTimer) clearTimeout(closeTimer)
  isSearchFocused.value = true
}

const handleSearchBlur = () => {
  if (closeTimer) clearTimeout(closeTimer)
  closeTimer = window.setTimeout(closeSearchDropdown, 150)
}

const goToSearchResults = () => {
  const keyword = normalizedKeyword.value
  if (!keyword) return
  closeSearchDropdown()
  router.push({ path: '/library', query: { query: keyword } })
}

const handleSongClick = async (song: Song) => {
  const tracks = searchResults.value.songs
    .map((item) => ({
      id: item.songId.toString(),
      title: item.songName,
      artist: item.artistName,
      album: item.album,
      cover: item.coverUrl || default_album,
      url: item.audioUrl,
      duration: Number(item.duration) || 0,
      likeStatus: item.likeStatus || 0,
    }))
    .filter((item) => Boolean(item.url))

  const selectedIndex = tracks.findIndex(
    (item) => Number(item.id) === song.songId
  )
  if (selectedIndex < 0) return

  audio.setAudioStore('trackList', tracks)
  audio.setAudioStore('currentSongIndex', selectedIndex)
  closeSearchDropdown()
  await loadTrack()
  play()
}

const handleArtistClick = (artistId: number) => {
  closeSearchDropdown()
  router.push(`/artist/${artistId}`)
}

const handleAlbumClick = (album: Album) => {
  closeSearchDropdown()
  router.push(`/album/${album.albumId}`)
}

const handlePlaylistClick = (playlistId: number) => {
  closeSearchDropdown()
  router.push(`/playlist/${playlistId}`)
}

// 初始化时根据 store 设置图标
watch(
  () => theme.isDark,
  (newValue) => {
    currentIcon.value = newValue
      ? 'mdi:weather-night'
      : 'material-symbols:wb-sunny-outline-rounded'
  },
  { immediate: true }
)

// 赋值到搜索框
watch(
  () => route.query,
  (newValue) => {
    searchText.value = newValue.query ? (newValue.query as string) : ''
  },
  { immediate: true }
)

const isDark = useDark({
  selector: 'html',
  attribute: 'class',
  valueDark: 'dark',
  valueLight: 'light',
})
const toggleDark = useToggle(isDark)

const toggleMode = () => {
  theme.setDark(!isDark.value)
  toggleDark()
}

const handleMobileNavigate = (path: string) => {
  mobileMenuOpen.value = false
  router.push(path)
}
</script>
<template>
  <header
    class="relative z-30 flex shrink-0 items-center gap-2 border-b bg-themeBgColor px-3 py-2 md:gap-3 md:px-4"
  >
    <button
      type="button"
      class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg hover:bg-hoverMenuBg md:hidden"
      aria-label="打开导航菜单"
      @click="mobileMenuOpen = true"
    >
      <Icon icon="mdi:menu" class="text-2xl" />
    </button>
    <button
      type="button"
      class="flex shrink-0 items-center md:w-60"
      aria-label="返回首页"
      @click="router.push('/')"
    >
      <img src="/logo.svg" alt="logo" class="h-10 w-10 md:ml-2" />
      <span
        class="ml-2 hidden items-center justify-center text-2xl font-bold md:flex md:ml-3"
        >Vibe Music</span
      >
    </button>
    <!-- 输入框和头像 -->
    <div class="flex min-w-0 flex-1 items-center gap-2 md:gap-3">
      <div class="relative min-w-0 flex-1 md:mr-6 md:flex-none">
        <Icon
          icon="mdi:magnify"
          class="absolute left-2 top-1/2 transform -translate-y-1/2 text-gray-500 text-xl z-10"
        />
        <input
          v-model="searchText"
          type="text"
          class="mt-0.5 w-full min-w-0 rounded-lg py-2 pl-8 pr-2 text-sm transition-all duration-300 focus:outline-none focus:ring-2 focus:ring-primary md:w-64 md:focus:w-80 search-bg"
          placeholder="搜索歌曲、歌手、专辑、歌单..."
          @focus="handleSearchFocus"
          @input="handleSearchFocus"
          @blur="handleSearchBlur"
          @keyup.enter="goToSearchResults"
        />

        <div
          v-if="showDropdown"
          class="absolute right-0 top-full mt-2 max-h-[70vh] w-[30rem] max-w-[calc(100vw-1.5rem)] overflow-y-auto rounded-xl border border-border bg-popover text-popover-foreground shadow-2xl md:left-0 md:right-auto"
        >
          <div
            v-if="isSearchLoading"
            class="flex items-center justify-center py-10 text-sm text-muted-foreground"
          >
            <Icon icon="mdi:loading" class="mr-2 animate-spin text-lg" />
            正在搜索...
          </div>

          <div
            v-else-if="!hasResults"
            class="px-5 py-10 text-center text-sm text-muted-foreground"
          >
            没有找到与「{{ normalizedKeyword }}」相关的音乐内容
          </div>

          <template v-else>
            <section v-if="searchResults.songs.length" class="py-2">
              <div
                class="px-4 pb-1 text-xs font-semibold tracking-wider text-muted-foreground"
              >
                歌曲
              </div>
              <button
                v-for="song in searchResults.songs.slice(0, 5)"
                :key="`song-${song.songId}`"
                type="button"
                class="flex w-full items-center gap-3 px-4 py-2 text-left transition-colors hover:bg-accent"
                @mousedown.prevent="handleSongClick(song)"
              >
                <img
                  :src="song.coverUrl || default_album"
                  :alt="song.songName"
                  class="h-10 w-10 rounded-md object-cover"
                />
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-medium">{{
                    song.songName
                  }}</span>
                  <span class="block truncate text-xs text-muted-foreground">
                    {{ song.artistName
                    }}<template v-if="song.album"> · {{ song.album }}</template>
                  </span>
                </span>
                <Icon
                  icon="mdi:play-circle-outline"
                  class="text-xl text-muted-foreground"
                />
              </button>
              <button
                type="button"
                class="w-full px-4 py-2 text-left text-sm text-primary hover:bg-accent"
                @mousedown.prevent="goToSearchResults"
              >
                查看全部歌曲 →
              </button>
            </section>

            <section
              v-if="searchResults.artists.length"
              class="border-t border-border py-2"
            >
              <div
                class="px-4 pb-1 text-xs font-semibold tracking-wider text-muted-foreground"
              >
                歌手
              </div>
              <button
                v-for="artist in searchResults.artists.slice(0, 3)"
                :key="`artist-${artist.artistId}`"
                type="button"
                class="flex w-full items-center gap-3 px-4 py-2 text-left transition-colors hover:bg-accent"
                @mousedown.prevent="handleArtistClick(artist.artistId)"
              >
                <img
                  :src="artist.avatar"
                  :alt="artist.artistName"
                  class="h-10 w-10 rounded-full bg-muted object-cover"
                />
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-medium">{{
                    artist.artistName
                  }}</span>
                  <span class="block text-xs text-muted-foreground"
                    >查看歌手详情</span
                  >
                </span>
                <Icon
                  icon="mdi:chevron-right"
                  class="text-xl text-muted-foreground"
                />
              </button>
            </section>

            <section
              v-if="searchResults.albums.length"
              class="border-t border-border py-2"
            >
              <div
                class="px-4 pb-1 text-xs font-semibold tracking-wider text-muted-foreground"
              >
                专辑
              </div>
              <button
                v-for="album in searchResults.albums.slice(0, 3)"
                :key="`album-${album.albumId}`"
                type="button"
                class="flex w-full items-center gap-3 px-4 py-2 text-left transition-colors hover:bg-accent"
                @mousedown.prevent="handleAlbumClick(album)"
              >
                <img
                  :src="album.coverUrl || default_album"
                  :alt="album.albumName"
                  class="h-10 w-10 rounded-md bg-muted object-cover"
                />
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-medium">{{
                    album.albumName
                  }}</span>
                  <span class="block truncate text-xs text-muted-foreground">
                    {{ album.artistName || '未知歌手' }}
                  </span>
                </span>
                <Icon
                  icon="mdi:chevron-right"
                  class="text-xl text-muted-foreground"
                />
              </button>
            </section>

            <section
              v-if="searchResults.playlists.length"
              class="border-t border-border py-2"
            >
              <div
                class="px-4 pb-1 text-xs font-semibold tracking-wider text-muted-foreground"
              >
                歌单
              </div>
              <button
                v-for="playlist in searchResults.playlists.slice(0, 3)"
                :key="`playlist-${playlist.playlistId}`"
                type="button"
                class="flex w-full items-center gap-3 px-4 py-2 text-left transition-colors hover:bg-accent"
                @mousedown.prevent="handlePlaylistClick(playlist.playlistId)"
              >
                <img
                  :src="playlist.coverUrl"
                  :alt="playlist.title"
                  class="h-10 w-10 rounded-md bg-muted object-cover"
                />
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-medium">{{
                    playlist.title
                  }}</span>
                  <span class="block text-xs text-muted-foreground"
                    >查看歌单</span
                  >
                </span>
                <Icon
                  icon="mdi:chevron-right"
                  class="text-xl text-muted-foreground"
                />
              </button>
            </section>
          </template>
        </div>
      </div>
      <button
        type="button"
        class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-lg hover:bg-hoverMenuBg"
        aria-label="切换深浅色"
        @click="toggleMode"
      >
        <Icon class="text-xl" :icon="currentIcon" />
      </button>
    </div>
    <div class="ml-auto flex shrink-0 items-center gap-3"><Avatar /></div>
  </header>

  <el-drawer
    v-model="mobileMenuOpen"
    direction="ltr"
    size="min(82vw, 300px)"
    :with-header="false"
    class="mobile-nav-drawer"
  >
    <Aside mobile @navigate="handleMobileNavigate" />
  </el-drawer>
</template>

<style scoped>
.search-bg {
  background-color: #e3e3e3;
}

:deep(.mobile-nav-drawer .el-drawer__body) {
  padding: 0;
}
</style>
