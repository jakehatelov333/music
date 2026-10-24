import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

/**
 * 直接加载可观测性页面的纯函数模块，不挂载 Vue、不启动浏览器。
 *
 * 这些函数里最容易出的错是"用 0 冒充缺失值"和"把空字符串当成有效筛选条件"，
 * 都属于逻辑问题而不是渲染问题，所以用 node:test 单独钉住。
 */
function loadFormat() {
  const source = readFileSync(
    new URL('../src/views/ai-observability/utils/format.ts', import.meta.url),
    'utf8'
  )
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS }
  }).outputText
  const exports = {}
  vm.runInNewContext(js, { exports })
  return exports
}

const format = loadFormat()

// ------------------------------------------------------------------ Token

test('Token 缺失显示为不可获取，而不是 0', () => {
  assert.equal(format.formatTokens(null), '不可获取')
  assert.equal(format.formatTokens(undefined), '不可获取')
  assert.equal(format.formatTokens(0), '0')
  assert.equal(format.formatTokens(12345), '12,345')
})

test('紧凑 Token 保留缺失语义', () => {
  assert.equal(format.formatTokensCompact(null), '—')
  assert.equal(format.formatTokensCompact(0), '0')
  assert.equal(format.formatTokensCompact(1500), '1.5K')
  assert.equal(format.formatTokensCompact(2_500_000), '2.50M')
})

test('价格档位边界按整数 K/M 展示，避免四舍五入改变档位含义', () => {
  assert.equal(format.formatPriceTierBoundary(128_000), '128K')
  assert.equal(format.formatPriceTierBoundary(256_000), '256K')
  assert.equal(format.formatPriceTierBoundary(1_000_000), '1M')
})

// ------------------------------------------------------------------ 费用

test('未配置单价时显示未配置价格，不显示 ¥0.00', () => {
  assert.equal(format.formatCost(null), '未配置价格')
  assert.equal(format.formatCost({ configured: false, amount: null }), '未配置价格')
  assert.equal(format.formatCost({ configured: true, amount: null }), '—')
  assert.equal(
    format.formatCost({ configured: true, amount: null, tokenAvailable: false }),
    'Token 不可获取'
  )
})

test('费用按币种加符号，极小金额提高精度避免看起来免费', () => {
  assert.equal(
    format.formatCost({ configured: true, amount: 1.2345, currency: 'CNY' }),
    '¥1.2345'
  )
  assert.equal(
    format.formatCost({ configured: true, amount: 0.000123, currency: 'USD' }),
    '$0.000123'
  )
})

// ------------------------------------------------------------------ 状态

test('失败分类与成功状态并列给出可读标签', () => {
  // 模块在独立 vm 上下文里执行，对象原型不同，用字段比较而不是 deepEqual。
  const meta = status => {
    const value = format.statusMeta(status)
    return [value.label, value.tone].join('|')
  }
  assert.equal(meta('SUCCESS'), '成功|success')
  assert.equal(meta('MODEL_ERROR'), '模型错误|danger')
  assert.equal(meta('INTERRUPTED'), '中断|warning')
  assert.equal(meta('EMPTY'), '空结果|info')
  assert.equal(format.statusMeta(undefined).label, '—')
  assert.equal(format.statusTagType('TIMEOUT'), 'warning')
  assert.equal(format.statusTagType('SUCCESS'), 'success')
})

test('快照状态非 FULL 时必须给出提示，不能当完整原文', () => {
  assert.equal(format.snapshotWarning('FULL', null), null)
  assert.equal(format.snapshotWarning(null, null), null)
  assert.match(format.snapshotWarning('TRUNCATED', null), /截断/)
  assert.match(format.snapshotWarning('REDACTED', null), /脱敏/)
  assert.match(format.snapshotWarning('DISABLED', null), /已关闭/)
  assert.equal(format.snapshotWarning('TRUNCATED', '自定义说明'), '自定义说明')
})

test('模型调用序号直接使用后端从 1 开始的值，不再加 1', () => {
  assert.equal(format.callIndexLabel(1, 0), '第 1 次调用')
  assert.equal(format.callIndexLabel(2, 1), '第 2 次调用')
  assert.equal(format.callIndexLabel(null, 1), '第 2 次调用')
  assert.equal(format.callIndexLabel(undefined, 0), '第 1 次调用')
})

