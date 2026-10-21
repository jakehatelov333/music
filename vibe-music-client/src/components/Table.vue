<script setup lang="ts">
import { Song } from '@/api/interface'
import { PropType, watch } from 'vue'
import { formatMillisecondsToTime } from '@/utils'
import { tableGridColumns } from '@/utils/tableGrid'
import type { SongTableVariant } from '@/utils/tableGrid'
import TableHeader from '@/components/TableHeader.vue'
import default_album from '@/assets/default_album.jpg'
import { collectSong, cancelCollectSong } from '@/api/system'
import { ElMessage } from 'element-plus'
import { UserStore } from '@/stores/modules/user'
import { downloadMedia } from '@/utils/download'

const audio = AudioStore()
const userStore = UserStore()
const { loadTrack, play } = useAudioPlayer()

const props = defineProps({
  data: {
    type: Array as PropType<Song[]>,
    default: () => [],
  },
  showTrackNumber: {
    type: Boolean,
    default: false,
  },
  hideHeader: {
    type: Boolean,
    default: false,
  },
  pageScroll: {
    type: Boolean,
    default: false,
  },
  /**
   * 表格形态。
   * - `library`：曲库/歌手/专辑/喜欢/公开歌单，末尾是「喜欢 / 时长 / 下载」
   * - `owned`：我的歌单，末尾是「时长 / 移出」
   *
   * 两种形态共用同一套行样式与栅格节奏，因此「我的歌单」看起来和曲库是同一张表。
   */
  variant: {
    type: String as PropType<SongTableVariant>,
    default: 'library',
  },
  /**
   * 点击某一行时写入播放器的队列。缺省用 `data` 本身；
   * 「我的歌单」分页显示 20 首，但要整张歌单入队，因此显式传入完整列表。
   */
  playQueue: {
    type: Array as PropType<Song[]>,
    default: null,
  },
  /**
   * 是否显示「专辑」列。专辑详情页传 `false` ——
   * 那一页所有歌都属于同一张专辑，再列一遍专辑名是纯噪音。
   * 必须与 `TableHeader` 传同一个值，否则列宽错位。
   */
  showAlbum: {
    type: Boolean,
    default: true,
  },
})

const emit = defineEmits<{
  (event: 'song-play', song: Song): void
  (event: 'remove', song: Song): void
}>()

const gridColumns = computed(() =>
  tableGridColumns(props.showTrackNumber, props.variant, props.showAlbum)
)

// 监听数据变化，更新当前页面的歌曲列表
watch(() => props.data, (newData) => {
  audio.setCurrentPageSongs(newData)
}, { immediate: true })

// 转换歌曲实体
const convertToTrackModel = (song: Song) => {
  // console.log('原始歌曲数据:', song)
  if (!song.songId || !song.songName || !song.audioUrl) {
    console.error('歌曲数据不完整:', song)
    return null
  }
  return {
    id: song.songId.toString(),
    title: song.songName,
    artist: song.artistName,
    album: song.album,
    cover: song.coverUrl || default_album,
    url: song.audioUrl,
    duration: Number(song.duration) || 0,
    likeStatus: song.likeStatus || 0,
  }
}

// 播放音乐
const handlePlay = async (row: Song) => {
  if (props.showTrackNumber) {
    emit('song-play', row)
    return
  }
  // 入队的是「要播的整份列表」，显示的可能只是它的一页
  const queue = props.playQueue ?? props.data
  // 先将所有表格数据转换为 trackModel
  const allTracks = queue
    .map(song => convertToTrackModel(song))
    .filter(track => track !== null)

  // 找到当前选中歌曲的索引
  const selectedIndex = queue.findIndex(song => song.songId === row.songId)

  // 清空现有播放列表并添加所有歌曲
  audio.setAudioStore('trackList', allTracks)
  // 设置当前播放索引为选中的歌曲
  audio.setAudioStore('currentSongIndex', Math.max(0, selectedIndex))

  // 加载并播放选中的歌曲
  await loadTrack()
  play()
}

// 更新所有相同歌曲的喜欢状态
const updateAllSongLikeStatus = (songId: number, status: number) => {
  // 更新播放列表中的状态
  audio.trackList.forEach(track => {
    if (Number(track.id) === songId) {
      track.likeStatus = status
    }
  })

  // 更新当前页面的歌曲列表状态
  if (audio.currentPageSongs) {
    audio.currentPageSongs.forEach(song => {
      if (song.songId === songId) {
        song.likeStatus = status
      }
    })
  }

  // 更新原始数据
  if (props.data) {
    const song = props.data.find(song => song.songId === songId)
    if (song) {
      song.likeStatus = status
    }
  }
}

