<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { searchPlaylistSongs } from '@/api/myPlaylists'
import type { Song } from '@/api/interface'

const props = defineProps<{
  modelValue: boolean
  existingIds: number[]
  saving: boolean
  error: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  add: [songIds: number[]]
}>()
const songName = ref('')
const artistName = ref('')
const songs = ref<Song[]>([])
const selected = ref<number[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const searchError = ref('')
const existing = computed(() => new Set(props.existingIds))
let controller: AbortController | undefined
let request = 0

async function search(reset = false) {
  if (reset) page.value = 1
  controller?.abort()
  controller = new AbortController()
  const current = ++request
  loading.value = true
  searchError.value = ''
  try {
    const res = await searchPlaylistSongs(
      songName.value.trim(),
      artistName.value.trim(),
      page.value,
      controller.signal
    )
    if (current !== request) return
    if (res.code !== 0 || !res.data)
      throw new Error(res.message || '暂时无法搜索歌曲')
    songs.value = Array.isArray(res.data.items) ? res.data.items : []
    total.value = Number(res.data.total) || 0
  } catch (error) {
    if (current !== request) return
    searchError.value =
      error instanceof Error ? error.message : '暂时无法搜索歌曲'
    songs.value = []
  } finally {
    if (current === request) loading.value = false
  }
}

function toggle(id: number, checked: boolean) {
  if (checked && !selected.value.includes(id) && selected.value.length < 100)
    selected.value.push(id)
  else if (!checked)
    selected.value = selected.value.filter((value) => value !== id)
}

watch(
  () => props.modelValue,
  (open) => {
    request++
    controller?.abort()
    if (open) {
      selected.value = []
      songName.value = ''
      artistName.value = ''
      songs.value = []
      total.value = 0
      search(true)
    }
  }
)
onBeforeUnmount(() => {
  request++
  controller?.abort()
})
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="添加歌曲"
    width="min(680px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <form
      class="grid grid-cols-1 sm:grid-cols-[1fr_1fr_auto] gap-3 mb-4"
      @submit.prevent="search(true)"
    >
      <label class="block text-sm"
        >歌曲名称
        <el-input
          v-model="songName"
          maxlength="200"
          placeholder="搜索歌曲"
          clearable
          class="mt-1"
        />
      </label>
      <label class="block text-sm"
        >歌手
        <el-input
          v-model="artistName"
          maxlength="200"
          placeholder="按歌手筛选"
          clearable
          class="mt-1"
        />
      </label>
      <el-button native-type="submit" :loading="loading" class="self-end"
        >搜索</el-button
      >
    </form>
    <p v-if="searchError" class="text-destructive my-3" role="alert">
      {{ searchError }}
    </p>
    <div
      class="min-h-48 max-h-[45vh] overflow-y-auto border rounded-lg"
      :aria-busy="loading"
    >
      <p v-if="loading" class="text-center py-10" role="status">
        正在搜索歌曲…
      </p>
      <p
        v-else-if="!songs.length"
        class="text-center py-10 text-muted-foreground"
      >
        没有找到歌曲，试试其他名称或歌手。
      </p>
      <ul v-else class="divide-y">
        <li
          v-for="song in songs"
          :key="song.songId"
          class="px-3 py-2 flex items-center gap-3 min-h-14"
        >
          <el-checkbox
            :model-value="
              selected.includes(song.songId) || existing.has(song.songId)
            "
            :disabled="
              saving ||
              existing.has(song.songId) ||
              (selected.length >= 100 && !selected.includes(song.songId))
            "
            :aria-label="`选择 ${song.songName}，${song.artistName}`"
            @change="toggle(song.songId, Boolean($event))"
          />
          <div class="min-w-0 flex-1">
            <p class="font-medium truncate">{{ song.songName }}</p>
            <p class="text-sm text-muted-foreground truncate">
              {{ song.artistName }} · {{ song.album || '未知专辑' }}
            </p>
          </div>
          <span v-if="existing.has(song.songId)" class="text-xs shrink-0"
            >已在歌单中</span
          >
        </li>
      </ul>
    </div>
    <el-pagination
      v-if="total > 10"
      v-model:current-page="page"
      :page-size="10"
      :total="total"
      layout="prev, pager, next"
      :pager-count="5"
      :disabled="loading"
      class="mt-4 justify-center"
      @current-change="search()"
    />
    <p v-if="error" class="text-destructive mt-4" role="alert">{{ error }}</p>
    <template #footer>
      <div class="flex flex-wrap items-center justify-between gap-3">
        <span class="text-sm" role="status"
          >已选 {{ selected.length }} 首，最多 100 首</span
        >
        <div class="flex gap-2">
          <el-button
            :disabled="saving"
            @click="emit('update:modelValue', false)"
            >取消</el-button
          >
          <el-button
            type="primary"
            :disabled="!selected.length"
            :loading="saving"
            @click="emit('add', selected)"
            >添加所选歌曲</el-button
          >
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
:deep(.el-button),
:deep(.el-input__wrapper),
:deep(.el-checkbox) {
  min-height: 44px;
}
</style>
