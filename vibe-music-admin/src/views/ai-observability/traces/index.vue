<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useWindowSize } from "@vueuse/core";
import dayjs from "dayjs";
import {
  Calendar,
  Download,
  InfoFilled,
  Refresh
} from "@element-plus/icons-vue";
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
  anomalyFilterFromQuery,
  anomalyFilterQuery,
  buildTraceQuery,
  formatDuration,
  formatTokensCompact,
  statusMeta,
  statusTagType,
  toIsoLocal,
  type AnomalyFilter
} from "../utils/format";

defineOptions({
  name: "AiObservabilityTraces"
});

const route = useRoute();
const router = useRouter();
const { width: viewportWidth } = useWindowSize();

const CUSTOM_RANGE = "custom";
// 管理端左侧导航会占用约 200px，1280px 以下桌面表格已无法完整容纳所有列。
const isCompactLayout = computed(() => viewportWidth.value < 1280);

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
  anomalyFilter: AnomalyFilter;
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
  anomalyFilter: ""
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

/** 紧凑布局下用一个下拉保留表格的排序能力。 */
const compactSort = computed({
  get() {
    if (sortField.value === "startedAt") {
      return `startedAt:${sortOrder.value}`;
    }
    return `${sortField.value}:desc`;
  },
  set(value: string) {
    onCompactSortChange(value);
  }
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
    ...anomalyFilterQuery(form.anomalyFilter),
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
  form.anomalyFilter = "";
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

function shortTraceId(traceId: string): string {
  return traceId.length > 20 ? `${traceId.slice(0, 18)}…` : traceId;
}

/** 当前页导出。CSV 单元格统一加引号，并防止表格软件把内容当公式执行。 */
function exportCurrentPage() {
  if (!rows.value.length) {
    message("当前页没有可导出的数据", { type: "warning" });
    return;
  }
  const headers = [
    "开始时间",
    "状态",
    "用户",
    "模型",
    "耗时",
    "模型调用",
    "工具调用",
    "输入 Token",
    "输出 Token",
    "总 Token",
    "护栏",
    "请求摘要",
    "Trace ID"
  ];
  const data = rows.value.map(row => [
    dayjs(row.startedAt).format("YYYY-MM-DD HH:mm:ss"),
    statusMeta(row.status).label,
    row.username || "",
    row.model,
    formatDuration(row.latencyMs),
    row.modelCalls ?? "",
    row.toolCalls ?? "",
    row.inputTokens ?? "",
    row.outputTokens ?? "",
    row.totalTokens ?? "",
    row.outputGuarded ? "命中" : "",
    row.requestSummary || "",
    row.traceId
  ]);
  const escapeCell = (value: unknown) => {
    let text = value === null || value === undefined ? "" : String(value);
    if (/^[=+\-@]/.test(text)) text = `'${text}`;
    return `"${text.replace(/"/g, '""')}"`;
  };
  const csv = `\uFEFF${[headers, ...data]
    .map(row => row.map(escapeCell).join(","))
    .join("\r\n")}`;
  const url = URL.createObjectURL(
    new Blob([csv], { type: "text/csv;charset=utf-8" })
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = `llm-traces-${dayjs().format("YYYYMMDD-HHmmss")}.csv`;
  link.click();
  URL.revokeObjectURL(url);
  message(`已导出当前页 ${rows.value.length} 条记录`, { type: "success" });
}

function onCompactSortChange(value: string) {
  const [field, order] = value.split(":");
  sortField.value = field;
  sortOrder.value = order === "asc" ? "asc" : "desc";
  pageNum.value = 1;
  loadTraces();
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
  form.anomalyFilter = anomalyFilterFromQuery(q);
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
    <header class="page-heading">
      <div>
        <h2 class="page-heading__title">LLM 调用记录</h2>
        <p class="page-heading__subtitle">查看模型请求、工具调用与 Token 消耗</p>
      </div>
      <div class="page-heading__actions">
        <el-button :icon="Download" @click="exportCurrentPage">导出</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadTraces">
          刷新
        </el-button>
      </div>
    </header>

    <el-form
      :model="form"
      :label-width="isCompactLayout ? '88px' : '96px'"
      class="search-form bg-bg_color"
      @submit.prevent
    >
      <el-row :gutter="24">
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="时间范围">
            <el-select
              v-model="form.rangeHours"
              class="w-full"
              placeholder="选择区间"
              :prefix-icon="Calendar"
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
        <el-col
          v-if="form.rangeHours === CUSTOM_RANGE"
          :xs="24"
          :md="24"
          :lg="24"
        >
          <el-form-item label="自定义">
            <div class="custom-range">
              <el-date-picker
                v-model="form.customFrom"
                type="datetime"
                placeholder="起始时间"
                value-format="YYYY-MM-DDTHH:mm:ss"
                class="custom-range__picker"
              />
              <span class="custom-range__separator">至</span>
              <el-date-picker
                v-model="form.customTo"
                type="datetime"
                placeholder="结束时间"
                value-format="YYYY-MM-DDTHH:mm:ss"
                class="custom-range__picker"
              />
            </div>
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
            <div class="field-stack">
              <el-select
                v-model="form.toolName"
                filterable
                allow-create
                clearable
                class="w-full"
                placeholder="全部工具"
              >
                <el-option
                  v-for="item in tools"
                  :key="item"
                  :label="item"
                  :value="item"
                />
              </el-select>
              <div class="field-hint">
                显示系统注册的全部工具；选中后只匹配实际调用过该工具的 Trace
              </div>
            </div>
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
            <div class="token-range">
              <el-input-number
                v-model="form.minTotalTokens"
                :min="0"
                :controls="false"
                placeholder="最小"
                class="token-range__input"
              />
              <span class="token-range__separator">-</span>
              <el-input-number
                v-model="form.maxTotalTokens"
                :min="0"
                :controls="false"
                placeholder="最大"
                class="token-range__input"
              />
            </div>
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
            <el-radio-group v-model="form.usedTools" class="tool-usage">
              <el-radio :value="null">全部</el-radio>
              <el-radio :value="true">有调用</el-radio>
              <el-radio :value="false">无调用</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12" :lg="8">
          <el-form-item label="异常筛选">
            <el-select
              v-model="form.anomalyFilter"
              class="w-full"
              placeholder="全部"
            >
              <el-option label="全部" value="" />
              <el-option label="护栏命中" value="guarded" />
              <el-option label="Token 不可获取" value="tokenUnavailable" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <div class="form-footer">
        <div class="form-footer__hint">
          <span>统计区间：{{ rangeHint }}</span>
          <span class="form-footer__dot">·</span>
          <span>单次查询最长 31 天</span>
          <span class="form-footer__dot">·</span>
          <span>每页最多 100 条</span>
          <el-tooltip
            placement="top"
            content="总 Token 是单次请求内所有模型调用的输入 + 输出之和；不是整个时间范围的总量，也不用于费用档位计算。"
          >
            <el-icon class="form-footer__help"><InfoFilled /></el-icon>
          </el-tooltip>
        </div>
        <div class="form-footer__actions">
          <el-button :disabled="loading" @click="onReset">重置</el-button>
          <el-button type="primary" :loading="loading" @click="onSearch">
            查询
          </el-button>
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

    <section class="results-panel bg-bg_color">
      <div class="results-panel__header">
        <div class="results-panel__title">
          <span>调用记录</span>
          <span class="results-panel__count">{{ total }} 条</span>
          <span
            v-if="searched && rows.length === 0 && !loadError"
            class="hint"
          >
            当前条件下没有请求
          </span>
        </div>
        <el-button
          :icon="Refresh"
          :loading="loading"
          size="small"
          @click="loadTraces"
        >
          刷新
        </el-button>
      </div>

      <div v-if="isCompactLayout && rows.length" class="compact-tools">
        <span class="compact-tools__label">排序</span>
        <el-select v-model="compactSort" size="small" class="compact-tools__sort">
          <el-option label="开始时间（新→旧）" value="startedAt:desc" />
          <el-option label="开始时间（旧→新）" value="startedAt:asc" />
          <el-option label="耗时（长→短）" value="latencyMs:desc" />
          <el-option label="耗时（短→长）" value="latencyMs:asc" />
          <el-option label="总 Token（多→少）" value="totalTokens:desc" />
          <el-option label="总 Token（少→多）" value="totalTokens:asc" />
          <el-option label="模型调用（多→少）" value="modelCalls:desc" />
          <el-option label="工具调用（多→少）" value="toolCalls:desc" />
        </el-select>
      </div>

      <div
        v-if="isCompactLayout"
        v-loading="loading"
        class="trace-cards"
        :class="{ 'trace-cards--empty': !rows.length }"
      >
        <el-empty
          v-if="!rows.length"
          :description="loading ? '加载中…' : '没有符合条件的 Trace'"
        />
        <article
          v-for="row in rows"
          :key="row.traceId"
          class="trace-card"
        >
          <div class="trace-card__top">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusMeta(row.status).label }}
            </el-tag>
            <span class="trace-card__time">
              {{ dayjs(row.startedAt).format("MM-DD HH:mm:ss") }}
            </span>
            <el-tag
              v-if="row.outputGuarded"
              type="warning"
              size="small"
              effect="plain"
            >
              护栏命中
            </el-tag>
          </div>
          <div class="trace-card__summary">
            {{ row.requestSummary || (row.traced ? "摘要不可用" : "无 Trace 明细") }}
          </div>
          <dl class="trace-card__grid">
            <div>
              <dt>用户</dt>
              <dd>
                {{ row.username || "—" }}
                <span v-if="row.userId" class="sub">#{{ row.userId }}</span>
              </dd>
            </div>
            <div>
              <dt>模型</dt>
              <dd>{{ row.model }}</dd>
            </div>
            <div>
              <dt>耗时</dt>
              <dd>{{ formatDuration(row.latencyMs) }}</dd>
            </div>
            <div>
              <dt>模型 / 工具</dt>
              <dd>{{ row.modelCalls ?? "—" }} / {{ row.toolCalls ?? "—" }}</dd>
            </div>
            <div>
              <dt>输入 / 输出</dt>
              <dd>
                {{ formatTokensCompact(row.inputTokens) }} /
                {{ formatTokensCompact(row.outputTokens) }}
              </dd>
            </div>
          </dl>
          <div class="trace-card__footer">
            <span class="trace-card__id" :title="row.traceId">
              {{ shortTraceId(row.traceId) }}
            </span>
            <el-button link type="primary" @click="openDetail(row)">
              查看详情
            </el-button>
          </div>
        </article>
      </div>

      <el-table
        v-else
        v-loading="loading"
        :data="rows"
        size="small"
        table-layout="fixed"
        row-key="traceId"
        :default-sort="{ prop: 'startedAt', order: 'descending' }"
        empty-text="没有符合条件的 Trace"
        @sort-change="onSortChange"
      >
        <el-table-column
          prop="startedAt"
          label="开始时间"
          width="124"
          sortable="custom"
        >
          <template #default="{ row }">
            {{ dayjs(row.startedAt).format("MM-DD HH:mm:ss") }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="68">
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
        <el-table-column label="用户" width="104">
          <template #default="{ row }">
            {{ row.username || "—" }}
            <span v-if="row.userId" class="sub">#{{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" width="86" />
        <el-table-column
          prop="latencyMs"
          label="耗时"
          width="76"
          sortable="custom"
        >
          <template #default="{ row }">{{ formatDuration(row.latencyMs) }}</template>
        </el-table-column>
        <el-table-column
          prop="modelCalls"
          label="模型/工具"
          width="82"
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
          label="输入/输出 Token"
          width="116"
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
        <el-table-column label="护栏" width="60">
          <template #default="{ row }">
            <el-tag v-if="row.outputGuarded" type="warning" size="small">
              命中
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="请求摘要" min-width="110">
          <template #default="{ row }">
            <span v-if="row.requestSummary" class="summary" :title="row.requestSummary">
              {{ row.requestSummary }}
            </span>
            <span v-else class="muted">
              {{ row.traced ? "摘要不可用" : "无 Trace 明细" }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Trace ID" min-width="140">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              class="trace-id"
              :title="row.traceId"
              @click="openDetail(row)"
            >
              {{ shortTraceId(row.traceId) }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="results-panel__pagination">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          :layout="isCompactLayout ? 'prev, pager, next' : 'total, sizes, prev, pager, next'"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.trace-search {
  padding: 20px 24px 24px;
}

.page-heading {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.page-heading__title {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
  line-height: 1.35;
  color: var(--el-text-color-primary);
}

.page-heading__subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.page-heading__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.page-heading__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.search-form {
  padding: 22px 24px 18px;
  margin-bottom: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.search-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.search-form :deep(.el-form-item__label) {
  justify-content: flex-start;
  font-weight: 500;
  color: var(--el-text-color-regular);
}

.field-stack {
  width: 100%;
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.custom-range,
.token-range {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}

.custom-range__picker,
.token-range__input {
  flex: 1;
  min-width: 0;
}

.custom-range__separator,
.token-range__separator {
  flex: none;
  color: var(--el-text-color-placeholder);
}

.tool-usage {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
}

.tool-usage :deep(.el-radio) {
  margin-right: 0;
}

.form-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding-top: 4px;
}

.form-footer__hint {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.form-footer__dot {
  color: var(--el-text-color-placeholder);
}

.form-footer__help {
  margin-left: 2px;
  cursor: help;
}

.form-footer__actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.form-footer__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.results-panel {
  padding: 16px 20px 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.results-panel__header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.results-panel__title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.results-panel__count {
  padding: 1px 7px;
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 10px;
}

.compact-tools {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.compact-tools__label {
  flex: none;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.compact-tools__sort {
  width: 210px;
  max-width: 100%;
}

.trace-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  min-height: 120px;
}

.trace-cards--empty {
  display: block;
}

.trace-card {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.trace-card__top,
.trace-card__footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.trace-card__time {
  margin-right: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.trace-card__summary {
  margin: 10px 0;
  overflow: hidden;
  font-size: 14px;
  line-height: 1.55;
  color: var(--el-text-color-primary);
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.trace-card__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;
  margin: 0 0 12px;
}

.trace-card__grid div {
  min-width: 0;
}

.trace-card__grid dt {
  margin-bottom: 2px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.trace-card__grid dd {
  margin: 0;
  overflow: hidden;
  font-size: 13px;
  color: var(--el-text-color-regular);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-card__footer {
  justify-content: space-between;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.trace-card__id {
  min-width: 0;
  overflow: hidden;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.results-panel__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  overflow-x: auto;
}

.hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
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
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.trace-id {
  max-width: 100%;
  font-family: var(--el-font-family-mono, monospace);
}

.trace-id :deep(span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 991px) {
  .trace-search {
    padding: 16px;
  }

  .page-heading__title {
    font-size: 20px;
  }

  .search-form {
    padding: 18px 18px 16px;
  }

  .results-panel {
    padding: 14px 16px 16px;
  }
}

@media (max-width: 768px) {
  .trace-search {
    padding: 12px;
  }

  .page-heading {
    align-items: flex-start;
  }

  .page-heading__actions {
    width: 100%;
  }

  .page-heading__actions :deep(.el-button) {
    flex: 1;
  }

  .search-form {
    padding: 16px 14px 14px;
  }

  .search-form :deep(.el-form-item) {
    margin-bottom: 16px;
  }

  .form-footer {
    align-items: stretch;
  }

  .form-footer__actions {
    width: 100%;
    margin-left: 0;
  }

  .form-footer__actions :deep(.el-button) {
    flex: 1;
  }

  .trace-cards {
    grid-template-columns: minmax(0, 1fr);
  }

  .results-panel__pagination {
    justify-content: center;
  }

  .compact-tools__sort {
    flex: 1;
    width: auto;
  }
}

@media (max-width: 480px) {
  .custom-range {
    flex-wrap: wrap;
  }

  .custom-range__picker {
    flex-basis: 100%;
  }

  .custom-range__separator {
    display: none;
  }

  .page-heading__subtitle {
    font-size: 12px;
  }

  .trace-card__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .results-panel__pagination :deep(.el-pagination__total) {
    display: none;
  }
}
</style>
