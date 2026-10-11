<script setup lang="ts">
import { ref, watch } from "vue";
import { previewLyrics, saveLyrics } from "@/api/lyrics";
import { message } from "@/utils/message";

const props = defineProps({
  songId: Number,
  songName: String,
  visible: Boolean
});
const emit = defineEmits(["update:visible", "success"]);

const isVisible = ref(props.visible);
const loading = ref(false);
const saving = ref(false);

const matched = ref(false);
const source = ref("");
const score = ref<number | null>(null);
const matchedTrack = ref("");
const resultMessage = ref("");
const hasExistingLyric = ref(false);
const force = ref(false);
const lyric = ref("");

watch(
  () => props.visible,
  newVal => {
    isVisible.value = newVal;
    if (newVal) {
      force.value = false;
      fetchLyric();
    }
  }
);

const sourceLabel = (value: string) => {
  if (value === "QQ_MUSIC") return "QQ音乐";
  if (value === "LRCLIB") return "LRCLIB";
  return value || "-";
};

async function fetchLyric() {
  if (!props.songId) return;
  loading.value = true;
  lyric.value = "";
  matched.value = false;
  try {
    const res: any = await previewLyrics(props.songId);
    const data = res.data || {};
    matched.value = !!data.matched;
    source.value = data.source || "";
    score.value = data.score ?? null;
    hasExistingLyric.value = !!data.hasExistingLyric;
    resultMessage.value = data.message || res.message || "";
    matchedTrack.value = [data.artistName, data.songName]
      .filter(Boolean)
      .join(" / ");
    lyric.value = data.lyric || "";
    if (!data.matched) {
      message(resultMessage.value || "未找到匹配的歌词", {
        type: "warning"
      });
    }
  } catch (error) {
    console.error("获取歌词失败:", error);
    message("获取歌词失败，请稍后重试", { type: "error" });
  } finally {
    loading.value = false;
  }
}

async function submitForm() {
  if (!lyric.value?.trim()) {
    message("歌词内容为空，无需保存", { type: "warning" });
    return;
  }
  if (hasExistingLyric.value && !force.value) {
    message("该歌曲已有歌词，如需覆盖请勾选「强制覆盖」", { type: "warning" });
    return;
  }

  saving.value = true;
  try {
    const res: any = await saveLyrics(props.songId, {
      lyric: lyric.value,
      force: force.value
    });
    if (res.code === 0) {
      message(res.message || "歌词已保存", { type: "success" });
      emit("update:visible", false);
      emit("success");
    } else {
      message(res.message || "歌词保存失败", { type: "error" });
    }
  } catch (error) {
    console.error("保存歌词失败:", error);
    message("歌词保存失败，请重试", { type: "error" });
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    v-model="isVisible"
    :title="`补全歌词 · ${songName || ''}`"
    width="660px"
    @close="emit('update:visible', false)"
  >
    <div v-loading="loading" class="lyric-body">
      <div v-if="matched" class="match-bar match-bar--ok">
        <span class="match-title">匹配成功</span>
        <span class="match-text">来源 {{ sourceLabel(source) }}</span>
        <span class="match-text">得分 {{ score?.toFixed(2) }}</span>
        <span class="match-text">{{ matchedTrack }}</span>
      </div>
      <div v-else class="match-bar match-bar--warn">
        <span class="match-title">{{ resultMessage || "未找到匹配的歌词" }}</span>
        <span class="match-text">可手动粘贴歌词后保存</span>
      </div>

      <div class="lyric-label">歌词内容（可编辑，保存前不会覆盖已有歌词）</div>
      <el-input
        v-model="lyric"
        type="textarea"
        :rows="14"
        resize="vertical"
        spellcheck="false"
        class="lyric-textarea"
        placeholder="未匹配到歌词时，可在此手动粘贴 LRC 文本"
      />

      <div class="lyric-foot">
        <el-checkbox v-model="force">强制覆盖已有歌词</el-checkbox>
        <span class="lyric-meta">
          {{ lyric ? lyric.split("\n").filter(Boolean).length : 0 }} 行
        </span>
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button :loading="loading" @click="fetchLyric">重新获取</el-button>
      <el-button type="primary" :loading="saving" @click="submitForm">
        保存歌词
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.lyric-body {
  min-height: 220px;
}

.match-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 12.5px;
  margin-bottom: 14px;
}

.match-bar--ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.match-bar--warn {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}

.match-title {
  font-weight: 600;
}

.match-text {
  color: var(--el-text-color-secondary);
}

.lyric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.lyric-textarea :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
}

.lyric-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.lyric-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
