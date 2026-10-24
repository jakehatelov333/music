/**
 * AI 可观测性页面的纯函数工具。
 *
 * 刻意与 Vue 解耦：这些规则（Token 缺失怎么显示、状态怎么配色、时间区间怎么生成）
 * 是页面里最容易出错、也最容易被"顺手写个 0 兜底"的地方，因此单独成文件并用
 * `node --test` 直接验证，不挂载组件。
 */

/** Token 数：null 表示"供应商没返回"，必须显示为不可获取，绝不能显示成 0。 */
export function formatTokens(value: number | null | undefined): string {
  if (value === null || value === undefined) return "不可获取";
  return value.toLocaleString("en-US");
}

/** 表格里用的紧凑写法；null 仍然保留语义，不退化成 0。 */
export function formatTokensCompact(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return String(value);
}

/** 价格档位边界按千位换算，128000 显示为 128K、1000000 显示为 1M。 */
export function formatPriceTierBoundary(value: number): string {
  if (value >= 1_000_000) return `${value / 1_000_000}M`;
  if (value >= 1_000) return `${value / 1_000}K`;
  return String(value);
}

/** 耗时。 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "—";
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(2)} s`;
}

/**
 * 费用。
 *
 * 未配置单价时返回"未配置价格"，而不是 `¥0.00` —— 后者会让管理员以为调用免费。
 * Token 不可获取时同样说明原因。
 */
export function formatCost(
  cost:
    | {
        configured?: boolean;
        amount?: number | null;
        currency?: string;
        tokenAvailable?: boolean;
      }
    | null
    | undefined
): string {
  if (!cost) return "未配置价格";
  if (!cost.configured) return "未配置价格";
  if (cost.amount === null || cost.amount === undefined) {
    return cost.tokenAvailable === false ? "Token 不可获取" : "—";
  }
  const currency = cost.currency || "CNY";
  const symbol =
    currency === "CNY" ? "¥" : currency === "USD" ? "$" : `${currency} `;
  // 金额很小的时候用更多小数位，避免显示成 ¥0.00 造成"免费"的错觉。
  const value =
    cost.amount < 0.01 ? cost.amount.toFixed(6) : cost.amount.toFixed(4);
  return `${symbol}${value}`;
}

/** 百分比：后端给的是 0~1 的小数。 */
export function formatRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return `${(value * 100).toFixed(2)}%`;
}

export type StatusTone = "success" | "danger" | "warning" | "info";

/**
 * 状态 → 展示标签与颜色。
 *
 * 失败分类（MODEL_ERROR / TOOL_ERROR / …）与成功、超时、中断并列展示，
 * 因为"失败了"远不如"在哪一层失败"有用。
 */
export function statusMeta(status: string | null | undefined): {
  label: string;
  tone: StatusTone;
} {
  switch (status) {
    case "SUCCESS":
      return { label: "成功", tone: "success" };
    case "FAILED":
      return { label: "失败", tone: "danger" };
    case "TIMEOUT":
      return { label: "超时", tone: "warning" };
    case "INTERRUPTED":
      return { label: "中断", tone: "warning" };
    case "MODEL_ERROR":
      return { label: "模型错误", tone: "danger" };
    case "TOOL_ERROR":
      return { label: "工具错误", tone: "danger" };
    case "BUSINESS_ERROR":
      return { label: "业务错误", tone: "danger" };
    case "UNKNOWN":
      return { label: "未知错误", tone: "danger" };
    case "GUARDED":
      return { label: "护栏命中", tone: "warning" };
    case "OK":
      return { label: "成功", tone: "success" };
    case "EMPTY":
      return { label: "空结果", tone: "info" };
    case "REJECTED":
      return { label: "被拒绝", tone: "warning" };
    case "ERROR":
      return { label: "执行失败", tone: "danger" };
    case "RUNNING":
      return { label: "进行中", tone: "info" };
    default:
      return { label: status || "—", tone: "info" };
  }
}

/** Span 类型 → 中文标签。 */
export function spanTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case "REQUEST":
      return "请求";
    case "CONTEXT":
      return "上下文";
    case "MODEL":
      return "模型调用";
    case "TOOL":
      return "工具";
    case "GUARD":
      return "护栏";
    case "FINAL":
      return "输出";
    default:
      return type || "—";
  }
}

/** 动态上下文块的注入状态 → 人话。 */
export function blockStateLabel(state: unknown): string {
  switch (state) {
    case "INJECTED":
      return "已注入";
    case "SKIPPED_EMPTY":
      return "无数据，未注入";
    case "SKIPPED_DISABLED":
      return "个性化已关闭，未注入";
    case "FAILED":
      return "注入失败，已跳过";
    case "NOT_APPLICABLE":
      return "不适用";
    default:
      return state === null || state === undefined ? "—" : String(state);
  }
}

/** 快照完整性 → 提示文案；null 表示完整，无需提示。 */
export function snapshotWarning(
  state: string | null | undefined,
  note?: string | null
): string | null {
  if (!state || state === "FULL") return null;
  if (note && note.trim()) return note;
  switch (state) {
    case "TRUNCATED":
      return "内容超长已截断，不是完整原文";
    case "REDACTED":
      return "命中凭据脱敏规则，敏感值已替换为 ***";
    case "DISABLED":
      return "内容快照已关闭，仅保留元数据";
    default:
      return `快照状态：${state}`;
  }
}

