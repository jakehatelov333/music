<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import { useAlbum } from "./utils/hook";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import {
  cancelLyricsTask,
  getLyricsTask,
  submitLyricsBatch
} from "@/api/lyrics";
import { ElNotification } from "element-plus";
import { message } from "@/utils/message";

import Upload from "@iconify-icons/ri/upload-line";
import Preview from "@iconify-icons/ri/headphone-line";
import Delete from "@iconify-icons/ep/delete";
import EditPen from "@iconify-icons/ep/edit-pen";
import Refresh from "@iconify-icons/ep/refresh";
import LyricsIcon from "@iconify-icons/ri/file-music-line";
import PreviewDialog from "@/views/song/form/preview.vue";
import { useRouter } from "vue-router";

defineOptions({
  name: "AlbumManagement"
});

const formRef = ref();
const tableRef = ref();
const router = useRouter();

// 专辑详情中的单首歌曲音频预览
const previewVisible = ref(false);
const previewSongName = ref("");
const previewAudioUrl = ref("");

function openPreviewDialog(row: any) {
  previewSongName.value = row.songName;
  previewAudioUrl.value = row.audioUrl;
  previewVisible.value = true;
}

// 补全本专辑歌词
const albumBatchVisible = ref(false);
const albumBatchSubmitting = ref(false);
const albumBatchStopping = ref(false);
/** 任务跑完前保持为 true，避免重复提交 */
const albumBatchRunning = ref(false);
const albumBatchTask = ref<any>(null);
const albumBatchIndicatorDismissed = ref(false);
const notifiedAlbumBatchTaskIds = new Set<string>();
let albumBatchTimer: number | undefined;

const {
  form,
  loading,
  detailVisible,
  detailLoading,
  detailAlbum,
  columns,
  dataList,
  artistOptions,
  selectedNum,
  pagination,
  buttonClass,
  deviceDetection,
  onSearch,
  resetForm,
  onbatchDel,
  openDialog,
  openCreateDialog,
  handleUpdate,
  handleDelete,
  handleSizeChange,
  handleSortChange,
  onSelectionCancel,
  handleCurrentChange,
  handleSelectionChange
} = useAlbum(tableRef);

/** 歌词状态标签 */
type TagType = "primary" | "success" | "warning" | "danger" | "info";

const lyricStatusMeta = (
  status: number,
  row: any
): { label: string; type: TagType } => {
  if (status === 1 || status === 2) {
    return { label: "已有", type: "success" };
  }
  if (status === 4) {
    return { label: "伴奏", type: "info" };
  }
  if (status === 3) {
    return { label: "无匹配", type: "info" };
  }
  if (status == null && row?.lyric) {
    return { label: "已有", type: "success" };
  }
  return { label: "无", type: "warning" };
};

/** 一键补全本专辑缺失歌词 */
async function handleAlbumLyrics() {
  if (albumBatchRunning.value) {
    message("已有批量补全任务正在执行，请等待完成后再提交", {
      type: "warning"
    });
    return;
  }
  const albumId = detailAlbum.value?.albumId;
  if (!albumId) {
    message("专辑信息尚未加载完成", { type: "warning" });
    return;
  }
  albumBatchSubmitting.value = true;
  try {
    const res: any = await submitLyricsBatch({ albumId, onlyMissing: true });
    if (res.code === 0 && res.data?.taskId) {
      albumBatchTask.value = res.data;
      albumBatchVisible.value = true;
      albumBatchIndicatorDismissed.value = false;
      albumBatchRunning.value = true;
      startAlbumPolling(res.data.taskId, albumId);
    } else {
      message(res.message || "提交批量补全任务失败", { type: "error" });
    }
  } catch (error) {
    console.error("提交专辑歌词补全失败:", error);
    message("提交批量补全任务失败，请稍后重试", { type: "error" });
  } finally {
    albumBatchSubmitting.value = false;
  }
}

