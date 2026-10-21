<script setup lang="ts">
import { getAllSongs, globalSearch } from '@/api/system'
import { useLibraryStore } from '@/stores/modules/library'

const route = useRoute()
const libraryStore = useLibraryStore()

const props = defineProps({
  selected: {
    type: String,
    default: '1',
  },
})
const tableData = computed(() => libraryStore.tableData)

const currentPage = ref(1) // 当前页
const pageSize = ref(20) // 每页显示的数量

const state = reactive({
  size: 'default',
  disabled: false,
  background: false,
  layout: 'total, sizes, prev, pager, next, jumper',
  total: 0,
  pageSizes: [20, 30, 50],
})

// 监听分页变化
const handleSizeChange = () => {
  getSongs()
}
// 监听当前页变化
const handleCurrentChange = () => {
  getSongs()
}

const getSongs = async () => {
  libraryStore.setTableData(null)
  const keyword = ((route.query.query as string) || '').trim()

  // 顶部搜索使用后端统一搜索，歌曲名、歌手名、专辑名均可命中。
  if (keyword) {
    const res = await globalSearch(keyword, 50)
    if (res.code === 0 && res.data) {
      const items = res.data.songs || []
      libraryStore.setTableData({ items, total: items.length })
      state.total = items.length
    } else {
      state.total = 0
    }
    return
  }

  const res = await getAllSongs({
    pageNum: currentPage.value,
    pageSize: pageSize.value,
    songName: '',
    artistName: '',
    album: '',
  })
  if (res.code === 0 && res.data) {
    libraryStore.setTableData(res.data)
    state.total = res.data.total || 0
  } else {
    state.total = 0
  }
}

watch(
  () => [route.query.query, props.selected],
  (val) => {
    if (!val[1] || val[1] != '1') return
    currentPage.value = 1
    getSongs()
  },
  {
    immediate: true,
  }
)
</script>

<template>
  <div class="flex h-full min-h-0 flex-1 flex-col overflow-hidden">
    <Table :data="tableData?.items" class="flex-1 overflow-x-hidden" />
    <nav class="mx-auto flex w-full justify-center mt-3">
      <el-pagination
        v-model:page-size="pageSize"
        v-model:currentPage="currentPage"
        v-bind="state"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
        class="mb-3"
      />
    </nav>
  </div>
</template>
