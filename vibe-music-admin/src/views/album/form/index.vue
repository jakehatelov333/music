<script setup lang="ts">
import { ref } from "vue";
import ReCol from "@/components/ReCol";
import ReCropperPreview from "@/components/ReCropperPreview";
import albumCover from "@/assets/song.jpg";
import { formRules } from "../utils/rule";
import { FormProps } from "../utils/types";

const props = withDefaults(defineProps<FormProps>(), {
  formInline: () => ({
    formTitle: "新增",
    albumId: 0,
    coverUrl: "",
    artistId: undefined,
    artistName: "",
    albumName: "",
    releaseDate: "",
    description: "",
    status: "DRAFT"
  }),
  artistOptions: () => []
});

const ruleFormRef = ref();
const newFormInline = ref(props.formInline);
const coverBlob = ref<Blob>();
/** 用户是否真正动过封面（选了新图或调整了裁剪），避免每次保存都静默重传原封面 */
const coverDirty = ref(false);
let cropperInitialized = false;

function onCropper(info: { blob?: Blob } | null) {
  if (!cropperInitialized) {
    // 裁剪器 ready 时的首次回调是原封面的基线快照，不算用户改动
    cropperInitialized = true;
    coverBlob.value = info?.blob;
    return;
  }
  coverDirty.value = true;
  coverBlob.value = info?.blob;
}

function getRef() {
  return ruleFormRef.value;
}

function getCoverBlob() {
  return coverDirty.value ? coverBlob.value : undefined;
}

defineExpose({ getRef, getCoverBlob });
</script>

<template>
  <el-form
    ref="ruleFormRef"
    :model="newFormInline"
    :rules="formRules"
    label-width="82px"
  >
    <el-row :gutter="30">
      <re-col
        v-if="newFormInline.formTitle === '修改'"
        :value="12"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="专辑编号" prop="albumId">
          <el-input v-model="newFormInline.albumId" disabled />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="歌手" prop="artistId" required>
          <el-select
            v-model="newFormInline.artistId"
            filterable
            clearable
            placeholder="请选择主艺人（可留空）"
            class="w-full"
          >
            <el-option
              v-for="artist in artistOptions"
              :key="artist.artistId"
              :label="artist.artistName"
              :value="artist.artistId"
            />
          </el-select>
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="专辑名" prop="albumName" required>
          <el-input
            v-model="newFormInline.albumName"
            clearable
            placeholder="请输入专辑名"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="发行日期" prop="releaseDate">
          <el-date-picker
            v-model="newFormInline.releaseDate"
            type="date"
            placeholder="请选择发行日期"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            class="w-full"
          />
        </el-form-item>
      </re-col>

      <re-col>
        <el-form-item label="状态" prop="status">
          <el-select v-model="newFormInline.status" class="w-full">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已发布" value="PUBLISHED" />
          </el-select>
        </el-form-item>
        <el-form-item label="简介">
          <el-input
            v-model="newFormInline.description"
            placeholder="请输入专辑简介"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 10 }"
          />
        </el-form-item>
      </re-col>

      <re-col v-if="newFormInline.formTitle === '修改' && newFormInline.albumId">
        <el-form-item label="封面">
          <ReCropperPreview
            uploadable
            :circled="false"
            :options="{ zoomOnWheel: false }"
            :img-src="newFormInline.coverUrl || albumCover"
            :fallback-src="albumCover"
            @cropper="onCropper"
          />
        </el-form-item>
      </re-col>
    </el-row>
  </el-form>
</template>
