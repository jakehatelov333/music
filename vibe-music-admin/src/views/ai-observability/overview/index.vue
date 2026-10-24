<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useDark, useECharts } from "@pureadmin/utils";
import { InfoFilled } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import { message } from "@/utils/message";
import { getAiOverview, type AiOverview } from "@/api/aiObservability";
import {
  RANGE_PRESETS,
  formatCost,
  formatDuration,
  formatRate,
  formatTokens,
  formatTokensCompact,
  rangeFromHours,
  statusMeta,
  statusTagType,
  formatPriceTierBoundary
} from "../utils/format";

defineOptions({
  name: "AiObservabilityOverview"
});

const router = useRouter();
const { isDark } = useDark();
const theme = computed(() => (isDark.value ? "dark" : "light"));

const loading = ref(false);
const loadError = ref<string | null>(null);
const overview = ref<AiOverview | null>(null);
const rangeHours = ref<number>(24 * 7);

const rangeLabel = computed(
  () =>
    RANGE_PRESETS.find(item => item.hours === rangeHours.value)?.label ??
    "自定义"
);

async function load() {
  loading.value = true;
  loadError.value = null;
  try {
    const [from, to] = rangeFromHours(rangeHours.value);
    const res = await getAiOverview(from, to);
    if (res.code !== 0) {
      loadError.value = res.message || "加载失败";
      overview.value = null;
      message(res.message || "加载失败", { type: "error" });
      return;
    }
    overview.value = res.data;
    // 图表容器在 v-else 分支里，必须先等它渲染出来再初始化 ECharts，
    // 否则 ref 还是空的（切换时间范围时尤其明显）。
    await nextTick();
    renderCharts();
  } catch (error: any) {
    // 401/403 是权限问题，必须如实提示，不能显示成"没有数据"。
    const status = error?.response?.status;
    loadError.value =
      status === 403
        ? "没有权限访问 AI 可观测性数据（需要管理员账号）"
        : status === 401
          ? "登录已过期，请重新登录"
          : "接口请求失败，请稍后重试";
    overview.value = null;
  } finally {
    loading.value = false;
  }
}

// ---------------------------------------------------------------- 图表

const trendRef = ref();
const { setOptions: setTrendOptions } = useECharts(trendRef, { theme });
const modelRef = ref();
const { setOptions: setModelOptions } = useECharts(modelRef, { theme });
const guardRef = ref();
const { setOptions: setGuardOptions } = useECharts(guardRef, { theme });

function renderCharts() {
  const data = overview.value;
  if (!data) return;

  const buckets = data.trend?.map(point => point.bucket) ?? [];
  setTrendOptions({
    tooltip: { trigger: "axis" },
    legend: { data: ["输入 Token", "输出 Token"], bottom: 0 },
    grid: { top: 24, left: 60, right: 20, bottom: 48 },
    xAxis: { type: "category", data: buckets, axisLabel: { fontSize: 11 } },
    yAxis: { type: "value", splitLine: { show: false } },
    series: [
      {
        name: "输入 Token",
        type: "line",
        smooth: true,
        symbol: "none",
        areaStyle: { opacity: 0.12 },
        itemStyle: { color: "#41b6ff" },
        data: data.trend?.map(point => point.inputTokens) ?? []
      },
      {
        name: "输出 Token",
        type: "line",
        smooth: true,
        symbol: "none",
        areaStyle: { opacity: 0.12 },
        itemStyle: { color: "#e85f33" },
        data: data.trend?.map(point => point.outputTokens) ?? []
      }
    ]
  });

  const models = data.models ?? [];
  setModelOptions({
    tooltip: { trigger: "axis" },
    legend: { data: ["请求数", "失败率"], bottom: 0 },
    grid: { top: 24, left: 60, right: 60, bottom: 48 },
    xAxis: { type: "category", data: models.map(item => item.model) },
    yAxis: [
      { type: "value", splitLine: { show: false } },
      {
        type: "value",
        axisLabel: { formatter: "{value}%" },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: "请求数",
        type: "bar",
        barWidth: 32,
        itemStyle: { color: "#41b6ff", borderRadius: [8, 8, 0, 0] },
        data: models.map(item => item.requests)
      },
      {
        name: "失败率",
        type: "line",
        yAxisIndex: 1,
        itemStyle: { color: "#e85f33" },
        data: models.map(item =>
          Number(((item.failureRate ?? 0) * 100).toFixed(2))
        )
      }
    ]
  });

  const guardPoints = data.guardTrend ?? [];
  setGuardOptions({
    tooltip: { trigger: "axis" },
    grid: { top: 24, left: 60, right: 20, bottom: 48 },
    xAxis: {
      type: "category",
      data: guardPoints.map(point => point.bucket),
      axisLabel: { fontSize: 11 }
    },
    yAxis: { type: "value", splitLine: { show: false } },
    series: [
      {
        name: "护栏命中",
        type: "bar",
        barWidth: 18,
        itemStyle: { color: "#f7b500", borderRadius: [6, 6, 0, 0] },
        data: guardPoints.map(point => point.guarded)
      }
    ]
  });
}

