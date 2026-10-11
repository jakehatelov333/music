<script setup lang="ts">
import { getArtistDetail } from '@/api/system'
import Table from '@/components/Table.vue'
import defaultAlbum from '@/assets/default_album.jpg'
import { useArtistStore } from '@/stores/modules/artist'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'

interface ArtistDetailResponse {
    artistId: number
    artistName: string
    avatar: string
    birth: string
    area: string
    introduction: string
    songs: any[]
    albums: any[]
}

const route = useRoute()
const artistStore = useArtistStore()
// 歌手数据
const artistInfo = computed(() => artistStore.artistInfo)

const fetchArtistDetail = async () => {
    const id = route.params.id
    const numericId = parseInt(id.toString())

    try {
        artistStore.setArtistInfo(null) // 清空之前的数据
        const res = await getArtistDetail(numericId)

        if (res.code === 0 && res.data) {
            const artistData = res.data as ArtistDetailResponse
            artistStore.setArtistInfo({
                artistId: artistData.artistId,
                artistName: artistData.artistName || '未知歌手',
                avatar: artistData.avatar || '',
                birth: artistData.birth || '',
                area: artistData.area || '未知',
                introduction: artistData.introduction || '暂无简介',
                songs: artistData.songs || [],
                albums: artistData.albums || []
            })
        } else {
            ElMessage.error(res.message || '获取歌手信息失败')
        }
    } catch (error) {
        console.error('获取歌手详情失败:', error)
        ElMessage.error('获取歌手信息失败，请稍后重试')
    }
}

watch(
    () => route.params.id,
    () => {
        fetchArtistDetail()
    },
    { immediate: true }
)

// 格式化生日
const formatBirth = (birth: string) => {
    if (!birth) return ''
    return new Date(birth).toLocaleDateString()
}
</script>

<template>
    <div class="container mx-auto py-10 px-5 h-full flex-1 flex flex-col overflow-y-auto">
        <!-- 歌手详情 -->
        <div class="flex flex-col lg:flex-row items-center gap-8">
            <div class="w-48 h-48 rounded-full overflow-hidden bg-gray-200">
                <img :src="artistInfo?.avatar" :alt="artistInfo?.artistName" class="w-full h-full object-cover" />
            </div>
            <div class="text-center lg:text-left flex-1">
                <h1 class="text-3xl font-semibold text-foreground">
                    {{ artistInfo?.artistName }}
                </h1>
                <div class="mt-4 space-y-2 text-sm text-muted-foreground">
                    <p v-if="artistInfo?.birth">生日：{{ formatBirth(artistInfo.birth) }}</p>
                    <p v-if="artistInfo?.area">地区：{{ artistInfo.area }}</p>
                    <p v-if="artistInfo?.introduction" class="mt-2 line-clamp-4">简介：{{ artistInfo.introduction }}
                    </p>
                </div>
            </div>
        </div>

        <!-- 歌曲列表 -->
        <div class="mt-12 flex shrink-0 flex-col">
            <h2 class="text-2xl font-semibold text-foreground mb-6">所有歌曲</h2>
            <div class="flex max-h-[58vh] w-full overflow-y-auto">
                <Table :data="artistInfo?.songs" />
            </div>
        </div>

        <!-- 专辑列表 -->
        <div v-if="artistInfo?.albums?.length" class="mt-12 pb-4">
            <h2 class="text-2xl font-semibold text-foreground mb-6">专辑</h2>
            <div class="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
                <button
                    v-for="album in artistInfo.albums"
                    :key="album.albumId"
                    type="button"
                    class="min-w-0 rounded-xl border bg-card p-2 text-left shadow-sm transition duration-300 hover:-translate-y-1 hover:shadow-lg"
                    @click="$router.push(`/album/${album.albumId}`)"
                >
                    <el-image
                        lazy
                        :src="album.coverUrl || defaultAlbum"
                        :alt="album.albumName"
                        fit="cover"
                        class="aspect-square w-full rounded-lg"
                    />
                    <h3 class="mt-3 line-clamp-2 min-h-[2.5rem] text-sm font-semibold">
                        {{ album.albumName }}
                    </h3>
                    <p class="mt-1 flex items-center justify-between gap-2 text-xs text-muted-foreground">
                        <span>{{ album.releaseDate?.slice(0, 4) || '未知年份' }}</span>
                        <span>{{ album.songCount || 0 }} 首</span>
                    </p>
                </button>
            </div>
        </div>
    </div>
</template>