// 处理喜欢/取消喜欢
const handleLike = async (row: Song, e: Event) => {
  e.stopPropagation() // 阻止事件冒泡
  
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }

  try {
    if (row.likeStatus === 0) {
      // 收藏歌曲
      const res = await collectSong(row.songId)
      if (res.code === 0) {
        updateAllSongLikeStatus(row.songId, 1)
        ElMessage.success('已添加到我的喜欢')
      } else {
        ElMessage.error(res.message || '添加到我的喜欢失败')
      }
    } else {
      // 取消收藏
      const res = await cancelCollectSong(row.songId)
      if (res.code === 0) {
        updateAllSongLikeStatus(row.songId, 0)
        ElMessage.success('已取消喜欢')
      } else {
        ElMessage.error(res.message || '取消喜欢失败')
      }
    }
  } catch (error: any) {
    ElMessage.error(error.message || '操作失败')
  }
}

const downLoadMusic = async (row: Song, e: Event) => {
  e.stopPropagation() // 阻止事件冒泡
  try {
    await downloadMedia(row.audioUrl, `${row.songName} - ${row.artistName}.mp3`)
    ElMessage.success('已开始下载')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '下载失败')
  }
}

// 判断是否是当前播放的歌曲
const isCurrentPlaying = (songId: number) => {
  const currentTrack = audio.trackList[audio.currentSongIndex]
  return currentTrack && Number(currentTrack.id) === songId
}
</script>

<template>
  <el-table :data="data" style="
      --el-table-border: none;
      --el-table-border-color: none;
      --el-table-tr-bg-color: none;
      --el-table-header-bg-color: none;
      --el-table-row-hover-bg-color: transparent;
    " :show-header="!hideHeader" :class="[
      pageScroll ? 'album-table-page-scroll' : '!h-full',
      '!rounded-lg transition duration-300',
    ]">
    <el-table-column>
      <template #header>
        <TableHeader :show-track-number="showTrackNumber" :variant="variant" />
      </template>
      <template #default="{ row }">
        <div
          class="grid w-full items-center gap-3 rounded-2xl p-2 transition duration-300 group md:gap-6"
          :class="[
            gridColumns,
            isCurrentPlaying(row.songId) ? 'bg-[hsl(var(--hover-menu-bg))]' : 'hover:bg-[hsl(var(--hover-menu-bg))]',
            'cursor-pointer'
          ]"
          @click="handlePlay(row)">
          <div v-if="showTrackNumber" class="min-w-0 text-center text-xs text-muted-foreground md:text-sm">
            {{ row.trackNumber ?? '-' }}
          </div>
          <!-- 封面：必须始终渲染占位，否则无封面的行会少一列导致整行错位 -->
          <div class="w-10 h-10 relative shrink-0">
            <el-image :src="row.coverUrl || default_album" fit="cover" lazy :alt="row.songName" class="w-full h-full rounded-md" />
            <!-- Play 按钮，使用 group-hover 控制透明度 -->
            <div
              class="absolute inset-0 flex items-center justify-center text-white opacity-0 transition-opacity duration-300 z-10 group-hover:opacity-100 group-hover:bg-black/50 rounded-md">
              <icon-tabler:player-play-filled class="text-lg" />
            </div>
          </div>

          <!-- 移动端把歌手放在标题下方，桌面端仍是独立列 -->
          <div class="min-w-0 text-left">
            <div class="line-clamp-1">{{ row.songName }}</div>
            <div class="mt-0.5 line-clamp-1 text-xs text-muted-foreground md:hidden">
              {{ row.artistName }}
            </div>
          </div>

          <!--
            歌手：仿 QQ 音乐 —— 悬停变绿、点击进入该歌手主页。
            归属用结构化 artists（带真实 artistId）；老接口没有该字段时退化为纯文本展示串。
            @click.stop 阻止冒泡，否则点歌手名会同时触发行点击（开始播放）。
          -->
          <div class="hidden min-w-0 text-left md:block">
            <div class="line-clamp-1">
              <template v-if="row.artists?.length">
                <template v-for="(artist, index) in row.artists" :key="artist.artistId">
                  <span v-if="index > 0" class="text-muted-foreground"> / </span>
                  <RouterLink
                    class="meta-link"
                    :to="`/artist/${artist.artistId}`"
                    :title="`进入 ${artist.artistName} 的主页`"
                    @click.stop
                  >{{ artist.artistName }}</RouterLink>
                </template>
              </template>
              <span v-else>{{ row.artistName }}</span>
            </div>
          </div>

          <!--
            专辑：与歌手名同一套交互（悬停变绿、点击进入专辑页）。
            没有 albumId 的歌曲（单曲等）退化为纯文本，不做成「点了没反应」的假链接。
          -->
          <div v-if="showAlbum" class="hidden min-w-0 text-left md:block">
            <div class="line-clamp-1">
              <RouterLink
                v-if="row.albumId"
                class="meta-link"
                :to="`/album/${row.albumId}`"
                :title="`进入《${row.album}》专辑页`"
                @click.stop
              >{{ row.album }}</RouterLink>
              <span v-else>{{ row.album }}</span>
            </div>
          </div>

          <template v-if="variant === 'library'">
            <!-- 喜欢 -->
            <div class="min-w-0 flex items-center justify-center">
              <el-button text circle @click="handleLike(row, $event)">
                <icon-mdi:cards-heart-outline v-if="!userStore.isLoggedIn || row.likeStatus === 0" class="text-lg" />
                <icon-mdi:cards-heart v-else class="text-lg text-red-500" />
              </el-button>
            </div>

            <!-- 时长 -->
            <div class="hidden min-w-0 text-left md:block">
              <span>{{ formatMillisecondsToTime(Number(row.duration) * 1000) }}</span>
            </div>

            <!-- 下载 -->
            <div class="min-w-0 flex items-center justify-center">
              <el-button text circle @click.stop="downLoadMusic(row, $event)">
                <icon-material-symbols:download class="text-lg" />
              </el-button>
            </div>
          </template>

          <template v-else>
            <!-- 时长 -->
            <div class="hidden min-w-0 text-left md:block">
              <span>{{ formatMillisecondsToTime(Number(row.duration) * 1000) }}</span>
            </div>

            <!-- 移出歌单：默认低对比度，悬停才转红，避免整列刺眼 -->
            <div class="min-w-0 flex items-center justify-center">
              <el-button
                text
                circle
                class="row-remove"
                :aria-label="`将《${row.songName}》移出歌单`"
                :title="`将《${row.songName}》移出歌单`"
                @click.stop="emit('remove', row)"
              >
                <icon-material-symbols:delete-outline class="text-lg" />
              </el-button>
            </div>
          </template>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
