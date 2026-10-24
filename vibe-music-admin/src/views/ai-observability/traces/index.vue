<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import dayjs from "dayjs";
import { message } from "@/utils/message";
import {
  getAiTraceFilterOptions,
  searchAiTraces,
  searchAiUsers,
  type TraceListItem,
  type TraceQuery
} from "@/api/aiObservability";
import {
  RANGE_PRESETS,
  buildTraceQuery,
  formatDuration,
  formatTokensCompact,
  statusMeta,
  statusTagType,
  toIsoLocal
} from "../utils/format";

defineOptions({
  name: "AiObservabilityTraces"
});

const route = useRoute();
const router = useRouter();

const CUSTOM_RANGE = "custom";

type Form = {
  rangeHours: number | typeof CUSTOM_RANGE;
  customFrom: string;
  customTo: string;
  userId: number | null;
  traceId: string;
  chatId: string;
  status: string;
  model: string;
  toolName: string;
  minTotalTokens: number | null;
  maxTotalTokens: number | null;
  minLatencyMs: number | null;
  usedTools: boolean | null;
  outputGuarded: boolean | null;
  tokenAvailable: boolean | null;
};

const form = reactive<Form>({
  rangeHours: 24 * 7,
  customFrom: "",
  customTo: "",
  userId: null,
  traceId: "",
  chatId: "",
  status: "",
  model: "",
  toolName: "",
  minTotalTokens: null,
  maxTotalTokens: null,
  minLatencyMs: null,
  usedTools: null,
  outputGuarded: null,
  tokenAvailable: null
});

const pageNum = ref(1);
const pageSize = ref(20);
const sortField = ref<string>("startedAt");
const sortOrder = ref<"asc" | "desc">("desc");

const loading = ref(false);
const loadError = ref<string | null>(null);
const rows = ref<TraceListItem[]>([]);
const total = ref(0);
/** 是否已经执行过至少一次查询：用于区分"还没查"和"查了没结果"。 */
const searched = ref(false);

const models = ref<string[]>([]);
const tools = ref<string[]>([]);
const statuses = ref<string[]>([]);
const userOptions = ref<Array<{ userId: number; username: string }>>([]);
const userSearching = ref(false);

const rangeHint = computed(() => {
  if (form.rangeHours !== CUSTOM_RANGE) {
    return (
      RANGE_PRESETS.find(item => item.hours === form.rangeHours)?.label ?? ""
    );
  }
  return form.customFrom && form.customTo
    ? "自定义区间"
    : "未指定（后端默认最近 7 天）";
});

/** 把表单 + 分页 + 排序合成后端请求体；空值一律不传。 */
function buildQuery(): TraceQuery {
  const [presetFrom, presetTo] =
    form.rangeHours === CUSTOM_RANGE
      ? [form.customFrom, form.customTo]
      : rangeFromPreset(form.rangeHours);
  const raw = {
    from: presetFrom,
    to: presetTo,
    userId: form.userId,
    traceId: form.traceId.trim(),
    chatId: form.chatId.trim(),
    status: form.status,
    model: form.model,
    toolName: form.toolName,
    minTotalTokens: form.minTotalTokens,
    maxTotalTokens: form.maxTotalTokens,
    minLatencyMs: form.minLatencyMs,
    usedTools: form.usedTools,
    outputGuarded: form.outputGuarded,
    tokenAvailable: form.tokenAvailable,
    sortField: sortField.value,
    order: sortOrder.value,
    pageNum: pageNum.value,
    pageSize: pageSize.value
  };
  return buildTraceQuery(raw) as TraceQuery;
}

function rangeFromPreset(hours: number): [string, string] {
  const now = new Date();
  return [
    toIsoLocal(new Date(now.getTime() - hours * 3600_000)),
    toIsoLocal(now)
  ];
}