function changeRange(hours: number) {
  rangeHours.value = hours;
  load();
}

function openTrace(traceId: string) {
  router.push({ name: "AiObservabilityTraceDetail", params: { traceId } });
}

/** 跳到 Trace 检索页并带上筛选条件（用户 / 模型 / 护栏命中）。 */
function openTraces(filter: Record<string, any>) {
  router.push({ name: "AiObservabilityTraces", query: filter });
}

onMounted(load);
</script>

<template>
  <div class="ai-overview">
    <!-- 筛选与口径说明 -->
    <div class="toolbar bg-bg_color">
      <div class="flex items-center gap-2 flex-wrap">
        <el-radio-group
          :model-value="rangeHours"
          size="default"
          @update:model-value="changeRange"
        >
          <el-radio-button
            v-for="preset in RANGE_PRESETS"
            :key="preset.hours"
            :value="preset.hours"
          >
            {{ preset.label }}
          </el-radio-button>
        </el-radio-group>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <div v-if="overview" class="scope">
        <el-icon><InfoFilled /></el-icon>
        <span>{{ overview.scope }}</span>
      </div>
    </div>

    <!-- 权限 / 接口错误 -->
    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
      class="mb-3"
    />

    <!-- 追踪系统自身健康度 -->
    <el-alert
      v-if="
        overview &&
        (!overview.traceEnabled || overview.traceCaptureFailures > 0)
      "
      type="warning"
      show-icon
      :closable="false"
      class="mb-3"
      :title="
        !overview.traceEnabled
          ? 'Trace 采集已关闭（ai.trace.enabled=false）：只有汇总指标，没有执行树明细。'
          : `追踪采集失败 ${overview.traceCaptureFailures} 次：部分请求可能缺少执行树明细。`
      "
    />

    <el-skeleton :loading="loading && !overview" :rows="8" animated>
      <template #default>
        <div v-if="!overview" class="empty-state">
          <el-empty description="暂无数据" />
        </div>

        <template v-else>
          <!-- 顶部指标 -->
          <el-row :gutter="12">
            <el-col
              v-for="card in [
                {
                  label: '请求总数',
                  value: overview.totalRequests,
                  hint: rangeLabel
                },
                {
                  label: '成功',
                  value: overview.successRequests,
                  tone: 'success'
                },
                {
                  label: '失败',
                  value: overview.failedRequests,
                  tone: 'danger'
                },
                {
                  label: '超时 / 中断',
                  value: `${overview.timeoutRequests} / ${overview.interruptedRequests}`,
                  tone: 'warning'
                },
                {
                  label: '输入 Token',
                  value: formatTokensCompact(overview.inputTokens)
                },
                {
                  label: '输出 Token',
                  value: formatTokensCompact(overview.outputTokens)
                }
              ]"
              :key="card.label"
              :xs="12"
              :sm="8"
              :md="6"
              :lg="4"
              class="mb-3"
            >
              <div class="metric-card bg-bg_color">
                <div class="metric-label">{{ card.label }}</div>
                <div
                  :class="['metric-value', card.tone && `tone-${card.tone}`]"
                >
                  {{ card.value }}
                </div>
                <div v-if="card.hint" class="metric-hint">{{ card.hint }}</div>
              </div>
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <el-col
              v-for="card in [
                {
                  label: 'Token 不可获取',
                  value: overview.tokenUnavailable,
                  hint: '未拿到供应商用量，不按 0 计'
                },
                { label: '模型调用', value: overview.modelCalls },
                {
                  label: '工具调用 / 失败',
                  value: `${overview.toolCalls} / ${overview.toolFailures}`
                },
                {
                  label: '平均耗时',
                  value: formatDuration(overview.avgLatencyMs)
                },
                {
                  label: 'P50 / P95',
                  value: `${formatDuration(overview.p50LatencyMs)} / ${formatDuration(overview.p95LatencyMs)}`
                },
                {
                  label: '护栏命中',
                  value: `${overview.guardedRequests}（${formatRate(overview.guardedRate)}）`,
                  tone: 'warning'
                }
              ]"
              :key="card.label"
              :xs="12"
              :sm="8"
              :md="6"
              :lg="4"
              class="mb-3"
            >
              <div class="metric-card bg-bg_color">
                <div class="metric-label">{{ card.label }}</div>
                <div
                  :class="['metric-value', card.tone && `tone-${card.tone}`]"
                >
                  {{ card.value }}
                </div>
                <div v-if="card.hint" class="metric-hint">{{ card.hint }}</div>
              </div>
            </el-col>
          </el-row>

          <!-- 估算费用 -->
          <div class="panel bg-bg_color mb-3">
            <div class="panel-header">
              <span class="panel-title">估算费用</span>
              <el-tag
                size="small"
                :type="overview.cost.configured ? 'info' : 'warning'"
              >
                {{ overview.cost.configured ? "按配置单价估算" : "未配置单价" }}
              </el-tag>
            </div>
            <div class="cost-body">
              <div class="cost-amount">{{ formatCost(overview.cost) }}</div>
              <div class="cost-note">
                {{ overview.cost.note }}
                <template v-if="overview.cost.priceVersion">
                  · 价格版本 {{ overview.cost.priceVersion }}
                </template>
              </div>
              <div v-if="overview.cost.tier" class="cost-note">
                计价方式：{{ overview.cost.tier }} · 已计价
                {{ overview.cost.pricedCalls }} 次模型调用
                <template v-if="overview.cost.unpricedCalls">
                  · 未计价 {{ overview.cost.unpricedCalls }} 次
                </template>
              </div>
              <div
                v-if="overview.cost.tiers?.length"
                class="cost-note cost-tiers"
              >
                <span
                  v-for="tier in overview.cost.tiers"
                  :key="tier.maxInputTokens"
                  class="cost-tier"
                >
                  输入 ≤ {{ formatPriceTierBoundary(tier.maxInputTokens) }}：¥{{
                    tier.inputPricePerMillion
                  }}
                  / ¥{{ tier.outputPricePerMillion }}（输入/输出，每百万 Token）
                </span>
              </div>
              <div v-if="overview.cost.sourceUrl" class="cost-note">
                官方价格：
                <a
                  :href="overview.cost.sourceUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  阿里云百炼模型调用价格
                </a>
                <template v-if="overview.cost.sourceUpdatedAt">
                  （页面更新于 {{ overview.cost.sourceUpdatedAt }}）
                </template>
              </div>
              <div class="cost-note">
                这是按 Token 单价推算的估算值，<strong>不是供应商账单</strong>。
              </div>
            </div>
          </div>

          <!-- 趋势 -->
          <div class="panel bg-bg_color mb-3">
            <div class="panel-header">
              <span class="panel-title">
                Token 趋势（按{{
                  overview.trendGranularity === "HOUR" ? "小时" : "天"
                }}）
              </span>
            </div>
            <div v-if="!overview.trend?.length" class="chart-empty">
              <el-empty description="该时间段没有请求" :image-size="60" />
            </div>
            <div v-else ref="trendRef" class="chart" />
          </div>

          <el-row :gutter="12">
            <el-col :xs="24" :lg="12" class="mb-3">
              <div class="panel bg-bg_color h-full">
                <div class="panel-header">
                  <span class="panel-title">按模型</span>
                </div>
                <div v-if="!overview.models?.length" class="chart-empty">
                  <el-empty description="暂无数据" :image-size="60" />
                </div>
                <div v-else ref="modelRef" class="chart" />
              </div>
            </el-col>
            <el-col :xs="24" :lg="12" class="mb-3">
              <div class="panel bg-bg_color h-full">
                <div class="panel-header">
                  <span class="panel-title">输出护栏命中趋势</span>
                </div>
                <div v-if="!overview.guardTrend?.length" class="chart-empty">
                  <el-empty description="暂无数据" :image-size="60" />
                </div>
                <div v-else ref="guardRef" class="chart" />
              </div>
            </el-col>
          </el-row>

          <!-- 表格区 -->
          <el-row :gutter="12">
            <el-col :xs="24" :lg="12" class="mb-3">
              <div class="panel bg-bg_color h-full">
                <div class="panel-header">
                  <span class="panel-title">模型用量</span>
                </div>
                <el-table
                  :data="overview.models"
                  size="small"
                  empty-text="暂无数据"
                >
                  <el-table-column prop="model" label="模型" min-width="120" />
                  <el-table-column prop="requests" label="请求" width="80" />
                  <el-table-column label="输入 / 输出" min-width="140">
                    <template #default="{ row }">
                      {{ formatTokensCompact(row.inputTokens) }} /
                      {{ formatTokensCompact(row.outputTokens) }}
                    </template>
                  </el-table-column>
                  <el-table-column label="平均耗时" width="110">
                    <template #default="{ row }">{{
                      formatDuration(row.avgLatencyMs)
                    }}</template>
                  </el-table-column>
                  <el-table-column label="失败率" width="90">
                    <template #default="{ row }">{{
                      formatRate(row.failureRate)
                    }}</template>
                  </el-table-column>
                  <el-table-column label="估算费用" width="110">
                    <template #default="{ row }">
                      {{
                        row.costConfigured
                          ? formatCost({
                              configured: true,
                              amount: row.cost,
                              currency: overview.cost.currency
                            })
                          : "未配置"
                      }}
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="80" fixed="right">
                    <template #default="{ row }">
                      <el-button
                        link
                        type="primary"
                        @click="openTraces({ model: row.model })"
                      >
                        查看
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </el-col>

            <el-col :xs="24" :lg="12" class="mb-3">
              <div class="panel bg-bg_color h-full">
                <div class="panel-header">
                  <span class="panel-title">用户消耗排行（高消耗优先）</span>
                </div>
                <el-table
                  :data="overview.users"
                  size="small"
                  empty-text="暂无数据"
                >
                  <el-table-column label="用户" min-width="140">
                    <template #default="{ row }">
                      {{ row.username || "—" }}
                      <span class="sub">#{{ row.userId }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="requests" label="请求" width="80" />
                  <el-table-column label="总 Token" width="110">
                    <template #default="{ row }">{{
                      formatTokens(row.totalTokens)
                    }}</template>
                  </el-table-column>
                  <el-table-column label="每请求 Token" width="120">
                    <template #default="{ row }">
                      {{ formatTokens(row.avgTokensPerRequest) }}
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="80" fixed="right">
                    <template #default="{ row }">
                      <el-button
                        link
                        type="primary"
                        @click="openTraces({ userId: row.userId })"
                      >
                        查看
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <el-col :xs="24" :lg="12" class="mb-3">
              <div class="panel bg-bg_color h-full">
                <div class="panel-header">
                  <span class="panel-title">工具调用</span>
                  <span class="panel-sub">来自真实执行记录，不是模型声称</span>
                </div>
                <el-table
                  :data="overview.tools"
                  size="small"
                  empty-text="暂无数据"
                >
                  <el-table-column
                    prop="toolName"
                    label="工具"
                    min-width="160"
                  />
                  <el-table-column prop="calls" label="调用" width="80" />
                  <el-table-column prop="failures" label="失败" width="80" />
                  <el-table-column label="成功率" width="90">
                    <template #default="{ row }">{{
                      formatRate(row.successRate)
                    }}</template>
                  </el-table-column>
                  <el-table-column label="平均耗时" width="110">
                    <template #default="{ row }">{{
                      formatDuration(row.avgDurationMs)
                    }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="80" fixed="right">
                    <template #default="{ row }">
                      <el-button
                        link
                        type="primary"
                        @click="openTraces({ toolName: row.toolName })"
                      >
                        查看
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </el-col>

            <el-col :xs="24" :lg="12" class="mb-3">
              <div class="panel bg-bg_color h-full">
                <div class="panel-header">
                  <span class="panel-title">失败类别分布</span>
                </div>
                <el-table
                  :data="overview.failures"
                  size="small"
                  empty-text="没有失败请求"
                >
                  <el-table-column label="类别" min-width="160">
                    <template #default="{ row }">
                      <el-tag :type="statusTagType(row.category)" size="small">
                        {{ statusMeta(row.category).label }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="count" label="次数" width="100" />
                  <el-table-column label="操作" width="80" fixed="right">
                    <template #default="{ row }">
                      <el-button
                        link
                        type="primary"
                        @click="openTraces({ status: row.category })"
                      >
                        查看
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </el-col>
          </el-row>

          <div class="panel bg-bg_color">
            <div class="panel-header">
              <span class="panel-title">最慢请求</span>
            </div>
            <el-table
              :data="overview.slowest"
              size="small"
              empty-text="暂无数据"
            >
              <el-table-column label="开始时间" width="180">
                <template #default="{ row }">
                  {{ dayjs(row.startedAt).format("YYYY-MM-DD HH:mm:ss") }}
                </template>
              </el-table-column>
              <el-table-column label="用户" min-width="130">
                <template #default="{ row }">
                  {{ row.username || "—" }}
                  <span v-if="row.userId" class="sub">#{{ row.userId }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="model" label="模型" min-width="120" />
              <el-table-column label="耗时" width="110">
                <template #default="{ row }">{{
                  formatDuration(row.latencyMs)
                }}</template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)" size="small">
                    {{ statusMeta(row.status).label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Trace ID" min-width="220">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="openTrace(row.traceId)"
                  >
                    {{ row.traceId }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped lang="scss">
.ai-overview {
  padding: 12px;
}

.toolbar {
  padding: 12px 16px;
  margin-bottom: 12px;
  border-radius: 6px;
}

.scope {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.metric-card {
  padding: 14px 16px;
  border-radius: 6px;
}

.metric-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.metric-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.metric-value.tone-success {
  color: var(--el-color-success);
}

.metric-value.tone-danger {
  color: var(--el-color-danger);
}

.metric-value.tone-warning {
  color: var(--el-color-warning);
}

.metric-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.panel {
  padding: 12px 16px;
  border-radius: 6px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.panel-sub {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.chart {
  width: 100%;
  height: 280px;
}

.chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 220px;
}

.cost-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cost-amount {
  font-size: 26px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.cost-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cost-tiers {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
}

.cost-tier {
  white-space: nowrap;
}

.empty-state {
  padding: 40px 0;
}

.sub {
  margin-left: 4px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
