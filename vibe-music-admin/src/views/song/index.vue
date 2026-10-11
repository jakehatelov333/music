<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import tree from "./tree.vue";
import { useSong } from "./utils/hook";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import PreviewDialog from "./form/preview.vue";
import UploadAudioDialog from "./form/upload.vue";
import LyricsDialog from "./form/lyrics.vue";
import {
  cancelLyricsTask,
  getLyricsTask,
  getRunningLyricsTask,
  markSongsInstrumental,
  submitLyricsBatch
} from "@/api/lyrics";
import { ElNotification } from "element-plus";
import { message } from "@/utils/message";

import Upload from "@iconify-icons/ri/upload-line";
import Preview from "@iconify-icons/ri/headphone-line";
import More from "@iconify-icons/ep/more-filled";
import Delete from "@iconify-icons/ep/delete";
import EditPen from "@iconify-icons/ep/edit-pen";
import Refresh from "@iconify-icons/ep/refresh";
import AddFill from "@iconify-icons/ri/add-circle-line";
import LyricsIcon from "@iconify-icons/ri/file-music-line";

defineOptions({
  name: "SongManagement"
});

const treeRef = ref();
const formRef = ref();
const tableRef = ref();
const showUploadDialog = ref(false);
const selectedSongId = ref(null);
const showPreviewDialog = ref(false);
const selectedSongName = ref(null);
const selectedAudioUrl = ref(null);

// 单曲补歌词弹窗
const showLyricsDialog = ref(false);
const lyricSongId = ref(null);
const lyricSongName = ref(null);

// 批量补全任务
const batchVisible = ref(false);
const batchSubmitting = ref(false);
const batchInstrumentalSubmitting = ref(false);
const batchStopping = ref(false);
/** 任务真正跑完前一直为 true，用于禁用按钮，避免重复提交把任务堆在队列里 */
const batchRunning = ref(false);
const batchTask = ref<any>(null);
const batchIndicatorDismissed = ref(false);
const notifiedBatchTaskIds = new Set<string>();
let batchTimer: number | undefined;

const {
  form,
  loading,
  columns,
  dataList,
  treeData,
  treeLoading,
  selectedNum,
  pagination,
  buttonClass,
  deviceDetection,
  onSearch,
  resetForm,
  onbatchDel,
  openDialog,
  onTreeSelect,
  handleUpdate,
  handleDelete,
  handleUpload,
  handleSizeChange,
  onSelectionCancel,
  handleCurrentChange,
  handleSelectionChange
} = useSong(tableRef, treeRef);

const openUploadDialog = row => {
  selectedSongId.value = row.songId;
  showUploadDialog.value = true;
};
const openPreviewDialog = row => {
  selectedSongName.value = row.songName;
  selectedAudioUrl.value = row.audio;
  showPreviewDialog.value = true;
};
const openLyricsDialog = row => {
  lyricSongId.value = row.songId;
  lyricSongName.value = row.songName;
  showLyricsDialog.value = true;
};

async function handleBatchSetInstrumental() {
  const rows = tableRef.value?.getTableRef()?.getSelectionRows?.() ?? [];
  const songIds = rows.map(row => row.songId).filter(Boolean);
  if (!songIds.length) {
    message("请先选择要标记为伴奏的歌曲", { type: "warning" });
    return;
  }

  batchInstrumentalSubmitting.value = true;
  try {
    const res: any = await markSongsInstrumental(songIds);
    if (res.code === 0) {
      message(res.message || `已将 ${songIds.length} 首歌曲标记为伴奏`, {
        type: "success"
      });
      onSelectionCancel();
      onSearch();
    } else {
      message(res.message || "批量标记伴奏失败", { type: "error" });
    }
  } catch (error) {
    console.error("批量标记伴奏失败:", error);
    message("批量标记伴奏失败，请稍后重试", { type: "error" });
  } finally {
    batchInstrumentalSubmitting.value = false;
  }
}

