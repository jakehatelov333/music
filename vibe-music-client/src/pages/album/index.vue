<script setup lang="ts">
import { getAlbumList } from '@/api/system'
import type { Album } from '@/api/interface'
import defaultAlbum from '@/assets/default_album.jpg'
import { ElNotification } from 'element-plus'

const router = useRouter()
const albumList = ref<Album[]>([])
const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(12)
const total = ref(0)
const loading = ref(false)

const state = reactive({
  size: 'default',
  disabled: false,
  background: false,
  layout: 'total, sizes, prev, pager, next',
  total: 0,
  pageSizes: [12, 24, 36, 48],
})

// 关键词检索一律走后端（fetchAlbums 传 keyword），这里不再保留本地过滤副本。

const fetchAlbums = async () => {
  loading.value = true
  try {
    const res = await getAlbumList({
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      albumName: null,
      keyword: searchKeyword.value.trim() || null,
    })

    if (res.code === 0 && res.data) {
      const items = (res.data.items || []) as Album[]
      albumList.value = items
      total.value = res.data.total || 0
      state.total = total.value
    } else {
      albumList.value = []
      total.value = 0
      state.total = 0
    }
  } catch (error) {
    albumList.value = []
    total.value = 0
    state.total = 0
    ElNotification({
      type: 'error',
      message: '获取专辑列表失败',
      duration: 2000,
    })
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  fetchAlbums()
}

const handleReset = () => {
  searchKeyword.value = ''
  currentPage.value = 1
  fetchAlbums()
}

const handleSizeChange = () => {
  currentPage.value = 1
  fetchAlbums()
}

const formatReleaseYear = (releaseDate: string | null) => {
  return releaseDate ? releaseDate.slice(0, 4) : '未知年份'
}

watch(searchKeyword, () => {
  currentPage.value = 1
  fetchAlbums()
})

onMounted(fetchAlbums)
</script>

<template>
  <div class="flex h-full min-h-0 min-w-0 flex-col overflow-hidden bg-background px-4 py-2">
    <div class="shrink-0 py-4">
      <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p class="text-xs font-semibold uppercase tracking-[0.22em] text-muted-foreground">
            Albums
          </p>
          <h1 class="mt-1 text-2xl font-semibold text-foreground">专辑</h1>
        </div>

        <div class="relative w-full sm:w-80">
          <icon-mdi:magnify
            class="absolute left-3 top-1/2 -translate-y-1/2 text-lg text-muted-foreground"
          />
          <input
            v-model="searchKeyword"
            type="search"
            class="flex h-10 w-full rounded-lg border border-input bg-background py-2 pl-10 pr-10 text-sm outline-none transition focus:ring-2 focus:ring-primary"
            placeholder="搜索专辑名称..."
            @keyup.enter="handleSearch"
          />
          <button
            v-if="searchKeyword"
            type="button"
            class="absolute right-2 top-1/2 -translate-y-1/2 rounded-full p-1 text-muted-foreground transition hover:bg-hoverMenuBg hover:text-foreground"
            aria-label="清空搜索"
            @click="handleReset"
          >
            <icon-material-symbols:close-rounded />
          </button>
        </div>
      </div>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
      <div v-if="loading" class="grid grid-cols-2 gap-4 md:grid-cols-3 md:gap-6 xl:grid-cols-5 2xl:grid-cols-6">
        <div v-for="index in pageSize" :key="index" class="space-y-3">
          <el-skeleton-item variant="image" class="aspect-square w-full rounded-xl" />
          <el-skeleton :rows="2" animated />
        </div>
      </div>

      <div
        v-else-if="albumList.length"
        class="grid grid-cols-2 gap-x-4 gap-y-6 md:grid-cols-3 md:gap-x-6 xl:grid-cols-5 2xl:grid-cols-6"
      >
        <button
          v-for="album in albumList"
          :key="album.albumId"
          type="button"
          class="group min-w-0 rounded-xl border bg-card p-2 text-left text-card-foreground shadow-sm transition duration-300 hover:-translate-y-1 hover:shadow-xl"
          @click="router.push(`/album/${album.albumId}`)"
        >
          <div class="relative overflow-hidden rounded-lg bg-muted">
            <el-image
              lazy
              :src="album.coverUrl || defaultAlbum"
              :alt="album.albumName"
              fit="cover"
              class="aspect-square w-full"
            />
            <div
              class="absolute inset-0 flex items-center justify-center bg-black/35 opacity-0 transition-opacity duration-300 group-hover:opacity-100"
            >
              <icon-tabler:player-play-filled class="text-3xl text-white" />
            </div>
          </div>

          <div class="px-1 pb-1 pt-3">
            <h2 class="line-clamp-2 min-h-[2.5rem] text-sm font-semibold leading-5" :title="album.albumName">
              {{ album.albumName }}
            </h2>
            <p class="mt-1 truncate text-xs text-muted-foreground" :title="album.artistName || '未知歌手'">
              {{ album.artistName || '未知歌手' }}
            </p>
            <div class="mt-2 flex items-center justify-between gap-2 text-xs text-muted-foreground">
              <span>{{ formatReleaseYear(album.releaseDate) }}</span>
              <span>{{ album.songCount || 0 }} 首</span>
            </div>
          </div>
        </button>
      </div>

      <el-empty v-else description="暂无专辑" />
    </div>

    <nav class="mx-auto flex w-full shrink-0 flex-wrap justify-center pt-3">
      <el-pagination
        v-model:page-size="pageSize"
        v-model:current-page="currentPage"
        v-bind="state"
        @size-change="handleSizeChange"
        @current-change="fetchAlbums"
      />
    </nav>
  </div>
</template>
