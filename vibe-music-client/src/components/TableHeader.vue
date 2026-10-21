<script setup lang="ts">
import { computed } from 'vue'
import { tableGridColumns } from '@/utils/tableGrid'
import type { SongTableVariant } from '@/utils/tableGrid'

/**
 * 歌曲表格表头。
 *
 * 与 `Table.vue` 的表体共用 `tableGridColumns` 与完全一致的单元格样式/留白，
 * 保证表头和数据列严格对齐（此前两边各自维护列宽，导致错位）。
 *
 * `variant` / `showAlbum` 必须与表体传同一个值，否则列数与列宽会对不上。
 */
const props = defineProps({
  showTrackNumber: {
    type: Boolean,
    default: false,
  },
  /** 与 `Table.vue` 的 variant 保持一致：library 用「喜欢/下载」，owned 用「操作」 */
  variant: {
    type: String as () => SongTableVariant,
    default: 'library',
  },
  /** 是否显示「专辑」列。专辑详情页传 false（所有歌同属一张专辑，列出来是噪音） */
  showAlbum: {
    type: Boolean,
    default: true,
  },
})

const gridColumns = computed(() =>
  tableGridColumns(props.showTrackNumber, props.variant, props.showAlbum)
)
</script>

<template>
  <div
    class="hidden w-full items-center gap-3 p-2 text-left text-sm text-muted-foreground md:grid md:gap-6"
    :class="gridColumns"
  >
    <div v-if="showTrackNumber" class="min-w-0 text-center">曲序</div>
    <!-- 封面列占位：与表体封面单元格同宽，否则 auto 列宽度不一致 -->
    <div class="h-10 w-10"></div>
    <div class="min-w-0">标题</div>
    <div class="hidden min-w-0 md:block">歌手</div>
    <div v-if="showAlbum" class="hidden min-w-0 md:block">专辑</div>
    <template v-if="variant === 'library'">
      <div class="min-w-0 text-center">喜欢</div>
      <div class="hidden min-w-0 md:block">时长</div>
      <div class="min-w-0 text-center">下载</div>
    </template>
    <template v-else>
      <div class="hidden min-w-0 md:block">时长</div>
      <div class="min-w-0 text-center">操作</div>
    </template>
  </div>
</template>