/** 提交批量补全任务并轮询进度 */
async function handleBatchLyrics(payload: object) {
  if (batchRunning.value) {
    message("已有批量补全任务正在执行，请等待完成后再提交", {
      type: "warning"
    });
    return;
  }
  batchSubmitting.value = true;
  try {
    const res: any = await submitLyricsBatch(payload);
    if (res.code === 0 && res.data?.taskId) {
      batchTask.value = res.data;
      batchVisible.value = true;
      batchIndicatorDismissed.value = false;
      batchRunning.value = true;
      startPolling(res.data.taskId);
    } else {
      message(res.message || "提交批量补全任务失败", { type: "error" });
    }
  } catch (error) {
    console.error("提交批量补全任务失败:", error);
    message("提交批量补全任务失败，请稍后重试", { type: "error" });
  } finally {
    batchSubmitting.value = false;
  }
}

function notifyBatchTaskFinished(task: any) {
  const taskId = task?.taskId;
  if (taskId && notifiedBatchTaskIds.has(taskId)) return;
  if (taskId) notifiedBatchTaskIds.add(taskId);

  const cancelled = task?.status === "CANCELLED";
  const processed = task?.done ?? 0;
  const total = cancelled ? processed : (task?.total ?? processed);
  ElNotification({
    title: cancelled ? "批量补全已停止" : "批量补全歌词已完成",
    message: `共处理 ${total} 首歌曲：成功 ${task?.success ?? 0}，失败 ${task?.failed ?? 0}，跳过 ${task?.skipped ?? 0}，无匹配 ${task?.noMatch ?? 0}`,
    type: cancelled ? "warning" : "success",
    position: "bottom-right",
    duration: 8000,
    showClose: true
  });
}

async function handleStopBatchLyrics() {
  const taskId = batchTask.value?.taskId;
  if (!taskId || batchStopping.value) return;

  batchStopping.value = true;
  try {
    const res: any = await cancelLyricsTask(taskId);
    if (res.code === 0 && res.data) {
      batchTask.value = res.data;
      if (res.data.status === "DONE" || res.data.status === "CANCELLED") {
        stopPolling();
        batchRunning.value = false;
        onSearch();
        notifyBatchTaskFinished(res.data);
      }
      message(res.message || "已提交停止请求", { type: "warning" });
    } else {
      message(res.message || "停止批量搜索失败", { type: "error" });
    }
  } catch (error) {
    console.error("停止批量搜索失败:", error);
    message("停止批量搜索失败，请稍后重试", { type: "error" });
  } finally {
    batchStopping.value = false;
  }
}

function startPolling(taskId: string) {
  stopPolling();
  batchTimer = window.setInterval(async () => {
    try {
      const res: any = await getLyricsTask(taskId);
      if (res.code === 0 && res.data) {
        batchTask.value = res.data;
        if (res.data.status === "DONE" || res.data.status === "CANCELLED") {
          stopPolling();
          batchRunning.value = false;
          onSearch();
          notifyBatchTaskFinished(res.data);
        }
      }
    } catch (error) {
      console.error("查询批量任务进度失败:", error);
    }
  }, 1500);
}

function stopPolling() {
  if (batchTimer) {
    window.clearInterval(batchTimer);
    batchTimer = undefined;
  }
}

// 刷新页面后如果后端还有任务在跑，自动恢复进度展示
onMounted(async () => {
  try {
    const res: any = await getRunningLyricsTask();
    if (res.code === 0 && res.data?.taskId) {
      batchTask.value = res.data;
      batchRunning.value = true;
      batchVisible.value = true;
      batchIndicatorDismissed.value = false;
      startPolling(res.data.taskId);
    }
  } catch (error) {
    console.error("查询正在执行的歌词任务失败:", error);
  }
});

onBeforeUnmount(stopPolling);
</script>

