<script setup lang="ts">
import { computed } from 'vue'
import { tableGridColumns } from '@/utils/tableGrid'

/**
 * 歌曲表格表头。
 *
 * 与 `Table.vue` 的表体共用 `tableGridColumns` 与完全一致的单元格样式/留白，
 * 保证表头和数据列严格对齐（此前两边各自维护列宽，导致错位）。
 */
const props = defineProps({
  showTrackNumber: {
    type: Boolean,
    default: false,
  },
})

const gridColumns = computed(() => tableGridColumns(props.showTrackNumber))
</script>

<template>
  <div
    class="grid w-full items-center gap-6 p-2 text-left text-sm text-muted-foreground"
    :class="gridColumns"
  >
    <div v-if="showTrackNumber" class="min-w-0 text-center">曲序</div>
    <!-- 封面列占位：与表体封面单元格同宽，否则 auto 列宽度不一致 -->
    <div class="h-10 w-10"></div>
    <div class="min-w-0">标题</div>
    <div class="min-w-0">歌手</div>
    <div class="min-w-0">专辑</div>
    <div class="min-w-0 text-center">喜欢</div>
    <div class="min-w-0">时长</div>
    <div class="min-w-0 text-center">下载</div>
  </div>
</template>
