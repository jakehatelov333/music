<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { PlaylistDetail } from '@/api/interface'
import {
  resetMyPlaylistCover,
  updateMyPlaylistCover,
} from '@/api/myPlaylists'
import coverImg from '@/assets/cover.png'

/**
 * 歌单封面修改弹窗。
 * 仅歌单所有者可调用，实际权限由后端再次校验（PATCH /playlist/mine/{id}/cover）。
 */
const props = defineProps<{
  modelValue: boolean
  playlistId: number | null
  title: string
  coverUrl: string | null
  coverIsCustom: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updated: [detail: PlaylistDetail]
}>()

const MAX_BYTES = 5 * 1024 * 1024
const fileInput = ref<HTMLInputElement | null>(null)
const picked = ref<File | null>(null)
const previewUrl = ref('')
const saving = ref(false)
const error = ref('')

const displayCover = computed(
  () => previewUrl.value || props.coverUrl || coverImg
)

function releasePreview() {
  if (previewUrl.value.startsWith('blob:')) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      picked.value = null
      error.value = ''
      releasePreview()
      if (fileInput.value) fileInput.value.value = ''
    }
  }
)

function choose() {
  fileInput.value?.click()
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  error.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    error.value = '封面仅支持 JPG、PNG、WebP 等图片格式'
    picked.value = null
    releasePreview()
    return
  }
  if (file.size > MAX_BYTES) {
    error.value = '封面图片不能超过 5 MB'
    picked.value = null
    releasePreview()
    return
  }
  releasePreview()
  picked.value = file
  previewUrl.value = URL.createObjectURL(file)
}

async function submit() {
  if (!props.playlistId) return
  if (!picked.value) {
    error.value = '请先选择一张图片'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const res = await updateMyPlaylistCover(props.playlistId, picked.value)
    if (res.code !== 0 || !res.data)
      throw new Error(res.message || '封面更新失败，请重试')
    ElMessage.success('封面已更新')
    emit('updated', res.data)
    emit('update:modelValue', false)
  } catch (err) {
    // 失败时保留弹窗，避免出现「提示成功但数据未保存」的情况。
    error.value = err instanceof Error ? err.message : '封面更新失败，请重试'
  } finally {
    saving.value = false
  }
}

async function resetToDefault() {
  if (!props.playlistId) return
  saving.value = true
  error.value = ''
  try {
    const res = await resetMyPlaylistCover(props.playlistId)
    if (res.code !== 0 || !res.data)
      throw new Error(res.message || '恢复默认封面失败，请重试')
    ElMessage.success('已恢复为默认封面')
    emit('updated', res.data)
    emit('update:modelValue', false)
  } catch (err) {
    error.value =
      err instanceof Error ? err.message : '恢复默认封面失败，请重试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="修改歌单封面"
    width="min(460px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="text-sm text-muted-foreground mb-4 break-words">
      「{{ title }}」当前使用{{
        coverIsCustom ? '自定义封面' : '默认封面（取自第一首歌曲）'
      }}。
    </p>
    <div class="flex justify-center mb-4">
      <img
        :src="displayCover"
        alt="歌单封面预览"
        class="w-40 h-40 object-cover rounded-xl bg-muted"
      />
    </div>
    <input
      ref="fileInput"
      type="file"
      accept="image/*"
      class="hidden"
      @change="onFileChange"
    />
    <p v-if="error" role="alert" class="text-destructive mb-3">{{ error }}</p>
    <div class="flex justify-between gap-3">
      <el-button
        v-if="coverIsCustom"
        :disabled="saving"
        @click="resetToDefault"
        >恢复默认封面</el-button
      >
      <span v-else />
      <div class="flex gap-3">
        <el-button :disabled="saving" @click="emit('update:modelValue', false)"
          >取消</el-button
        >
        <el-button :disabled="saving" @click="choose">选择图片</el-button>
        <el-button type="primary" :loading="saving" @click="submit"
          >上传封面</el-button
        >
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
:deep(.el-button) {
  min-height: 44px;
}
</style>