<template>
  <div :class="['flex', 'justify-between', deviceDetection() && 'flex-wrap']">
    <tree
      ref="treeRef"
      :class="['mr-2', deviceDetection() ? 'w-full' : 'min-w-[180px]']"
      :treeData="treeData"
      :treeLoading="treeLoading"
      @tree-select="onTreeSelect"
    />
    <div
      :class="[deviceDetection() ? ['w-full', 'mt-2'] : 'w-[calc(100%-180px)]']"
    >
      <el-form
        ref="formRef"
        :inline="true"
        :model="form"
        class="search-form bg-bg_color w-[99/100] pl-8 pt-[12px] overflow-auto"
      >
        <el-form-item label="歌名：" prop="songName">
          <el-input
            v-model="form.songName"
            placeholder="请输入歌名"
            clearable
            class="!w-[180px]"
          />
        </el-form-item>
        <el-form-item label="专辑：" prop="album">
          <el-input
            v-model="form.album"
            placeholder="请输入专辑"
            clearable
            class="!w-[180px]"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.onlyMissingLyric">
            仅看无歌词
          </el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :icon="useRenderIcon('ri:search-line')"
            :loading="loading"
            @click="onSearch"
          >
            搜索
          </el-button>
          <el-button :icon="useRenderIcon(Refresh)" @click="resetForm(formRef)">
            重置
          </el-button>
        </el-form-item>
      </el-form>

      <PureTableBar
        title="歌曲管理"
        :columns="columns"
        @refresh="onSearch"
      >
        <template #buttons>
          <el-button
            type="primary"
            :icon="useRenderIcon(AddFill)"
            @click="openDialog()"
          >
            新增歌曲
          </el-button>
          <el-dropdown
            split-button
            type="warning"
            :disabled="batchRunning"
            :loading="batchSubmitting"
            @click="handleBatchLyrics({ onlyMissing: true })"
          >
            补全未获取的歌词
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  @click="
                    handleBatchLyrics({ onlyMissing: true, retryNoMatch: true })
                  "
                >
                  重试「无匹配」的歌曲
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-slot="{ size, dynamicColumns }">
          <div
            v-if="selectedNum > 0"
            v-motion-fade
            class="bg-[var(--el-fill-color-light)] w-full h-[46px] mb-2 pl-4 flex items-center"
          >
            <div class="flex-auto">
              <span
                style="font-size: var(--el-font-size-base)"
                class="text-[rgba(42,46,54,0.5)] dark:text-[rgba(220,220,242,0.5)]"
              >
                已选 {{ selectedNum }} 项
              </span>
              <el-button type="primary" text @click="onSelectionCancel">
                取消选择
              </el-button>
            </div>
            <el-button
              type="primary"
              text
              :loading="batchSubmitting"
              :disabled="batchRunning"
              @click="
                handleBatchLyrics({
                  songIds: tableRef.getTableRef().getSelectionRows().map(r => r.songId),
                  force: false
                })
              "
            >
              批量补全歌词
            </el-button>
            <el-popconfirm
              title="将清空所选歌曲已有歌词，并标记为伴奏；后续批量搜索会跳过这些歌曲。确认继续吗？"
              confirm-button-text="设为伴奏"
              cancel-button-text="取消"
              @confirm="handleBatchSetInstrumental"
            >
              <template #reference>
                <el-button
                  type="warning"
                  text
                  :loading="batchInstrumentalSubmitting"
                  :disabled="batchRunning || batchSubmitting"
                >
                  批量设为伴奏
                </el-button>
              </template>
            </el-popconfirm>
            <el-popconfirm title="是否确认删除?" @confirm="onbatchDel">
              <template #reference>
                <el-button type="danger" text class="mr-1">
                  批量删除
                </el-button>
              </template>
            </el-popconfirm>
          </div>
          <pure-table
            ref="tableRef"
            row-key="id"
            adaptive
            :adaptiveConfig="{ offsetBottom: 108 }"
            align-whole="center"
            table-layout="auto"
            :loading="loading"
            :size="size"
            :data="dataList"
            :columns="dynamicColumns"
            :pagination="{ ...pagination, size }"
            :header-cell-style="{
              background: 'var(--el-fill-color-light)',
              color: 'var(--el-text-color-primary)'
            }"
            @selection-change="handleSelectionChange"
            @page-size-change="handleSizeChange"
            @page-current-change="handleCurrentChange"
          >
            <template #operation="{ row }">
              <el-button
                class="reset-margin"
                link
                type="primary"
                :size="size"
                :icon="useRenderIcon(EditPen)"
                @click="openDialog('修改', row)"
              >
                修改
              </el-button>
              <el-button
                class="reset-margin"
                link
                type="primary"
                :size="size"
                :icon="useRenderIcon(LyricsIcon)"
                @click="openLyricsDialog(row)"
              >
                歌词
              </el-button>
              <el-popconfirm
                :title="`是否确认删除歌曲编号为 ${row.songId} 的这条数据`"
                @confirm="handleDelete(row)"
              >
                <template #reference>
                  <el-button
                    class="reset-margin"
                    link
                    type="primary"
                    :size="size"
                    :icon="useRenderIcon(Delete)"
                  >
                    删除
                  </el-button>
                </template>
              </el-popconfirm>
              <el-dropdown>
                <el-button
                  class="ml-3 mt-[2px]"
                  link
                  type="primary"
                  :size="size"
                  :icon="useRenderIcon(More)"
                  @click="handleUpdate(row)"
                />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item>
                      <el-button
                        :class="buttonClass"
                        link
                        type="primary"
                        :size="size"
                        :icon="useRenderIcon(Upload)"
                        @click="handleUpload(row)"
                      >
                        上传封面
                      </el-button>
                    </el-dropdown-item>
                    <el-dropdown-item>
                      <el-button
                        :class="buttonClass"
                        link
                        type="primary"
                        :size="size"
                        :icon="useRenderIcon(Upload)"
                        @click="openUploadDialog(row)"
                      >
                        上传音频
                      </el-button>
                    </el-dropdown-item>
                    <el-dropdown-item>
                      <el-button
                        :class="buttonClass"
                        link
                        type="primary"
                        :size="size"
                        :icon="useRenderIcon(Preview)"
                        @click="openPreviewDialog(row)"
                      >
                        预览音频
                      </el-button>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </pure-table>
          <!-- 上传音频弹窗 -->
          <UploadAudioDialog
            v-if="showUploadDialog"
            :visible="showUploadDialog"
            :songId="selectedSongId"
            @update:visible="showUploadDialog = $event"
            @success="onSearch"
          />
          <!-- 音频预览弹窗 -->
          <PreviewDialog
            v-if="showPreviewDialog"
            :visible="showPreviewDialog"
            :songName="selectedSongName"
            :audioUrl="selectedAudioUrl"
            @update:visible="showPreviewDialog = $event"
          />
          <!-- 补全歌词弹窗 -->
          <LyricsDialog
            v-if="showLyricsDialog"
            :visible="showLyricsDialog"
            :songId="lyricSongId"
            :songName="lyricSongName"
            @update:visible="showLyricsDialog = $event"
            @success="onSearch"
          />

          <!-- 批量补全进度 -->
          <el-dialog
            v-model="batchVisible"
            title="批量补全歌词"
            width="460px"
          >
            <div v-if="batchTask">
              <div class="batch-head">
                <span>{{ batchTask.title || "批量补全歌词" }}</span>
                <span>{{ batchTask.done }} / {{ batchTask.total }}</span>
              </div>
              <div
                v-if="batchTask.status === 'CANCELLING'"
                class="batch-status batch-status--warning"
              >
                正在停止：当前歌曲的数据源请求结束后会停止后续搜索。
              </div>
              <div
                v-else-if="batchTask.status === 'CANCELLED'"
                class="batch-status batch-status--warning"
              >
                搜索已停止，已完成的处理结果会保留。
              </div>
              <el-progress
                :percentage="batchTask.progress ?? 0"
                :status="
                  batchTask.status === 'DONE'
                    ? 'success'
                    : batchTask.status === 'CANCELLED'
                      ? 'warning'
                      : ''
                "
              />
              <div class="batch-stats">
                <span>成功 {{ batchTask.success ?? 0 }}</span>
                <span>无匹配 {{ batchTask.noMatch ?? 0 }}</span>
                <span>跳过 {{ batchTask.skipped ?? 0 }}</span>
                <span>失败 {{ batchTask.failed ?? 0 }}</span>
              </div>
              <ul
                v-if="batchTask.failures && batchTask.failures.length"
                class="batch-failures"
              >
                <li v-for="(item, index) in batchTask.failures" :key="index">
                  {{ item }}
                </li>
              </ul>
            </div>
            <template #footer>
              <el-popconfirm
                v-if="batchTask?.status === 'RUNNING'"
                title="停止后会保留已完成结果，尚未处理的歌曲不会继续搜索。确定停止吗？"
                confirm-button-text="停止搜索"
                cancel-button-text="继续搜索"
                @confirm="handleStopBatchLyrics"
              >
                <template #reference>
                  <el-button type="danger" :loading="batchStopping">
                    停止搜索
                  </el-button>
                </template>
              </el-popconfirm>
              <el-button
                v-else-if="batchTask?.status === 'CANCELLING'"
                type="warning"
                loading
                disabled
              >
                正在停止
              </el-button>
              <el-button
                v-if="
                  batchTask?.status === 'RUNNING' ||
                  batchTask?.status === 'CANCELLING'
                "
                @click="batchVisible = false"
              >
                后台运行
              </el-button>
              <el-button type="primary" @click="batchVisible = false">
                {{
                  batchTask?.status === "DONE" ||
                  batchTask?.status === "CANCELLED"
                    ? "完成"
                    : "关闭"
                }}
              </el-button>
            </template>
          </el-dialog>

          <div
            v-if="
              batchTask &&
              ['RUNNING', 'CANCELLING'].includes(batchTask.status) &&
              !batchVisible &&
              !batchIndicatorDismissed
            "
            class="batch-progress-float"
          >
            <button
              class="batch-progress-orb"
              type="button"
              :style="{
                '--batch-progress': `${Math.max(0, Math.min(100, batchTask.progress ?? 0))}%`
              }"
              :aria-label="`重新打开批量补全歌词进度，当前 ${batchTask.progress ?? 0}%`"
              :title="`${batchTask.title || '批量补全歌词'}：点击查看详情`"
              @click="batchVisible = true"
            >
              <span>{{ batchTask.progress ?? 0 }}%</span>
            </button>
            <button
              class="batch-progress-dismiss"
              type="button"
              aria-label="隐藏进度提示"
              title="隐藏进度提示"
              @click="batchIndicatorDismissed = true"
            >
              <span aria-hidden="true">×</span>
            </button>
          </div>
        </template>
      </PureTableBar>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.el-dropdown-menu__item i) {
  margin: 0;
}

