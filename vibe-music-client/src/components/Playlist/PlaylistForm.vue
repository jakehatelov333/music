<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import type { PlaylistInput } from '@/api/myPlaylists'

const props = defineProps<{
  modelValue: boolean
  initial?: PlaylistInput
  saving: boolean
  error: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  save: [value: PlaylistInput]
}>()
const formRef = ref<FormInstance>()
const form = reactive<PlaylistInput>({
  title: '',
  introduction: '',
  style: '',
  isPublic: false,
})
const rules: FormRules<PlaylistInput> = {
  title: [
    {
      required: true,
      whitespace: true,
      message: '请输入歌单名称',
      trigger: 'blur',
    },
    { max: 255, message: '歌单名称最多 255 字', trigger: 'blur' },
  ],
  introduction: [{ max: 2000, message: '介绍最多 2000 字', trigger: 'blur' }],
  style: [{ max: 64, message: '风格最多 64 字', trigger: 'blur' }],
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    Object.assign(
      form,
      props.initial ?? {
        title: '',
        introduction: '',
        style: '',
        isPublic: false,
      }
    )
    formRef.value?.clearValidate()
  }
)

async function submit() {
  if (props.saving || !(await formRef.value?.validate().catch(() => false)))
    return
  emit('save', {
    title: form.title.trim(),
    introduction: form.introduction.trim(),
    style: form.style.trim(),
    isPublic: form.isPublic,
  })
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="initial ? '编辑歌单' : '创建歌单'"
    width="min(520px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      :disabled="saving"
      @submit.prevent="submit"
    >
      <el-form-item label="歌单名称" prop="title">
        <el-input
          v-model="form.title"
          maxlength="255"
          show-word-limit
          placeholder="例如：周末散步"
        />
      </el-form-item>
      <el-form-item label="歌单介绍" prop="introduction">
        <el-input
          v-model="form.introduction"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="记录这份歌单的心情（选填）"
        />
      </el-form-item>
      <el-form-item label="风格" prop="style">
        <el-input
          v-model="form.style"
          maxlength="64"
          placeholder="例如：轻音乐（选填）"
        />
      </el-form-item>
      <el-form-item label="可见范围">
        <el-radio-group v-model="form.isPublic">
          <el-radio :value="false">私有 · 仅自己可见</el-radio>
          <el-radio :value="true">公开 · 大家可见</el-radio>
        </el-radio-group>
      </el-form-item>
      <p v-if="error" class="text-destructive mb-4" role="alert">{{ error }}</p>
      <div class="flex justify-end gap-3">
        <el-button :disabled="saving" @click="emit('update:modelValue', false)"
          >取消</el-button
        >
        <el-button native-type="submit" type="primary" :loading="saving">{{
          initial ? '保存修改' : '创建歌单'
        }}</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
:deep(.el-button),
:deep(.el-input__wrapper) {
  min-height: 44px;
}
</style>
