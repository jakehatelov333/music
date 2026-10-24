<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { CircleCheck } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import {
  getAiTraceDetail,
  type LlmCall,
  type SpanNode,
  type ToolCallDetail,
  type TraceDetail
} from "@/api/aiObservability";
import {
  blockStateLabel,
  callIndexLabel,
  formatCost,
  formatDuration,
  formatTokens,
  messagesWithoutSystem,
  snapshotWarning,
  spanTypeLabel,
  statusMeta,
  statusTagType,
  type TagType
} from "../utils/format";

defineOptions({
  name: "AiObservabilityTraceDetail"
});

const route = useRoute();
const router = useRouter();
const traceId = computed(() => String(route.params.traceId ?? ""));

const loading = ref(false);
const loadError = ref<string | null>(null);
const detail = ref<TraceDetail | null>(null);

const activeTab = ref("tree");
const llmExpand = ref<string[]>([]);

async function load() {
  if (!traceId.value) return;
  loading.value = true;
  loadError.value = null;
  try {
    const res = await getAiTraceDetail(traceId.value);
    if (res.code !== 0) {
      loadError.value = res.message || "加载失败";
      detail.value = null;
      return;
    }
    detail.value = res.data;
    // 默认展开第一次模型调用的明细：排查时最先要看的就是"模型收到了什么"。
    llmExpand.value = res.data?.llmCalls?.length
      ? [res.data.llmCalls[0].spanId]
      : [];
  } catch (error: any) {
    const status = error?.response?.status;
    loadError.value =
      status === 404
        ? "该 Trace 不存在，或已被保留策略清理"
        : status === 403
          ? "没有权限访问 AI 可观测性数据（需要管理员账号）"
          : status === 401
            ? "登录已过期，请重新登录"
            : "接口请求失败，请稍后重试";
    detail.value = null;
  } finally {
    loading.value = false;
  }
}

// ---------------------------------------------------------------- 派生数据

const treeData = computed(() => detail.value?.spans ?? []);

const consistency = computed<Record<string, any> | null>(() => {
  const value = detail.value?.consistency;
  return value && typeof value === "object"
    ? (value as Record<string, any>)
    : null;
});

const consistencyMismatch = computed(
  () =>
    consistency.value?.comparable === true &&
    consistency.value?.consistent === false
);

/** 每个 LLM 调用的快照警告（截断 / 脱敏 / 关闭采集）。 */
function llmWarning(call: LlmCall): string | null {
  return snapshotWarning(call.snapshotState, call.snapshotNote);
}

function toolWarning(call: ToolCallDetail): string | null {
  return snapshotWarning(call.snapshotState, call.snapshotNote);
}

function onSpanClick(span: SpanNode) {
  // 点到模型调用 / 工具节点时，顺手切到对应 Tab，减少一次手动切换。
  if (span.spanType === "MODEL") activeTab.value = "llm";
  else if (span.spanType === "TOOL") activeTab.value = "tool";
  else activeTab.value = "tree";
  if (span.spanType === "MODEL" && !llmExpand.value.includes(span.spanId)) {
    llmExpand.value = [...llmExpand.value, span.spanId];
  }
}

