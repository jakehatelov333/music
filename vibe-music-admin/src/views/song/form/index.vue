<script setup lang="ts">
import { ref } from "vue";
import ReCol from "@/components/ReCol";
import { formRules } from "../utils/rule";
import { FormProps } from "../utils/types";

const props = withDefaults(defineProps<FormProps>(), {
  formInline: () => ({
    title: "新增",
    artistId: null,
    artistName: "",
    songId: null,
    songName: "",
    album: "",
    style: [],
    releaseTime: null,
    lyric: "",
    instrumental: false
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

function getRef() {
  return ruleFormRef.value;
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
      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="歌手编号" prop="artistId">
          <el-input
            v-model="newFormInline.artistId"
            clearable
            disabled
            placeholder="newFormInline.artistId"
          />
        </el-form-item>
      </re-col>

      <re-col
        v-if="newFormInline.title === '修改'"
        :value="12"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="歌手" prop="artistName">
          <el-input
            v-model="newFormInline.artistName"
            clearable
            disabled
            placeholder="newFormInline.artistName"
          />
        </el-form-item>
      </re-col>

      <re-col
        v-if="newFormInline.title === '修改'"
        :value="12"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="歌曲编号" prop="songId">
          <el-input
            v-model="newFormInline.songId"
            clearable
            disabled
            placeholder="newFormInline.songId"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="歌名" prop="songName" required>
          <el-input
            v-model="newFormInline.songName"
            clearable
            placeholder="请输入歌名"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="专辑" prop="album" required>
          <el-input
            v-model="newFormInline.album"
            clearable
            placeholder="请输入专辑"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="发行日期" prop="releaseTime" required>
          <el-date-picker
            v-model="newFormInline.releaseTime"
            type="date"
            placeholder="请选择发行日期"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="曲风">
          <el-select
            v-model="newFormInline.style"
            placeholder="请选择曲风"
            class="w-full"
            clearable
            multiple
          >
            <el-option
              v-for="(item, index) in styleOptions"
              :key="index"
              :label="item"
              :value="item"
            >
              {{ item }}
            </el-option>
          </el-select>
        </el-form-item>
      </re-col>

      <re-col
        :value="24"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="曲目类型">
          <el-checkbox v-model="newFormInline.instrumental">
            这是伴奏 / 无需歌词
          </el-checkbox>
          <div class="lyric-hint">
            标记后会作为“歌词已处理”保存，并从后续批量搜索中跳过；用户端会显示伴奏提示。
          </div>
        </el-form-item>
      </re-col>

      <re-col
        v-if="newFormInline.title === '修改' && !newFormInline.instrumental"
        :value="24"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="歌词" prop="lyric">
          <el-input
            v-model="newFormInline.lyric"
            type="textarea"
            :rows="10"
            resize="vertical"
            placeholder="支持 LRC 时间轴格式，留空表示该歌曲暂无歌词"
            class="lyric-textarea"
          />
          <div class="lyric-hint">
            支持 LRC 时间轴格式；留空表示该歌曲暂无歌词。可先在列表页使用「补歌词」自动获取。
          </div>
        </el-form-item>
      </re-col>
    </el-row>
  </el-form>
</template>

<style scoped>
.lyric-textarea :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
}

.lyric-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
