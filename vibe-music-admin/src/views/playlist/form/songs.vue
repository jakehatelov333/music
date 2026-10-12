<script setup lang="ts">
import { onMounted, ref } from "vue";
import { message } from "@/utils/message";
import { addPlaylistSongs, getPlaylistDetail, removePlaylistSong } from "@/api/system";
import SongSelectDialog from "./SongSelectDialog.vue";

/**
 * 歌单歌曲维护：查看歌单详情与歌曲列表、添加歌曲、移除歌曲。
 * 用户自建歌单在管理端只读（readonly），只能查看与删除整个歌单。
 */
const props = withDefaults(
  defineProps<{
    playlistId: number;
    playlistTitle?: string;
    readonly?: boolean;
    onChanged?: () => void;
  }>(),
  { playlistTitle: "", readonly: false, onChanged: undefined }
);

const detail = ref<any>(null);
const loading = ref(true);
const saving = ref(false);
const songDialog = ref(false);

const currentSongIds = () =>
  (detail.value?.songs ?? []).map((song: any) => song.songId);

async function loadDetail() {
  loading.value = true;
  try {
    const res = await getPlaylistDetail(props.playlistId);
    if (res.code === 0 && res.data) {
      detail.value = res.data;
    } else {
      detail.value = null;
      message(res.message || "歌单详情加载失败", { type: "error" });
    }
  } catch {
    detail.value = null;
    message("歌单详情加载失败", { type: "error" });
  } finally {
    loading.value = false;
  }
}

async function onSongsPicked(songs: any[]) {
  if (props.readonly || !songs.length) return;
  saving.value = true;
  try {
    const res = await addPlaylistSongs(
      props.playlistId,
      songs.map(song => song.songId)
    );
    if (res.code === 0) {
      message(`已添加 ${songs.length} 首歌曲`, { type: "success" });
      await loadDetail();
      props.onChanged?.();
    } else {
      message(res.message || "添加歌曲失败", { type: "error" });
    }
  } catch {
    message("添加歌曲失败", { type: "error" });
  } finally {
    saving.value = false;
  }
}

async function removeSong(song: any) {
  saving.value = true;
  try {
    const res = await removePlaylistSong(props.playlistId, song.songId);
    if (res.code === 0) {
      message(`已将《${song.songName}》移出歌单`, { type: "success" });
      await loadDetail();
      props.onChanged?.();
    } else {
      message(res.message || "移除歌曲失败", { type: "error" });
    }
  } catch {
    message("移除歌曲失败", { type: "error" });
  } finally {
    saving.value = false;
  }
}

onMounted(loadDetail);
</script>

<template>
  <div v-loading="loading" class="playlist-songs">
    <div
      v-if="detail"
      class="mb-4 px-3 py-2 rounded bg-[var(--el-fill-color-light)] flex items-center flex-wrap gap-2"
    >
      <span class="font-medium">{{ detail.title }}</span>
      <el-tag
        :type="detail.status === 'PUBLISHED' ? 'success' : 'info'"
        size="small"
      >
        {{ detail.status === "PUBLISHED" ? "已发布" : "草稿" }}
      </el-tag>
      <el-tag :type="detail.isPublic ? 'primary' : 'warning'" size="small">
        {{ detail.isPublic ? "公开" : "私人" }}
      </el-tag>
      <el-tag v-if="detail.ownerUserId" type="info" size="small">
        用户歌单
      </el-tag>
      <el-tag v-else type="info" size="small">平台歌单</el-tag>
      <span class="text-xs text-gray-400">
        共 {{ detail.songs?.length ?? 0 }} 首歌曲
      </span>
    </div>

    <div class="flex items-center justify-between mb-2">
      <h4>歌单内歌曲</h4>
      <el-button
        v-if="!readonly"
        type="primary"
        :disabled="saving"
        @click="songDialog = true"
      >
        添加歌曲
      </el-button>
      <span v-else class="text-xs text-gray-400">
        用户自建歌单在管理端只读，仅可查看与删除整个歌单
      </span>
    </div>

    <el-table
      :data="detail?.songs ?? []"
      size="small"
      max-height="300"
      row-key="songId"
    >
      <el-table-column label="歌曲" prop="songName" min-width="180" />
      <el-table-column label="歌手" prop="artistName" min-width="140" />
      <el-table-column label="专辑" prop="album" min-width="140" />
      <el-table-column v-if="!readonly" label="操作" width="90" align="center">
        <template #default="{ row }">
          <el-button
            link
            type="danger"
            :disabled="saving"
            @click="removeSong(row)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
      <template #empty>该歌单还没有歌曲</template>
    </el-table>

    <SongSelectDialog
      v-model="songDialog"
      title="添加歌曲到歌单"
      :excluded-ids="currentSongIds()"
      @confirm="onSongsPicked"
    />
  </div>
</template>

<style scoped>
.playlist-songs :deep(.el-table) {
  border-radius: 6px;
}
</style>