/** 把任意 JSON 值格式化展示；解析失败时原样输出，不静默吞掉。 */
function pretty(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string") return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

/** 消息列表在页面上按角色分组展示；结构不符合预期时退化为原始 JSON。 */
function messagesOf(call: LlmCall): Array<Record<string, any>> | null {
  return messagesWithoutSystem(call.messages);
}

function toolRequestsOf(call: LlmCall): Array<Record<string, any>> | null {
  return Array.isArray(call.toolRequests)
    ? (call.toolRequests as Array<Record<string, any>>)
    : null;
}

function toolsOf(call: LlmCall): Array<Record<string, any>> | null {
  return Array.isArray(call.tools)
    ? (call.tools as Array<Record<string, any>>)
    : null;
}

function blocksOf(
  call: LlmCall
): Array<{ key: string; label: string; state: string }> {
  const blocks = call.blocks;
  if (!blocks || typeof blocks !== "object") return [];
  const labels: Record<string, string> = {
    base: "基础提示词",
    summary: "会话摘要",
    profile: "音乐画像",
    manualPreference: "手动偏好",
    guest: "游客身份",
    personalizationSwitch: "个性化开关",
    summaryError: "摘要错误",
    profileError: "画像错误",
    manualPreferenceError: "偏好错误"
  };
  return Object.keys(blocks).map(key => ({
    key,
    label: labels[key] ?? key,
    state: String((blocks as Record<string, any>)[key])
  }));
}

function blockTagType(state: string): TagType {
  if (state === "INJECTED") return "success";
  if (state === "FAILED") return "danger";
  if (state === "SKIPPED_DISABLED") return "warning";
  return "info";
}

/** 角色 → 中文；未知角色原样显示。 */
function roleLabel(role: unknown): string {
  switch (role) {
    case "system":
      return "system";
    case "user":
      return "user";
    case "ai":
    case "assistant":
      return "assistant";
    case "toolExecutionResult":
    case "tool":
      return "tool";
    default:
      return String(role ?? "—");
  }
}

function backToList() {
  router.push({ name: "AiObservabilityTraces" });
}

onMounted(load);
</script>

<template>
  <div class="trace-detail">
    <div class="flex items-center justify-between mb-3">
      <div class="flex items-center gap-2">
        <el-button size="small" @click="backToList">返回检索</el-button>
        <span class="title">Trace 详情</span>
        <el-tag v-if="detail" :type="statusTagType(detail.status)" size="small">
          {{ statusMeta(detail.status).label }}
        </el-tag>
        <el-tag v-if="detail?.outputGuarded" type="warning" size="small"
          >护栏命中</el-tag
        >
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
      class="mb-3"
    />

    <el-skeleton :loading="loading && !detail" :rows="10" animated>
      <template #default>
        <el-empty v-if="!detail" description="暂无数据" />

        <template v-else>
          <!-- 汇总 -->
          <div class="panel bg-bg_color mb-3">
            <el-descriptions :column="4" border size="small">
              <el-descriptions-item label="Trace ID" :span="2">
                <span class="mono">{{ detail.traceId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="会话 ID" :span="2">
                <span class="mono">{{ detail.chatId || "—" }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="用户">
                {{ detail.username || "—" }}
                <span v-if="detail.userId" class="sub"
                  >#{{ detail.userId }}</span
                >
              </el-descriptions-item>
              <el-descriptions-item label="模型">{{
                detail.model || "—"
              }}</el-descriptions-item>
              <el-descriptions-item label="开始时间">
                {{
                  detail.startedAt
                    ? dayjs(detail.startedAt).format("YYYY-MM-DD HH:mm:ss.SSS")
                    : "—"
                }}
              </el-descriptions-item>
              <el-descriptions-item label="耗时">
                {{ formatDuration(detail.latencyMs) }}
              </el-descriptions-item>
              <el-descriptions-item label="输入 Token">
                {{ formatTokens(detail.inputTokens) }}
              </el-descriptions-item>
              <el-descriptions-item label="输出 Token">
                {{ formatTokens(detail.outputTokens) }}
              </el-descriptions-item>
              <el-descriptions-item label="Token 来源">
                <el-tag
                  :type="
                    detail.tokenSource === 'PROVIDER' ? 'success' : 'warning'
                  "
                  size="small"
                >
                  {{
                    detail.tokenSource === "PROVIDER"
                      ? "供应商返回"
                      : "不可获取"
                  }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="模型 / 工具调用">
                {{ detail.modelCalls ?? "—" }} / {{ detail.toolCalls ?? "—" }}
                <span v-if="detail.toolFailures" class="danger">
                  （失败 {{ detail.toolFailures }}）
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="估算费用">
                {{ formatCost(detail.cost) }}
                <div
                  v-if="detail.cost?.configured && detail.cost.tier"
                  class="cost-detail"
                >
                  {{ detail.cost.tier }} · 已计价
                  {{ detail.cost.pricedCalls }} 次
                  <template v-if="detail.cost.unpricedCalls">
                    · 未计价 {{ detail.cost.unpricedCalls }} 次
                  </template>
                </div>
              </el-descriptions-item>
              <el-descriptions-item label="请求摘要" :span="2">
                {{ detail.requestSummary || "—" }}
              </el-descriptions-item>
            </el-descriptions>

            <div
              v-if="detail.failureCategory || detail.failureMessage"
              class="failure"
            >
              <el-tag type="danger" size="small">{{
                detail.failureCategory || "FAILED"
              }}</el-tag>
              <span class="failure-message">{{
                detail.failureMessage || "无错误详情"
              }}</span>
            </div>
          </div>

          <!-- 完整性 / 一致性提示：不允许静默 -->
          <el-alert
            v-if="detail.traceNote"
            :title="detail.traceNote"
            type="warning"
            show-icon
            :closable="false"
            class="mb-3"
          />
          <el-alert
            v-if="consistencyMismatch"
            type="warning"
            show-icon
            :closable="false"
            class="mb-3"
            :title="consistency?.note || '任务汇总与逐次调用明细不一致'"
          >
            <div class="consistency">
              任务汇总：输入
              {{ formatTokens(consistency?.metricInputTokens) }} / 输出
              {{
                formatTokens(consistency?.metricOutputTokens)
              }}；逐次调用合计：输入
              {{ formatTokens(consistency?.spanInputTokens) }} / 输出
              {{ formatTokens(consistency?.spanOutputTokens) }}。
            </div>
          </el-alert>
          <div
            v-else-if="consistency?.comparable === true"
            class="consistency-ok mb-3"
          >
            <el-icon><CircleCheck /></el-icon>
            <span>{{ consistency.note }}</span>
          </div>

          <el-tabs v-model="activeTab" class="detail-tabs">
            <!-- 执行树 -->
            <el-tab-pane label="执行树" name="tree">
              <div class="bg-bg_color p-3">
                <el-empty
                  v-if="!treeData.length"
                  description="该 Trace 没有执行树明细（原因见上方提示）"
                />
                <el-tree
                  v-else
                  :data="treeData"
                  node-key="spanId"
                  default-expand-all
                  :expand-on-click-node="false"
                  @node-click="onSpanClick"
                >
                  <template #default="{ data }">
                    <div class="tree-node">
                      <el-tag size="small" effect="plain">
                        {{ spanTypeLabel(data.spanType) }}
                      </el-tag>
                      <span class="tree-name">{{ data.name }}</span>
                      <el-tag
                        v-if="data.status && data.status !== 'OK'"
                        :type="statusTagType(data.status)"
                        size="small"
                      >
                        {{ statusMeta(data.status).label }}
                      </el-tag>
                      <span class="tree-meta">
                        {{ formatDuration(data.durationMs) }}
                        <template
                          v-if="
                            data.inputTokens !== null ||
                            data.outputTokens !== null
                          "
                        >
                          · {{ formatTokens(data.inputTokens) }} /
                          {{ formatTokens(data.outputTokens) }} tokens
                        </template>
                        <template v-if="data.errorMessage">
                          · <span class="danger">{{ data.errorMessage }}</span>
                        </template>
                      </span>
                    </div>
                  </template>
                </el-tree>
              </div>
            </el-tab-pane>

            <!-- 模型调用 -->
            <el-tab-pane
              :label="`模型调用（${detail.llmCalls?.length ?? 0}）`"
              name="llm"
            >
              <el-empty
                v-if="!detail.llmCalls?.length"
                description="没有模型调用明细"
              />
              <el-collapse v-else v-model="llmExpand">
                <el-collapse-item
                  v-for="(call, callArrayIndex) in detail.llmCalls"
                  :key="call.spanId"
                  :name="call.spanId"
                >
                  <template #title>
                    <div class="call-title">
                      <span class="call-index"
                        >{{ callIndexLabel(call.callIndex, callArrayIndex) }}</span
                      >
                      <el-tag size="small" :type="statusTagType(call.status)">
                        {{ statusMeta(call.status).label }}
                      </el-tag>
                      <span class="tree-meta">
                        {{ formatDuration(call.durationMs) }} · 输入
                        {{ formatTokens(call.inputTokens) }} / 输出
                        {{ formatTokens(call.outputTokens) }}
                        <template v-if="call.finishReason">
                          · finish={{ call.finishReason }}</template
                        >
                      </span>
                    </div>
                  </template>

                  <el-alert
                    v-if="llmWarning(call)"
                    :title="llmWarning(call)!"
                    type="warning"
                    show-icon
                    :closable="false"
                    class="mb-2"
                  />

                  <div class="prompt-summary mb-2">
                    <div>
                      <span class="block-title">系统提示词</span>
                      <span class="block-hint">
                        版本 {{ call.promptVersion || "—" }} · 哈希
                        <span class="mono">{{
                          (call.promptHash || "—").slice(0, 12)
                        }}</span>
                        · {{ call.effectivePrompt?.length ?? 0 }} 字符
                      </span>
                    </div>
                    <el-tag
                      v-for="block in blocksOf(call)"
                      :key="block.key"
                      size="small"
                      :type="blockTagType(block.state)"
                    >
                      {{ block.label }}：{{ blockStateLabel(block.state) }}
                    </el-tag>
                    <el-button link type="primary" @click="activeTab = 'context'">
                      查看完整提示词
                    </el-button>
                  </div>

                  <div class="block mb-2">
                    <div class="block-title">
                      本轮新增消息
                      <span class="block-hint"
                        >不含 system；系统提示词见“上下文”</span
                      >
                    </div>
                    <template v-if="messagesOf(call)">
                      <div
                        v-for="(msg, index) in messagesOf(call)"
                        :key="index"
                        class="message"
                      >
                        <div class="message-role">
                          {{ roleLabel(msg.role) }}
                        </div>
                        <pre class="code">{{ msg.text ?? "—" }}</pre>
                        <div v-if="msg.toolRequests" class="message-extra">
                          <span class="block-hint">该消息携带工具请求：</span>
                          <pre class="code">{{ pretty(msg.toolRequests) }}</pre>
                        </div>
                        <div v-if="msg.toolCallId" class="message-extra">
                          <span class="block-hint">
                            工具结果 · {{ msg.toolName }} · toolCallId={{
                              msg.toolCallId
                            }}
                          </span>
                        </div>
                      </div>
                    </template>
                    <pre v-else class="code">{{ pretty(call.messages) }}</pre>
                  </div>

                  <el-row :gutter="12" class="mt-2">
                    <el-col :xs="24" :lg="12">
                      <div class="block">
                        <div class="block-title">模型输出</div>
                        <pre v-if="call.outputText" class="code">{{
                          call.outputText
                        }}</pre>
                        <el-empty
                          v-else
                          description="本次调用没有文本输出"
                          :image-size="50"
                        />
                      </div>
                    </el-col>
                    <el-col :xs="24" :lg="12">
                      <div class="block">
                        <div class="block-title">
                          模型的工具调用请求（原始）
                          <span class="block-hint"
                            >模型侧 ID 与后端 span ID 不同，见工具 Tab</span
                          >
                        </div>
                        <template v-if="toolRequestsOf(call)?.length">
                          <pre class="code">{{
                            pretty(toolRequestsOf(call))
                          }}</pre>
                        </template>
                        <el-empty
                          v-else
                          description="本次调用没有请求工具"
                          :image-size="50"
                        />
                      </div>
                    </el-col>
                  </el-row>

                  <div class="block mt-2">
                    <div class="block-title">
                      模型可见的工具定义（{{ toolsOf(call)?.length ?? 0 }} 个）
                    </div>
                    <el-collapse v-if="toolsOf(call)?.length">
                      <el-collapse-item
                        v-for="tool in toolsOf(call)"
                        :key="tool.name"
                        :title="tool.name"
                      >
                        <div class="block-hint">{{ tool.description }}</div>
                        <pre class="code">{{ pretty(tool.parameters) }}</pre>
                      </el-collapse-item>
                    </el-collapse>
                    <el-empty
                      v-else
                      description="本次调用没有提供工具"
                      :image-size="50"
                    />
                  </div>
                </el-collapse-item>
              </el-collapse>
            </el-tab-pane>

            <!-- 工具调用 -->
            <el-tab-pane
              :label="`工具调用（${detail.toolCallsDetail?.length ?? 0}）`"
              name="tool"
            >
              <el-empty
                v-if="!detail.toolCallsDetail?.length"
                description="没有工具调用明细"
              />
              <div
                v-for="call in detail.toolCallsDetail"
                :key="call.spanId"
                class="block bg-bg_color mb-3"
              >
                <div class="block-title">
                  {{ call.toolName }}
                  <el-tag size="small" :type="statusTagType(call.status)">
                    {{ statusMeta(call.status).label }}
                  </el-tag>
                  <el-tag v-if="call.resultCode" size="small" effect="plain">
                    {{ call.resultCode }}
                  </el-tag>
                  <span class="tree-meta">
                    {{ formatDuration(call.durationMs) }}
                    <template v-if="call.resultTotal !== null">
                      · 返回 {{ call.resultTotal }} 条
                    </template>
                  </span>
                </div>

                <el-alert
                  v-if="toolWarning(call)"
                  :title="toolWarning(call)!"
                  type="warning"
                  show-icon
                  :closable="false"
                  class="mb-2"
                />

                <el-descriptions :column="2" border size="small" class="mb-2">
                  <el-descriptions-item label="模型侧 Tool Call ID">
                    <span class="mono">{{ call.modelToolCallId || "—" }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item
                    label="服务端 Tool Call ID（= SSE toolCallId）"
                  >
                    <span class="mono">{{ call.serverToolCallId || "—" }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="Span ID">
                    <span class="mono">{{ call.spanId }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="结果摘要">
                    {{ call.resultSummary || "—" }}
                  </el-descriptions-item>
                </el-descriptions>

                <el-row :gutter="12">
                  <el-col :xs="24" :lg="8">
                    <div class="block-title">模型原始参数</div>
                    <pre class="code">{{ call.rawArguments || "—" }}</pre>
                  </el-col>
                  <el-col :xs="24" :lg="8">
                    <div class="block-title">服务端解析后参数</div>
                    <pre class="code">{{ pretty(call.parsedArguments) }}</pre>
                  </el-col>
                  <el-col :xs="24" :lg="8">
                    <div class="block-title">返回数据（已脱敏 / 截断）</div>
                    <pre class="code">{{ pretty(call.resultPayload) }}</pre>
                  </el-col>
                </el-row>
              </div>
            </el-tab-pane>

            <!-- 上下文 -->
            <el-tab-pane label="上下文" name="context">
              <div class="bg-bg_color p-3">
                <el-empty
                  v-if="!detail.context"
                  description="没有上下文快照（采集关闭或该请求无 Trace）"
                />
                <template v-else>
                  <el-descriptions :column="2" border size="small" class="mb-2">
                    <el-descriptions-item label="基础提示词版本">
                      {{ detail.context.basePromptVersion || "—" }}
                    </el-descriptions-item>
                    <el-descriptions-item label="基础提示词哈希">
                      <span class="mono">{{
                        detail.context.basePromptHash || "—"
                      }}</span>
                    </el-descriptions-item>
                    <el-descriptions-item label="生效提示词长度">
                      {{ detail.context.effectivePromptChars ?? "—" }}
                    </el-descriptions-item>
                    <el-descriptions-item label="基础提示词长度">
                      {{ detail.context.basePromptChars ?? "—" }}
                    </el-descriptions-item>
                  </el-descriptions>
                  <div class="block-states mb-2">
                    <el-tag
                      v-for="(state, key) in detail.context.blocks || {}"
                      :key="key"
                      size="small"
                      :type="blockTagType(String(state))"
                    >
                      {{ key }}：{{ blockStateLabel(state) }}
                    </el-tag>
                  </div>
                  <pre v-if="detail.context.effectivePrompt" class="code">{{
                    detail.context.effectivePrompt
                  }}</pre>
                  <el-alert
                    v-else
                    title="内容快照已关闭，仅保留长度与哈希"
                    type="info"
                    show-icon
                    :closable="false"
                  />
                </template>
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped lang="scss">
.trace-detail {
  padding: 12px;
}

.title {
  font-size: 15px;
  font-weight: 600;
}

.panel {
  padding: 12px 16px;
  border-radius: 6px;
}

.mono {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}

.sub {
  margin-left: 4px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.danger {
  color: var(--el-color-danger);
}

.failure {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-top: 10px;
}

.failure-message {
  font-size: 13px;
  color: var(--el-color-danger);
}

.cost-detail {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.consistency {
  margin-top: 4px;
  font-size: 12px;
}

.consistency-ok {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  color: var(--el-color-success);
}

.detail-tabs {
  background: transparent;
}

.tree-node {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

.tree-name {
  font-weight: 500;
}

.tree-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.call-title {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

.call-index {
  font-weight: 600;
}

.block {
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.block-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
}

.block-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.block-states {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}

.code {
  max-height: 420px;
  padding: 8px;
  margin: 0;
  overflow: auto;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.message {
  margin-bottom: 8px;
}

.message-role {
  margin-bottom: 2px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-color-primary);
}

.message-extra {
  margin-top: 4px;
}

.prompt-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.mt-2 {
  margin-top: 8px;
}
</style>