async function loadTraces() {
  loading.value = true;
  loadError.value = null;
  try {
    const res = await searchAiTraces(buildQuery());
    if (res.code !== 0) {
      loadError.value = res.message || "查询失败";
      rows.value = [];
      total.value = 0;
      message(res.message || "查询失败", { type: "error" });
      return;
    }
    rows.value = res.data?.items ?? [];
    total.value = res.data?.total ?? 0;
    searched.value = true;
  } catch (error: any) {
    // 400（区间超限等）与 401/403 都是"这次查询没成功"，必须如实说明，不能显示成空结果。
    const status = error?.response?.status;
    const serverMessage = error?.response?.data?.message;
    loadError.value =
      status === 403
        ? "没有权限访问 AI 可观测性数据（需要管理员账号）"
        : status === 401
          ? "登录已过期，请重新登录"
          : status === 400
            ? serverMessage || "查询条件不合法"
            : "接口请求失败，请稍后重试";
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

function onSearch() {
  pageNum.value = 1;
  loadTraces();
}

function onReset() {
  form.rangeHours = 24 * 7;
  form.customFrom = "";
  form.customTo = "";
  form.userId = null;
  form.traceId = "";
  form.chatId = "";
  form.status = "";
  form.model = "";
  form.toolName = "";
  form.minTotalTokens = null;
  form.maxTotalTokens = null;
  form.minLatencyMs = null;
  form.usedTools = null;
  form.outputGuarded = null;
  form.tokenAvailable = null;
  sortField.value = "startedAt";
  sortOrder.value = "desc";
  pageNum.value = 1;
  loadTraces();
}

function onPageChange(page: number) {
  pageNum.value = page;
  loadTraces();
}

function onSizeChange(size: number) {
  pageSize.value = size;
  pageNum.value = 1;
  loadTraces();
}

/** el-table 的 sort-change：只接受白名单字段，其余忽略。 */
function onSortChange(payload: { prop?: string; order?: string | null }) {
  const allowed = [
    "startedAt",
    "latencyMs",
    "totalTokens",
    "toolCalls",
    "modelCalls"
  ];
  if (!payload.prop || !allowed.includes(payload.prop)) return;
  sortField.value = payload.prop;
  sortOrder.value = payload.order === "ascending" ? "asc" : "desc";
  pageNum.value = 1;
  loadTraces();
}

async function onUserSearch(keyword: string) {
  userSearching.value = true;
  try {
    const res = await searchAiUsers(keyword ?? "");
    userOptions.value = res.code === 0 ? (res.data ?? []) : [];
  } catch {
    // 下拉搜索失败不阻塞主查询；用户仍可手填 ID。
    userOptions.value = [];
  } finally {
    userSearching.value = false;
  }
}

function openDetail(row: TraceListItem) {
  router.push({
    name: "AiObservabilityTraceDetail",
    params: { traceId: row.traceId }
  });
}

/** 加载下拉候选（模型 / 工具 / 状态），取自真实数据。 */
async function loadFilterOptions() {
  try {
    const [from, to] = rangeFromPreset(24 * 30);
    const res = await getAiTraceFilterOptions(from, to);
    if (res.code === 0 && res.data) {
      models.value = res.data.models ?? [];
      tools.value = res.data.tools ?? [];
      statuses.value = res.data.statuses ?? [];
    }
  } catch {
    // 候选加载失败不影响检索本身，只是下拉变空。
  }
}

/** 从总览页跳转过来时带的筛选条件。 */
function applyRouteQuery() {
  const q = route.query;
  if (q.model) form.model = String(q.model);
  if (q.toolName) form.toolName = String(q.toolName);
  if (q.status) form.status = String(q.status);
  if (q.userId) {
    const id = Number(q.userId);
    if (!Number.isNaN(id)) form.userId = id;
  }
  if (q.outputGuarded === "true") form.outputGuarded = true;
  if (q.tokenAvailable === "false") form.tokenAvailable = false;
  if (q.usedTools === "true") form.usedTools = true;
}

onMounted(() => {
  applyRouteQuery();
  loadFilterOptions();
  loadTraces();
});
</script>

<template>
  <div class="trace-search">
    <el-form
      :model="form"
      label-width="86px"
      class="search-form bg-bg_color"
      @submit.prevent
    >
      <el-row :gutter="12">
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="时间范围">
            <el-select
              v-model="form.rangeHours"
              class="w-full"
              placeholder="选择区间"
            >
              <el-option
                v-for="preset in RANGE_PRESETS"
                :key="preset.hours"
                :label="preset.label"
                :value="preset.hours"
              />
              <el-option label="自定义" :value="CUSTOM_RANGE" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col v-if="form.rangeHours === CUSTOM_RANGE" :xs="24" :md="12" :lg="8">
          <el-form-item label="自定义">
            <el-date-picker
              v-model="form.customFrom"
              type="datetime"
              placeholder="起始时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              class="!w-[calc(50%-4px)]"
            />
            <el-date-picker
              v-model="form.customTo"
              type="datetime"
              placeholder="结束时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              class="!w-[calc(50%-4px)] !ml-2"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="用户">
            <el-select
              v-model="form.userId"
              filterable
              remote
              clearable
              reserve-keyword
              placeholder="按账号前缀或 ID 搜索"
              :remote-method="onUserSearch"
              :loading="userSearching"
              class="w-full"
            >
              <el-option
                v-for="item in userOptions"
                :key="item.userId"
                :label="`${item.username}（#${item.userId}）`"
                :value="item.userId"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="状态">
            <el-select
              v-model="form.status"
              clearable
              class="w-full"
              placeholder="全部"
            >
              <el-option
                v-for="item in statuses"
                :key="item"
                :label="statusMeta(item).label"
                :value="item"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="模型">
            <el-select
              v-model="form.model"
              filterable
              allow-create
              clearable
              class="w-full"
              placeholder="全部"
            >
              <el-option
                v-for="item in models"
                :key="item"
                :label="item"
                :value="item"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="工具">
            <el-select
              v-model="form.toolName"
              filterable
              allow-create
              clearable
              class="w-full"
              placeholder="只查调用过该工具的 Trace"
            >
              <el-option
                v-for="item in tools"
                :key="item"
                :label="item"
                :value="item"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="Trace ID">
            <el-input v-model="form.traceId" clearable placeholder="精确匹配" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="会话 ID">
            <el-input v-model="form.chatId" clearable placeholder="精确匹配" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="总 Token">
            <el-input-number
              v-model="form.minTotalTokens"
              :min="0"
              :controls="false"
              placeholder="最小"
              class="!w-[calc(50%-4px)]"
            />
            <el-input-number
              v-model="form.maxTotalTokens"
              :min="0"
              :controls="false"
              placeholder="最大"
              class="!w-[calc(50%-4px)] !ml-2"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="最小耗时">
            <el-input-number
              v-model="form.minLatencyMs"
              :min="0"
              :controls="false"
              placeholder="毫秒"
              class="w-full"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="工具调用">
            <el-radio-group v-model="form.usedTools">
              <el-radio :value="null">全部</el-radio>
              <el-radio :value="true">有调用</el-radio>
              <el-radio :value="false">无调用</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="护栏 / Token">
            <el-checkbox
              :model-value="form.outputGuarded === true"
              @update:model-value="
                value => (form.outputGuarded = value ? true : null)
              "
            >
              只看护栏命中
            </el-checkbox>
            <el-checkbox
              :model-value="form.tokenAvailable === false"
              @update:model-value="
                value => (form.tokenAvailable = value ? false : null)
              "
            >
              只看 Token 不可获取
            </el-checkbox>
          </el-form-item>
        </el-col>
      </el-row>

      <div class="flex items-center justify-between flex-wrap gap-2 pb-3">
        <span class="hint">
          统计区间：{{ rangeHint }}。后端单次查询上限 31 天，分页上限 100
          条/页。
        </span>
        <div>
          <el-button type="primary" :loading="loading" @click="onSearch"
            >查询</el-button
          >
          <el-button :disabled="loading" @click="onReset">重置</el-button>
        </div>
      </div>
    </el-form>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
      class="mb-3"
    />

    <div class="bg-bg_color p-3">
      <div class="flex items-center justify-between mb-2">
        <span class="panel-title">
          共 {{ total }} 条
          <span v-if="searched && rows.length === 0 && !loadError" class="hint">
            · 当前条件下没有请求
          </span>
        </span>
        <el-button :loading="loading" size="small" @click="loadTraces"
          >刷新</el-button
        >
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        size="small"
        row-key="traceId"
        :default-sort="{ prop: 'startedAt', order: 'descending' }"
        empty-text="没有符合条件的 Trace"
        @sort-change="onSortChange"
      >
        <el-table-column
          prop="startedAt"
          label="开始时间"
          width="170"
          sortable="custom"
        >
          <template #default="{ row }">
            {{ dayjs(row.startedAt).format("MM-DD HH:mm:ss") }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.failureMessage"
              :content="row.failureMessage"
              placement="top"
            >
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusMeta(row.status).label }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else :type="statusTagType(row.status)" size="small">
              {{ statusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户" width="130">
          <template #default="{ row }">
            {{ row.username || "—" }}
            <span v-if="row.userId" class="sub">#{{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="110" />
        <el-table-column
          prop="latencyMs"
          label="耗时"
          width="100"
          sortable="custom"
        >
          <template #default="{ row }">{{
            formatDuration(row.latencyMs)
          }}</template>
        </el-table-column>
        <el-table-column
          prop="modelCalls"
          label="模型/工具"
          width="110"
          sortable="custom"
        >
          <template #default="{ row }">
            {{ row.modelCalls ?? "—" }} / {{ row.toolCalls ?? "—" }}
            <el-tooltip
              v-if="row.toolFailures > 0"
              content="有工具调用失败"
              placement="top"
            >
              <span class="warn-dot">{{ row.toolFailures }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column
          prop="totalTokens"
          label="输入 / 输出"
          width="150"
          sortable="custom"
        >
          <template #default="{ row }">
            <span :class="{ muted: row.inputTokens === null }">
              {{ formatTokensCompact(row.inputTokens) }}
            </span>
            /
            <span :class="{ muted: row.outputTokens === null }">
              {{ formatTokensCompact(row.outputTokens) }}
            </span>
            <el-tooltip
              v-if="row.tokenSource !== 'PROVIDER'"
              content="供应商未返回 Token 用量，这里不是 0"
              placement="top"
            >
              <span class="warn-dot">!</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="护栏" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.outputGuarded" type="warning" size="small"
              >命中</el-tag
            >
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="请求摘要" min-width="220">
          <template #default="{ row }">
            <span v-if="row.requestSummary" class="summary">{{
              row.requestSummary
            }}</span>
            <span v-else class="muted">
              {{ row.traced ? "摘要不可用" : "无 Trace 明细" }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Trace ID" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">
              {{ row.traceId }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="flex justify-end mt-3">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.trace-search {
  padding: 12px;
}

.search-form {
  padding: 16px 16px 0;
  margin-bottom: 12px;
  border-radius: 6px;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
}

.sub {
  margin-left: 4px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.warn-dot {
  margin-left: 4px;
  font-size: 12px;
  color: var(--el-color-warning);
}

.summary {
  display: inline-block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
