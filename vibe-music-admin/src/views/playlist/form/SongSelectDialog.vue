<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { message } from "@/utils/message";
import { getSongList } from "@/api/system";

/**
 * 可复用的选歌弹窗：支持按歌曲名与歌手名搜索，多选后确认。
 * 用于「新增歌单时选择歌曲」与「歌单歌曲维护时添加歌曲」两处。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    /** 已在歌单中的歌曲，禁止重复选择 */
    excludedIds?: number[];
    title?: string;
  }>(),
  { excludedIds: () => [], title: "选择歌曲" }
);
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  confirm: [songs: any[]];
}>();

const query = reactive({ pageNum: 1, pageSize: 8, songName: "", artistName: "" });
const candidates = ref<any[]>([]);
const total = ref(0);
const loading = ref(false);
const selectedRows = ref<any[]>([]);
const tableRef = ref();

const excluded = () => props.excludedIds ?? [];

function isExcluded(row: any) {
  return excluded().includes(row.songId);
}

async function search() {
  loading.value = true;
  try {
    const res = await getSongList({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      songName: query.songName,
      artistName: query.artistName
    });
    if (res.code === 0 && res.data?.items) {
      candidates.value = res.data.items;
      total.value = res.data.total ?? res.data.items.length;
    } else {
      candidates.value = [];
      total.value = 0;
    }
  } catch {
    candidates.value = [];
    total.value = 0;
    message("歌曲列表加载失败", { type: "error" });
  } finally {
    loading.value = false;
  }
}

function resetSearch() {
  query.pageNum = 1;
  search();
}

function handleSelectionChange(rows: any[]) {
  selectedRows.value = rows;
}

function confirm() {
  if (!selectedRows.value.length) {
    message("请先勾选要添加的歌曲", { type: "warning" });
    return;
  }
  emit("confirm", [...selectedRows.value]);
  emit("update:modelValue", false);
}

function close() {
  emit("update:modelValue", false);
}

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    query.pageNum = 1;
    selectedRows.value = [];
    tableRef.value?.clearSelection?.();
    search();
  }
);

onMounted(() => {
  if (props.modelValue) search();
});
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="62%"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="flex items-center gap-2 mb-3 flex-wrap">
      <el-input
        v-model="query.songName"
        placeholder="歌曲名"
        clearable
        class="!w-[180px]"
        @keyup.enter="resetSearch"
      />
      <el-input
        v-model="query.artistName"
        placeholder="歌手名"
        clearable
        class="!w-[180px]"
        @keyup.enter="resetSearch"
      />
      <el-button type="primary" :loading="loading" @click="resetSearch">
        搜索
      </el-button>
      <el-button
        @click="
          query.songName = '';
          query.artistName = '';
          resetSearch();
        "
      >
        重置
      </el-button>
    </div>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="candidates"
      size="small"
      max-height="380"
      row-key="songId"
      @selection-change="handleSelectionChange"
    >
      <!-- reserve-selection：翻页 / 切换搜索条件后按 row-key 保留已勾选歌曲，
           否则 data 变更时 el-table 会清掉不在当前列表中的选中项 -->
      <el-table-column
        type="selection"
        width="45"
        reserve-selection
        :selectable="row => !isExcluded(row)"
      />
      <el-table-column label="歌曲" prop="songName" min-width="160" />
      <el-table-column label="歌手" prop="artistName" min-width="120" />
      <el-table-column label="专辑" prop="album" min-width="140" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="isExcluded(row)" type="info" size="small">
            已在歌单
          </el-tag>
        </template>
      </el-table-column>
      <template #empty>没有匹配的歌曲，试试其它歌曲名或歌手名</template>
    </el-table>

    <div class="flex justify-between items-center mt-3">
      <span class="text-xs text-gray-400">已选 {{ selectedRows.length }} 首</span>
      <el-pagination
        v-model:current-page="query.pageNum"
        :page-size="query.pageSize"
        :total="total"
        layout="prev, pager, next"
        :pager-count="5"
        @current-change="search"
      />
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :disabled="!selectedRows.length" @click="confirm">
        确定添加{{ selectedRows.length ? `(${selectedRows.length})` : "" }}
      </el-button>
    </template>
  </el-dialog>
</template>
