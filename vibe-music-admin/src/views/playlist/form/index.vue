<script setup lang="ts">
import { ref } from "vue";
import ReCol from "@/components/ReCol";
import { formRules } from "../utils/rule";
import { FormProps } from "../utils/types";
import SongSelectDialog from "./SongSelectDialog.vue";

const props = withDefaults(defineProps<FormProps>(), {
  formInline: () => ({
    formTitle: "新增",
    playlistId: 0,
    title: "",
    style: "",
    introduction: "",
    songIds: []
  })
});

const styleOptions = [
  "节奏布鲁斯",
  "欧美流行",
  "华语流行",
  "粤语流行",
  "国风流行",
  "韩国流行",
  "日本流行",
  "嘻哈说唱",
  "非洲节拍",
  "原声带",
  "轻音乐",
  "摇滚",
  "朋克",
  "电子",
  "国风",
  "乡村",
  "古典"
];

const ruleFormRef = ref();
const newFormInline = ref(props.formInline);
const songDialog = ref(false);
/** 仅用于展示已选歌曲名称，真正的提交数据是 newFormInline.songIds */
const pickedSongs = ref<any[]>([]);

function getRef() {
  return ruleFormRef.value;
}

function openSongDialog() {
  songDialog.value = true;
}

function onSongsPicked(songs: any[]) {
  pickedSongs.value = [...pickedSongs.value, ...songs];
  newFormInline.value.songIds = pickedSongs.value.map(song => song.songId);
}

function removePicked(index: number) {
  pickedSongs.value.splice(index, 1);
  newFormInline.value.songIds = pickedSongs.value.map(song => song.songId);
}

defineExpose({ getRef });
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
        <el-form-item label="歌单编号" prop="playlistId">
          <el-input
            v-model="newFormInline.playlistId"
            disabled
            placeholder="newFormInline.playlistId"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="歌单" prop="title" required>
          <el-input
            v-model="newFormInline.title"
            clearable
            placeholder="请输入歌单"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="风格">
          <el-select
            v-model="newFormInline.style"
            placeholder="可选择预设，也可输入自定义风格"
            class="w-full"
            clearable
            filterable
            allow-create
            default-first-option
          >
            <el-option
              v-for="(item, index) in styleOptions"
              :key="index"
              :label="item"
              :value="item"
            />
          </el-select>
        </el-form-item>
      </re-col>

      <re-col>
        <el-form-item label="简介">
          <el-input
            v-model="newFormInline.introduction"
            placeholder="请输入简介"
            type="textarea"
            line-number
            :autosize="{ minRows: 3, maxRows: 8 }"
          />
        </el-form-item>
      </re-col>

      <re-col v-if="newFormInline.formTitle === '新增'">
        <el-form-item label="歌曲">
          <div class="w-full">
            <div class="flex items-center gap-2 flex-wrap">
              <el-button type="primary" plain @click="openSongDialog">
                选择歌曲
              </el-button>
              <span class="text-xs text-gray-400">
                可先挑选歌曲，创建后无需再手动添加
              </span>
            </div>
            <div v-if="pickedSongs.length" class="mt-2 flex flex-wrap gap-2">
              <el-tag
                v-for="(song, index) in pickedSongs"
                :key="song.songId"
                closable
                @close="removePicked(index)"
              >
                {{ song.songName }} - {{ song.artistName }}
              </el-tag>
            </div>
          </div>
        </el-form-item>
      </re-col>

      <re-col v-if="newFormInline.formTitle === '新增'">
        <el-form-item label="说明">
          <div class="text-xs text-gray-400 leading-5">
            新建歌单固定为草稿，发布后即为公开；平台歌单不使用私人可见性。
          </div>
        </el-form-item>
      </re-col>
    </el-row>

    <SongSelectDialog
      v-model="songDialog"
      title="选择要加入新歌单的歌曲"
      :excluded-ids="newFormInline.songIds ?? []"
      @confirm="onSongsPicked"
    />
  </el-form>
</template>
