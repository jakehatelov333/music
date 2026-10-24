import { http } from "@/utils/http";
import { getToken } from "@/utils/auth";

/**
 * AI 可观测性接口。
 *
 * 全部走 `/admin/ai/**`，由后端 LoginInterceptor + RolePermissionManager 强制 ROLE_ADMIN。
 * 前端不做权限判断，只在 401/403 时如实提示 —— 权限的最终依据永远在服务端。
 */

export type CostInfo = {
  configured: boolean;
  amount: number | null;
  currency: string;
  priceVersion?: string;
  inputPricePerMillion?: number | null;
  outputPricePerMillion?: number | null;
  tiers?: Array<{
    minInputTokens: number;
    maxInputTokens: number;
    inputPricePerMillion: number;
    outputPricePerMillion: number;
  }>;
  tier?: string;
  pricedCalls: number;
  unpricedCalls: number;
  tokenAvailable: boolean;
  note?: string;
  sourceUrl?: string;
  sourceUpdatedAt?: string;
};

export type TrendPoint = {
  bucket: string;
  requests: number;
  inputTokens: number;
  outputTokens: number;
  guarded: number;
};

export type ModelStat = {
  model: string;
  requests: number;
  inputTokens: number;
  outputTokens: number;
  avgLatencyMs: number | null;
  failureRate: number;
  cost: number | null;
  costConfigured: boolean;
};

export type UserStat = {
  userId: number | null;
  username: string | null;
  requests: number;
  totalTokens: number;
  avgTokensPerRequest: number | null;
};

export type ToolStat = {
  toolName: string;
  calls: number;
  failures: number;
  successRate: number;
  failureRate: number;
  avgDurationMs: number | null;
};

export type FailureStat = { category: string; count: number };

export type SlowRequest = {
  traceId: string;
  model: string;
  latencyMs: number;
  status: string;
  startedAt: string;
  userId: number | null;
  username: string | null;
};

export type AiOverview = {
  from: string;
  to: string;
  /** 统计覆盖范围说明，必须原样展示给管理员。 */
  scope: string;
  totalRequests: number;
  successRequests: number;
  failedRequests: number;
  timeoutRequests: number;
  interruptedRequests: number;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  tokenUnavailable: number;
  modelCalls: number;
  toolCalls: number;
  toolFailures: number;
  avgLatencyMs: number | null;
  p50LatencyMs: number | null;
  p95LatencyMs: number | null;
  guardedRequests: number;
  guardedRate: number | null;
  cost: CostInfo;
  trend: TrendPoint[];
  trendGranularity: string;
  guardTrend: TrendPoint[];
  models: ModelStat[];
  users: UserStat[];
  tools: ToolStat[];
  failures: FailureStat[];
  slowest: SlowRequest[];
  traceCaptureFailures: number;
  traceEnabled: boolean;
  traceContentCaptured: boolean;
};

export type TraceQuery = {
  from?: string | null;
  to?: string | null;
  userId?: number | null;
  username?: string | null;
  traceId?: string | null;
  chatId?: string | null;
  status?: string | null;
  model?: string | null;
  toolName?: string | null;
  minInputTokens?: number | null;
  maxInputTokens?: number | null;
  minOutputTokens?: number | null;
  maxOutputTokens?: number | null;
  minTotalTokens?: number | null;
  maxTotalTokens?: number | null;
  minLatencyMs?: number | null;
  failureCategory?: string | null;
  usedTools?: boolean | null;
  outputGuarded?: boolean | null;
  tokenAvailable?: boolean | null;
  sortField?: string | null;
  order?: string | null;
  pageNum?: number;
  pageSize?: number;
};

export type TraceListItem = {
  traceId: string;
  chatId: string;
  userId: number | null;
  username: string | null;
  status: string;
  failureCategory: string | null;
  failureMessage: string | null;
  model: string;
  modelCalls: number;
  toolCalls: number;
  toolFailures: number;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number;
  tokenSource: string;
  latencyMs: number;
  outputGuarded: boolean;
  startedAt: string;
  endedAt: string;
  /** 已脱敏的请求摘要；可能为 null。 */
  requestSummary: string | null;
  traced: boolean;
};