function notifyAlbumBatchFinished(task: any) {
  const taskId = task?.taskId;
  if (taskId && notifiedAlbumBatchTaskIds.has(taskId)) return;
  if (taskId) notifiedAlbumBatchTaskIds.add(taskId);

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

async function handleStopAlbumLyrics() {
  const taskId = albumBatchTask.value?.taskId;
  if (!taskId || albumBatchStopping.value) return;

  albumBatchStopping.value = true;
  try {
    const res: any = await cancelLyricsTask(taskId);
    if (res.code === 0 && res.data) {
      albumBatchTask.value = res.data;
      if (res.data.status === "DONE" || res.data.status === "CANCELLED") {
        stopAlbumPolling();
        albumBatchRunning.value = false;
        const albumId = detailAlbum.value?.albumId;
        if (albumId) handleUpdate({ albumId });
        notifyAlbumBatchFinished(res.data);
      }
      message(res.message || "已提交停止请求", { type: "warning" });
    } else {
      message(res.message || "停止批量搜索失败", { type: "error" });
    }
  } catch (error) {
    console.error("停止专辑歌词搜索失败:", error);
    message("停止批量搜索失败，请稍后重试", { type: "error" });
  } finally {
    albumBatchStopping.value = false;
  }
}

function startAlbumPolling(taskId: string, albumId: number) {
  stopAlbumPolling();
  albumBatchTimer = window.setInterval(async () => {
    try {
      const res: any = await getLyricsTask(taskId);
      if (res.code === 0 && res.data) {
        albumBatchTask.value = res.data;
        if (res.data.status === "DONE" || res.data.status === "CANCELLED") {
          stopAlbumPolling();
          albumBatchRunning.value = false;
          // 刷新详情中的歌词状态
          handleUpdate({ albumId });
          notifyAlbumBatchFinished(res.data);
        }
      }
    } catch (error) {
      console.error("查询批量任务进度失败:", error);
    }
  }, 1500);
}

function stopAlbumPolling() {
  if (albumBatchTimer) {
    window.clearInterval(albumBatchTimer);
    albumBatchTimer = undefined;
  }
}

onBeforeUnmount(stopAlbumPolling);
</script>

<template>
  <div :class="['flex', 'justify-between', deviceDetection() && 'flex-wrap']">
    <div :class="[deviceDetection() ? ['w-full', 'mt-2'] : 'w-[calc(100%-0px)]']">
      <el-form
        ref="formRef"
        :inline="true"
        :model="form"
        class="search-form bg-bg_color w-[99/100] pl-8 pt-[12px] overflow-auto"
      >
        <el-form-item label="专辑：" prop="albumName">
          <el-input
            v-model="form.albumName"
            placeholder="请输入专辑"
            clearable
            class="!w-[180px]"
          />
        </el-form-item>
        <el-form-item label="歌手：" prop="artistId">
          <el-select
            v-model="form.artistId"
            placeholder="请选择歌手"
            clearable
            filterable
            class="!w-[180px]"
          >
            <el-option
              v-for="artist in artistOptions"
              :key="artist.artistId"
              :label="artist.artistName"
              :value="artist.artistId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态：" prop="status">
          <el-select
            v-model="form.status"
            placeholder="全部状态"
            clearable
            class="!w-[150px]"
          >
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="草稿" value="DRAFT" />
          </el-select>
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

      <PureTableBar title="专辑管理" :columns="columns" @refresh="onSearch">
        <template v-slot="{ size, dynamicColumns }">
          <div class="flex justify-end gap-2 mb-3">
            <el-button type="primary" :icon="useRenderIcon(Upload)" @click="router.push('/album-upload/index')">上传专辑</el-button>
            <el-button :icon="useRenderIcon('ri:add-line')" @click="openCreateDialog">新建专辑</el-button>
          </div>
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
            row-key="albumId"
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
            @sort-change="handleSortChange"
            @page-size-change="handleSizeChange"
            @page-current-change="handleCurrentChange"
          >
            <template #operation="{ row }">
              <el-button class="reset-margin" link type="primary" :size="size" @click="handleUpdate(row)">查看</el-button>
              <el-button
                class="reset-margin"
                link
                type="primary"
                :size="size"
                :icon="useRenderIcon(EditPen)"
                @click="openDialog(row)"
              >
                修改
              </el-button>
              <el-popconfirm
                :title="`是否确认删除专辑编号为 ${row.albumId} 的这条数据`"
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
            </template>
          </pure-table>
        </template>
      </PureTableBar>
    </div>
    <el-drawer v-model="detailVisible" title="专辑详情" size="680px" destroy-on-close>
      <div v-loading="detailLoading" class="album-detail">
        <template v-if="detailAlbum">
          <div class="detail-header">
            <el-image :src="detailAlbum.coverUrl || undefined" fit="cover" class="detail-cover" />
            <div class="detail-copy">
              <h2>{{ detailAlbum.albumName }}</h2>
              <div>{{ detailAlbum.artistName || "未指定主艺人" }}</div>
              <div>{{ detailAlbum.releaseDate || "未设置发行日期" }}</div>
              <el-tag :type="detailAlbum.status === 'PUBLISHED' ? 'success' : 'info'">{{ detailAlbum.status === 'PUBLISHED' ? '已发布' : '草稿' }}</el-tag>
              <p>{{ detailAlbum.description || "暂无简介" }}</p>
            </div>
          </div>
          <div class="detail-songs-heading">
            <h3>歌曲</h3>
            <span>{{ detailAlbum.songCount ?? detailAlbum.songs?.length ?? 0 }} 首</span>
            <div class="detail-heading-spacer" />
            <el-button
              type="warning"
              plain
              size="small"
              :icon="useRenderIcon(LyricsIcon)"
              :loading="albumBatchSubmitting"
              :disabled="albumBatchRunning"
              @click="handleAlbumLyrics"
            >
              补全本专辑歌词
            </el-button>
          </div>
          <el-table :data="detailAlbum.songs || []" empty-text="此专辑尚无歌曲">
            <el-table-column label="曲序" width="74" prop="trackNumber" />
            <el-table-column label="歌曲" prop="songName" min-width="180" />
            <el-table-column label="艺人" prop="artistName" min-width="120" />
            <el-table-column label="时长" prop="duration" width="100" />
            <el-table-column label="歌词" width="90">
              <template #default="{ row }">
                <el-tag
                  :type="lyricStatusMeta(row.lyricStatus, row).type"
                  effect="light"
                  size="small"
                >
                  {{ lyricStatusMeta(row.lyricStatus, row).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="试听" width="90">
              <template #default="{ row }">
                <el-button
                  :class="buttonClass"
                  link
                  type="primary"
                  size="small"
                  :icon="useRenderIcon(Preview)"
                  @click="openPreviewDialog(row)"
                >
                  预览
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>

    <!-- 音频预览弹窗 -->
    <PreviewDialog
      v-if="previewVisible"
      :visible="previewVisible"
      :songName="previewSongName"
      :audioUrl="previewAudioUrl"
      @update:visible="previewVisible = $event"
    />

    <!-- 补全本专辑进度 -->
    <el-dialog v-model="albumBatchVisible" title="补全本专辑歌词" width="460px">
      <div v-if="albumBatchTask">
        <div class="batch-head">
          <span>{{ albumBatchTask.title || "补全专辑歌词" }}</span>
          <span>{{ albumBatchTask.done }} / {{ albumBatchTask.total }}</span>
        </div>
        <div
          v-if="albumBatchTask.status === 'CANCELLING'"
          class="batch-status batch-status--warning"
        >
          正在停止：当前歌曲的数据源请求结束后会停止后续搜索。
        </div>
        <div
          v-else-if="albumBatchTask.status === 'CANCELLED'"
          class="batch-status batch-status--warning"
        >
          搜索已停止，已完成的处理结果会保留。
        </div>
        <el-progress
          :percentage="albumBatchTask.progress ?? 0"
          :status="
            albumBatchTask.status === 'DONE'
              ? 'success'
              : albumBatchTask.status === 'CANCELLED'
                ? 'warning'
                : ''
          "
        />
        <div class="batch-stats">
          <span>成功 {{ albumBatchTask.success ?? 0 }}</span>
          <span>无匹配 {{ albumBatchTask.noMatch ?? 0 }}</span>
          <span>跳过 {{ albumBatchTask.skipped ?? 0 }}</span>
          <span>失败 {{ albumBatchTask.failed ?? 0 }}</span>
        </div>
      </div>
      <template #footer>
        <el-popconfirm
          v-if="albumBatchTask?.status === 'RUNNING'"
          title="停止后会保留已完成结果，尚未处理的歌曲不会继续搜索。确定停止吗？"
          confirm-button-text="停止搜索"
          cancel-button-text="继续搜索"
          @confirm="handleStopAlbumLyrics"
        >
          <template #reference>
            <el-button type="danger" :loading="albumBatchStopping">
              停止搜索
            </el-button>
          </template>
        </el-popconfirm>
        <el-button
          v-else-if="albumBatchTask?.status === 'CANCELLING'"
          type="warning"
          loading
          disabled
        >
          正在停止
        </el-button>
        <el-button
          v-if="
            albumBatchTask?.status === 'RUNNING' ||
            albumBatchTask?.status === 'CANCELLING'
          "
          @click="albumBatchVisible = false"
        >
          后台运行
        </el-button>
        <el-button type="primary" @click="albumBatchVisible = false">
          {{
            albumBatchTask?.status === "DONE" ||
            albumBatchTask?.status === "CANCELLED"
              ? "完成"
              : "关闭"
          }}
        </el-button>
      </template>
    </el-dialog>

    <div
      v-if="
        albumBatchTask &&
        ['RUNNING', 'CANCELLING'].includes(albumBatchTask.status) &&
        !albumBatchVisible &&
        !albumBatchIndicatorDismissed
      "
      class="batch-progress-float"
    >
      <button
        class="batch-progress-orb"
        type="button"
        :style="{
          '--batch-progress': `${Math.max(0, Math.min(100, albumBatchTask.progress ?? 0))}%`
        }"
        :aria-label="`重新打开批量补全歌词进度，当前 ${albumBatchTask.progress ?? 0}%`"
        :title="`${albumBatchTask.title || '补全本专辑歌词'}：点击查看详情`"
        @click="albumBatchVisible = true"
      >
        <span>{{ albumBatchTask.progress ?? 0 }}%</span>
      </button>
      <button
        class="batch-progress-dismiss"
        type="button"
        aria-label="隐藏进度提示"
        title="隐藏进度提示"
        @click="albumBatchIndicatorDismissed = true"
      >
        <span aria-hidden="true">×</span>
      </button>
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

.search-form {
  :deep(.el-form-item) {
    margin-bottom: 12px;
  }
}

.album-detail { min-height: 200px; }
.detail-header { display: grid; grid-template-columns: 160px minmax(0, 1fr); gap: 20px; margin-bottom: 28px; }
.detail-cover { width: 160px; height: 160px; border-radius: 6px; background: var(--el-fill-color-light); }
.detail-copy { display: flex; flex-direction: column; gap: 10px; align-items: flex-start; }
.detail-copy h2, .detail-songs-heading h3 { margin: 0; }
.detail-copy p { margin: 0; color: var(--el-text-color-secondary); white-space: pre-wrap; }
.detail-songs-heading { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.detail-songs-heading span { color: var(--el-text-color-secondary); }
.detail-heading-spacer { flex: 1; }
.batch-head { display: flex; align-items: center; justify-content: space-between; font-size: 13px; margin-bottom: 10px; }
.batch-status { margin-bottom: 10px; font-size: 12px; line-height: 1.5; }
.batch-status--warning { color: var(--el-color-warning); }
.batch-stats { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 10px; font-size: 12px; color: var(--el-text-color-secondary); }
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