:deep(.el-table__row) {
  background: transparent !important;
}

:deep(.el-table__row:hover) td {
  background: transparent !important;
}

:deep(.el-table__cell) {
  padding: 0 !important;
}

/*
 * 歌手名 / 专辑名共用的可点击样式：仿 QQ 音乐 —— 默认继承行文字色，
 * 悬停或键盘聚焦时转为主题绿，提示「这里可以点进去」，但不加下划线（与 QQ 音乐一致）。
 */
.meta-link {
  color: inherit;
  border-radius: 2px;
  transition: color 0.15s ease;
}

.meta-link:hover,
.meta-link:focus-visible {
  color: var(--link-hover);
}

.meta-link:focus-visible {
  outline: 2px solid var(--link-hover);
  outline-offset: 1px;
}

/* 「移出歌单」按钮：需要压过 element-plus 的 .el-button.is-text:hover 底色。
   悬停红同样不用 --destructive —— 它在暗色主题里是深红背景色，做文字色会看不清。 */
.row-remove.el-button {
  color: hsl(var(--muted-foreground)) !important;
}

.row-remove.el-button:hover {
  color: #ef4444 !important;
  background-color: rgb(239 68 68 / 0.12) !important;
}

@media (max-width: 767px) {
  :deep(.el-button) {
    min-width: 40px;
    min-height: 40px;
  }
}

:deep(.el-table.album-table-page-scroll),
:deep(.el-table.album-table-page-scroll .el-table__inner-wrapper),
:deep(.el-table.album-table-page-scroll .el-table__body-wrapper),
:deep(.el-table.album-table-page-scroll .el-table__body-wrapper .el-scrollbar),
:deep(.el-table.album-table-page-scroll .el-table__body-wrapper .el-scrollbar__wrap) {
  height: auto !important;
}

:deep(.el-table.album-table-page-scroll),
:deep(.el-table.album-table-page-scroll .el-table__body-wrapper),
:deep(.el-table.album-table-page-scroll .el-table__body-wrapper .el-scrollbar),
:deep(.el-table.album-table-page-scroll .el-table__body-wrapper .el-scrollbar__wrap) {
  overflow: visible !important;
}
</style>