/**
 * 模型调用序号。
 *
 * 后端 `callIndex` 从 1 开始，直接展示；只有缺失时才回退到数组下标。
 * 不要再对已有值加 1，否则第 2 次调用会显示成"第 3 次"。
 */
export function callIndexLabel(
  callIndex: number | null | undefined,
  arrayIndex: number
): string {
  const value =
    typeof callIndex === "number" && Number.isFinite(callIndex)
      ? callIndex
      : arrayIndex + 1;
  return `第 ${value} 次调用`;
}

/**
 * 单次模型调用的增量消息。
 *
 * system 提示词属于整个 Trace 的上下文，统一在"上下文"中展示；
 * 这里只保留该轮新增的 user / assistant / tool 消息，避免同一份长提示词重复三遍。
 */
export function messagesWithoutSystem(
  messages: unknown
): Array<Record<string, any>> | null {
  if (!Array.isArray(messages)) return null;
  return messages.filter(
    message =>
      message &&
      typeof message === "object" &&
      (message as Record<string, any>).role !== "system"
  ) as Array<Record<string, any>>;
}

/** 时间范围快捷项（天）。 */
export const RANGE_PRESETS = [
  { label: "最近 24 小时", hours: 24 },
  { label: "最近 7 天", hours: 24 * 7 },
  { label: "最近 14 天", hours: 24 * 14 },
  { label: "最近 30 天", hours: 24 * 30 }
] as const;

/**
 * 生成后端接受的 ISO 本地时间字符串（不带时区）。
 *
 * 后端用 `LocalDateTime` 接收，带 `Z` 或偏移量会被解析成不同含义，
 * 因此这里刻意只输出 `YYYY-MM-DDTHH:mm:ss`。
 */
export function toIsoLocal(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/** 按小时数生成 [from, to]。 */
export function rangeFromHours(
  hours: number,
  now: Date = new Date()
): [string, string] {
  const from = new Date(now.getTime() - hours * 3600_000);
  return [toIsoLocal(from), toIsoLocal(now)];
}

/**
 * 把检索表单转换成请求体。
 *
 * 空字符串与 null 一律不传：后端用 `!= null` 判断"是否过滤"，
 * 传 `""` 会让条件变成 `= ''`，查不到任何数据；null 虽然等价于不传，
 * 但保留在 body 里只会让请求体变得难以阅读。
 *
 * 注意 `false` 必须保留 —— `usedTools=false` / `outputGuarded=false`
 * 是"只查没有的"，与"不限制"语义完全不同。
 */
export function buildTraceQuery(
  form: Record<string, any>
): Record<string, any> {
  const query: Record<string, any> = {};
  Object.keys(form).forEach(key => {
    const value = form[key];
    if (value === "" || value === undefined || value === null) return;
    query[key] = value;
  });
  return query;
}

/**
 * 把扁平的 Span 列表组装成树。
 *
 * 父节点缺失时挂到根上（而不是丢弃）：宁可让节点出现在错误层级，
 * 也不能让它从界面上消失 —— 那会让人误以为这一步没有发生。
 * 环状父引用同理：从根出发走不到的节点会被提升为根，避免整棵子树不可见。
 */
export function buildSpanTree<
  T extends { spanId: string; parentSpanId?: string | null }
>(spans: T[]): Array<T & { children: any[] }> {
  const nodes = new Map<string, T & { children: any[] }>();
  spans.forEach(span => nodes.set(span.spanId, { ...span, children: [] }));
  const roots: Array<T & { children: any[] }> = [];
  nodes.forEach(node => {
    const parent = node.parentSpanId ? nodes.get(node.parentSpanId) : undefined;
    if (!parent || parent === node) roots.push(node);
    else parent.children.push(node);
  });

  // 兜底：环状引用会让一组节点既不是根、也不在任何根下面，界面上会整段消失。
  // 提升为根之前先把它从父节点的 children 里摘掉，否则父子互指会在渲染时无限递归。
  const reachable = new Set<string>();
  const walk = (node: T & { children: any[] }) => {
    if (reachable.has(node.spanId)) return;
    reachable.add(node.spanId);
    node.children.forEach(walk);
  };
  roots.forEach(walk);
  nodes.forEach(node => {
    if (reachable.has(node.spanId)) return;
    const parent = node.parentSpanId ? nodes.get(node.parentSpanId) : undefined;
    if (parent)
      parent.children = parent.children.filter(child => child !== node);
    roots.push(node);
    walk(node);
  });
  return roots;
}

/**
 * Element Plus tag / alert 允许的类型。
 *
 * 收窄成字面量联合而不是 `string`：`<el-tag :type="...">` 的 prop 类型就是这几个字面量，
 * 返回 `string` 会让模板类型检查失败。
 */
export type TagType = "primary" | "success" | "warning" | "danger" | "info";

/** 状态对应的 Element Plus tag 类型。 */
export function statusTagType(status: string | null | undefined): TagType {
  const tone = statusMeta(status).tone;
  if (tone === "success") return "success";
  if (tone === "danger") return "danger";
  if (tone === "warning") return "warning";
  return "info";
}