:deep(.el-button:focus-visible) {
  outline: none;
}

.main-content {
  margin: 24px 24px 0 !important;
}

.search-form {
  :deep(.el-form-item) {
    margin-bottom: 12px;
  }
}

.batch-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 10px;
}

.batch-status {
  margin-bottom: 10px;
  font-size: 12px;
  line-height: 1.5;
}

.batch-status--warning {
  color: var(--el-color-warning);
}

.batch-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.batch-failures {
  margin: 10px 0 0;
  padding-left: 18px;
  max-height: 140px;
  overflow: auto;
  font-size: 12px;
  color: var(--el-color-danger);
}

.batch-progress-float {
  position: fixed;
  right: 28px;
  bottom: 32px;
  z-index: 2100;
}

.batch-progress-orb {
  display: grid;
  width: 62px;
  height: 62px;
  padding: 4px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: conic-gradient(
    var(--el-color-primary) var(--batch-progress),
    var(--el-border-color-lighter) 0
  );
  color: var(--el-text-color-primary);
  cursor: pointer;
  box-shadow: 0 2px 12px rgb(31 45 61 / 18%);
}

.batch-progress-orb span {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  border-radius: 50%;
  background: var(--el-bg-color);
  font-size: 13px;
  font-weight: 600;
}

.batch-progress-orb:focus-visible,
.batch-progress-dismiss:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}

.batch-progress-dismiss {
  position: absolute;
  top: -5px;
  right: -5px;
  display: grid;
  width: 22px;
  height: 22px;
  padding: 0;
  place-items: center;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 50%;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  cursor: pointer;
  box-shadow: 0 1px 5px rgb(31 45 61 / 18%);
}

@media (max-width: 640px) {
  .batch-progress-float {
    right: 16px;
    bottom: 20px;
  }
}
</style>
