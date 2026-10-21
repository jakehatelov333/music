<script setup lang="ts">
import { getAlbumDetail } from '@/api/system'
import type { AlbumDetail, Song } from '@/api/interface'
import defaultAlbum from '@/assets/default_album.jpg'
import Table from '@/components/Table.vue'
import TableHeader from '@/components/TableHeader.vue'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const audio = AudioStore()
const { loadTrack, play: playAudio } = useAudioPlayer()

const album = ref<AlbumDetail | null>(null)
const loading = ref(false)
const songs = computed(() => album.value?.songs || [])
let requestId = 0

const fetchAlbumDetail = async () => {
  const albumId = Number(route.params.id)
  if (!albumId) return

  const currentRequestId = ++requestId
  loading.value = true
  album.value = null
  try {
    const res = await getAlbumDetail(albumId)
    if (currentRequestId !== requestId) return

    if (res.code === 0 && res.data) {
      album.value = res.data
    } else {
      ElMessage.error(res.message || '获取专辑详情失败')
    }
  } catch (error) {
    if (currentRequestId === requestId) {
      ElMessage.error('获取专辑详情失败，请稍后重试')
    }
  } finally {
    if (currentRequestId === requestId) {
      loading.value = false
    }
  }
}

const convertToTrack = (song: Song) => ({
  id: song.songId.toString(),
  title: song.songName,
  artist: song.artistName,
  album: song.album || album.value?.albumName || '',
  cover: song.coverUrl || album.value?.coverUrl || defaultAlbum,
  url: song.audioUrl,
  duration: parseDuration(song.duration),
  likeStatus: song.likeStatus || 0,
})

const parseDuration = (duration: string | number | null | undefined) => {
  if (typeof duration === 'number') return duration
  if (!duration) return 0
  const numericDuration = Number(duration)
  if (Number.isFinite(numericDuration)) return numericDuration
  const parts = duration.split(':').map(Number)
  if (parts.some((part) => !Number.isFinite(part))) return 0
  return parts.reduce((total, part) => total * 60 + part, 0)
}

const handlePlayAll = async () => {
  if (!songs.value.length) {
    ElMessage.info('该专辑暂无歌曲')
    return
  }

  audio.setAudioStore(
    'trackList',
    songs.value.filter((song) => song.audioUrl).map(convertToTrack)
  )
  audio.setAudioStore('currentSongIndex', 0)
  await loadTrack()
  playAudio()
}

const handlePlaySong = async (song: Song) => {
  const playableSongs = songs.value.filter((item) => item.audioUrl)
  const selectedIndex = playableSongs.findIndex((item) => item.songId === song.songId)
  if (selectedIndex < 0) {
    ElMessage.warning('该歌曲暂不可播放')
    return
  }
  audio.setAudioStore('trackList', playableSongs.map(convertToTrack))
  audio.setAudioStore('currentSongIndex', selectedIndex)
  await loadTrack()
  playAudio()
}

const formatReleaseDate = (releaseDate: string | null | undefined) => {
  return releaseDate || '暂无发行日期'
}

watch(
  () => route.params.id,
  () => fetchAlbumDetail(),
  { immediate: true }
)
</script>

<template>
  <div class="album-detail-page min-h-full min-w-0 bg-background px-4 pb-5 md:px-6 lg:px-8">
    <div v-if="loading" class="flex flex-col gap-8 lg:flex-row">
      <el-skeleton-item variant="image" class="aspect-square w-full rounded-2xl sm:w-56 lg:w-64" />
      <div class="flex-1 space-y-4">
        <el-skeleton :rows="5" animated />
      </div>
    </div>

    <template v-else-if="album">
      <section class="flex shrink-0 flex-col gap-6 lg:flex-row lg:items-end">
        <div class="w-full shrink-0 overflow-hidden rounded-2xl bg-muted shadow-xl sm:w-56 lg:w-64">
          <el-image
            :src="album.coverUrl || defaultAlbum"
            :alt="album.albumName"
            fit="cover"
            class="aspect-square w-full"
          />
        </div>

        <div class="min-w-0 flex-1 pb-1">
          <p class="text-xs font-semibold uppercase tracking-[0.24em] text-muted-foreground">
            Album
          </p>
          <h1 class="mt-2 break-words text-3xl font-bold leading-tight text-foreground lg:text-4xl">
            {{ album.albumName }}
          </h1>
          <button
            type="button"
            class="mt-3 text-sm font-medium text-primary transition hover:underline"
            @click="router.push(`/artist/${album.artistId}`)"
          >
            {{ album.artistName || '未知歌手' }}
          </button>

          <div class="mt-5 flex flex-wrap items-center gap-x-5 gap-y-2 text-sm text-muted-foreground">
            <span>{{ formatReleaseDate(album.releaseDate) }}</span>
            <span>{{ album.songCount || songs.length }} 首歌曲</span>
          </div>

          <p class="mt-5 max-w-3xl whitespace-pre-line text-sm leading-6 text-muted-foreground">
            {{ album.description || '暂无专辑简介' }}
          </p>

          <button
            type="button"
            class="mt-6 inline-flex h-11 items-center justify-center gap-2 rounded-full bg-primary px-6 text-sm font-medium text-white shadow-lg shadow-primary/20 transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-50"
            :disabled="!songs.length"
            @click="handlePlayAll"
          >
            <icon-tabler:player-play-filled class="text-lg" />
            播放全部
          </button>
        </div>
      </section>

      <section class="mt-8 pb-4">
        <div class="album-songs-sticky -mx-4 border-b px-4 md:-mx-6 md:px-6 lg:-mx-8 lg:px-8">
          <div class="flex items-end justify-between gap-4 pb-3 pt-3">
            <div>
              <h2 class="text-xl font-semibold text-foreground">专辑歌曲</h2>
              <p class="mt-1 text-xs text-muted-foreground">按唱片与曲序排列</p>
            </div>
            <span class="text-sm text-muted-foreground">{{ songs.length }} 首</span>
          </div>

          <!-- 与表体共用 TableHeader，保证列标题与数据严格对齐。
               show-album=false：这一页所有歌都属于当前专辑，再列一遍专辑名是纯噪音。 -->
          <TableHeader class="hidden md:grid" :show-track-number="true" :show-album="false" />
        </div>

        <div v-if="songs.length" class="min-w-0 w-full">
          <Table
            :data="songs"
            show-track-number
            :show-album="false"
            hide-header
            page-scroll
            @song-play="handlePlaySong"
          />
        </div>
        <div v-else class="flex min-h-64 items-center justify-center rounded-2xl border border-dashed">
          <el-empty description="该专辑暂无歌曲" />
        </div>
      </section>
    </template>

    <div v-else class="flex flex-1 items-center justify-center">
      <el-empty description="专辑不存在或已下架" />
    </div>
  </div>
</template>

<style scoped>
.album-detail-page {
  padding-top: calc(var(--app-header-height, 0px) + 1.25rem);
}

.album-songs-sticky {
  position: sticky;
  top: var(--app-header-height, 0px);
  z-index: 20;
  background: var(--el-bg-color);
}
</style>