export type SpanNode = {
  spanId: string;
  parentSpanId: string | null;
  spanType: string;
  name: string;
  status: string;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  tokenSource: string | null;
  errorCategory: string | null;
  errorMessage: string | null;
  attributes: Record<string, any> | null;
  hasDetail: boolean;
  children: SpanNode[];
};

export type LlmCall = {
  spanId: string;
  callIndex: number;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  tokenSource: string | null;
  durationMs: number | null;
  status: string | null;
  errorCategory: string | null;
  errorMessage: string | null;
  systemMessage: string | null;
  promptHash: string | null;
  promptVersion: string | null;
  effectivePrompt: string | null;
  basePrompt: string | null;
  blocks: Record<string, any> | null;
  messages: any;
  tools: any;
  outputText: string | null;
  toolRequests: any;
  finishReason: string | null;
  snapshotState: string;
  snapshotNote: string | null;
  createdAt: string;
};

export type ToolCallDetail = {
  spanId: string;
  toolName: string;
  modelToolCallId: string | null;
  serverToolCallId: string | null;
  rawArguments: string | null;
  parsedArguments: any;
  status: string;
  resultCode: string | null;
  resultTotal: number | null;
  resultSummary: string | null;
  resultPayload: any;
  errorCategory: string | null;
  durationMs: number | null;
  snapshotState: string;
  snapshotNote: string | null;
  createdAt: string;
};

export type TraceDetail = {
  traceId: string;
  chatId: string | null;
  userId: number | null;
  username: string | null;
  status: string;
  failureCategory: string | null;
  failureMessage: string | null;
  model: string | null;
  startedAt: string | null;
  endedAt: string | null;
  latencyMs: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  tokenSource: string | null;
  modelCalls: number | null;
  toolCalls: number | null;
  toolFailures: number | null;
  outputGuarded: boolean | null;
  requestSummary: string | null;
  context: Record<string, any> | null;
  cost: CostInfo;
  spans: SpanNode[];
  llmCalls: LlmCall[];
  toolCallsDetail: ToolCallDetail[];
  traceNote: string | null;
  consistency: Record<string, any> | null;
};

type Result<T> = { code: number; message: string; data: T };

const authHeaders = () => {
  const userData = getToken();
  return {
    "Content-Type": "application/json",
    Authorization: userData?.accessToken ?? ""
  };
};

/** ISO-8601 本地时间，后端用 LocalDateTime 接收，避免时区歧义。 */
const iso = (value?: string | null) => (value ? value : undefined);

export const getAiOverview = (from?: string | null, to?: string | null) =>
  http.request<Result<AiOverview>>("get", "/admin/ai/overview", {
    headers: authHeaders(),
    params: { from: iso(from), to: iso(to) }
  });

export const searchAiTraces = (query: TraceQuery) =>
  http.request<Result<{ total: number; items: TraceListItem[] }>>(
    "post",
    "/admin/ai/traces",
    { headers: authHeaders(), data: query }
  );

export const getAiTraceDetail = (traceId: string) =>
  http.request<Result<TraceDetail>>(
    "get",
    `/admin/ai/traces/${encodeURIComponent(traceId)}`,
    { headers: authHeaders() }
  );

export const getAiSpanDetail = (traceId: string, spanId: string) =>
  http.request<Result<Record<string, any>>>(
    "get",
    `/admin/ai/traces/${encodeURIComponent(traceId)}/spans/${encodeURIComponent(spanId)}`,
    { headers: authHeaders() }
  );

export const getAiTraceFilterOptions = (
  from?: string | null,
  to?: string | null
) =>
  http.request<
    Result<{
      models: string[];
      tools: string[];
      statuses: string[];
      failureCategories: string[];
    }>
  >("get", "/admin/ai/filter-options", {
    headers: authHeaders(),
    params: { from: iso(from), to: iso(to) }
  });

/** 用户下拉：后端按账号前缀或 ID 搜索，不加载全表。 */
export const searchAiUsers = (keyword: string) =>
  http.request<Result<Array<{ userId: number; username: string }>>>(
    "get",
    "/admin/ai/users/options",
    { headers: authHeaders(), params: { keyword } }
  );