test('模型调用增量消息排除 system，避免提示词重复展示', () => {
  const messages = [
    { role: 'system', text: '很长的系统提示词' },
    { role: 'user', text: '推荐几首周杰伦的歌' },
    { role: 'assistant', toolRequests: [{ name: 'searchSongsByArtist' }] },
    { role: 'tool', text: '{"ok":true}' }
  ]
  const visible = format.messagesWithoutSystem(messages)
  assert.equal(visible.length, 3)
  assert.equal(visible.some(message => message.role === 'system'), false)
  assert.equal(format.messagesWithoutSystem({ role: 'system' }), null)
})

test('动态块状态翻译成人话', () => {
  assert.equal(format.blockStateLabel('INJECTED'), '已注入')
  assert.equal(format.blockStateLabel('SKIPPED_EMPTY'), '无数据，未注入')
  assert.equal(format.blockStateLabel('SKIPPED_DISABLED'), '个性化已关闭，未注入')
  assert.equal(format.blockStateLabel('FAILED'), '注入失败，已跳过')
  assert.equal(format.blockStateLabel(undefined), '—')
})

// ------------------------------------------------------------------ 时间与请求

test('时间字符串不带时区偏移，避免后端按另一种含义解析', () => {
  const value = format.toIsoLocal(new Date(2026, 9, 24, 8, 5, 9))
  assert.equal(value, '2026-10-24T08:05:09')
})

test('时间区间按小时回推，结束时间是当前时刻', () => {
  const now = new Date(2026, 9, 24, 12, 0, 0)
  const [from, to] = format.rangeFromHours(24, now)
  assert.equal(to, '2026-10-24T12:00:00')
  assert.equal(from, '2026-10-23T12:00:00')
})

test('空字符串不进入请求体（否则后端会按 = \'\' 过滤）', () => {
  const query = format.buildTraceQuery({
    traceId: '',
    chatId: '  ',
    model: 'qwen-flash',
    userId: null,
    pageNum: 1
  })
  assert.equal('traceId' in query, false)
  assert.equal('userId' in query, false)
  assert.equal(query.model, 'qwen-flash')
  assert.equal(query.pageNum, 1)
  // 空白字符串保留原样交给后端 trim 判断，不在这里静默改写语义。
  assert.equal(query.chatId, '  ')
})

// ------------------------------------------------------------------ 执行树

test('执行树按真实 parentSpanId 组装', () => {
  const spans = [
    { spanId: 'root', parentSpanId: null, name: 'request' },
    { spanId: 'model-0', parentSpanId: 'root', name: 'llm' },
    { spanId: 'tool-0', parentSpanId: 'model-0', name: 'tool' },
    { spanId: 'guard', parentSpanId: 'root', name: 'guard' }
  ]
  const roots = format.buildSpanTree(spans)
  assert.equal(roots.length, 1)
  assert.equal(roots[0].spanId, 'root')
  assert.equal(roots[0].children.length, 2)
  const model = roots[0].children.find(child => child.spanId === 'model-0')
  assert.equal(model.children[0].spanId, 'tool-0')
})

test('父节点缺失的节点挂到根上，而不是从界面上消失', () => {
  const roots = format.buildSpanTree([
    { spanId: 'orphan', parentSpanId: 'missing', name: 'x' }
  ])
  assert.equal(roots.length, 1)
  assert.equal(roots[0].spanId, 'orphan')
})

test('环状父引用不会导致节点丢失或无限递归', () => {
  const roots = format.buildSpanTree([
    { spanId: 'a', parentSpanId: 'b', name: 'a' },
    { spanId: 'b', parentSpanId: 'a', name: 'b' }
  ])
  const collected = []
  const walk = nodes => nodes.forEach(node => {
    collected.push(node.spanId)
    walk(node.children)
  })
  walk(roots)
  assert.equal(collected.sort().join(','), 'a,b')
})

// ------------------------------------------------------------------ 其它

test('耗时格式区分毫秒与秒', () => {
  assert.equal(format.formatDuration(null), '—')
  assert.equal(format.formatDuration(320), '320 ms')
  assert.equal(format.formatDuration(2500), '2.50 s')
})

test('比率按百分比展示，缺失值不显示为 0%', () => {
  assert.equal(format.formatRate(null), '—')
  assert.equal(format.formatRate(0.1234), '12.34%')
})

test('Span 类型有中文标签，未知类型原样返回', () => {
  assert.equal(format.spanTypeLabel('MODEL'), '模型调用')
  assert.equal(format.spanTypeLabel('TOOL'), '工具')
  assert.equal(format.spanTypeLabel('CUSTOM'), 'CUSTOM')
})
