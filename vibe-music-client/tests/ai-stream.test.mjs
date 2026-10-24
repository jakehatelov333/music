import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

// 直接加载业务模块，隔离 HTTP 与 Pinia；不启动浏览器或真实模型。
function loadApi(fetchMock) {
  const source = readFileSync(new URL('../src/api/ai.ts', import.meta.url), 'utf8')
    .replace('import.meta.env.VITE_AI_BASE', 'undefined')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(js, {
    exports, fetch: fetchMock, TextDecoder,
    require(name) {
      if (name === '@/stores/modules/user') return { UserStore: () => ({ userInfo: { token: 'test-token' } }) }
      if (name === '@/utils/http') return { http: () => {} }
      if (name === '@/utils/media') return { normalizeMediaUrls: value => value }
      throw new Error(`Unexpected dependency: ${name}`)
    },
  })
  return exports
}

function streamResponse(chunks) {
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(new TextEncoder().encode(chunk))
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

test('POST keeps content out of the URL and preserves chunked multiline SSE whitespace', async () => {
  let request
  const api = loadApi(async (url, options) => {
    request = { url, options }
    return streamResponse(['event: to', 'ken\r\ndata:  hello \r\ndata: world\r\n\r\n', 'event: done\ndata:\n\n'])
  })
  const tokens = []
  await new Promise((resolve, reject) => api.aiStreamChat('私人消息', {
    onToken: token => tokens.push(token), onDone: resolve, onError: reject,
  }))
  assert.equal(request.url, '/ai/chat/stream')
  assert.equal(request.options.method, 'POST')
  assert.equal(request.options.credentials, 'include')
  assert.equal(request.options.headers.Authorization, 'test-token')
  assert.deepEqual(JSON.parse(request.options.body), { query: '私人消息' })
  assert.deepEqual(tokens, [' hello \nworld'])
})

test('HTTP rate limit responses expose the friendly server message', async () => {
  const api = loadApi(async () => new Response(JSON.stringify({ message: '请求过于频繁' }), { status: 429 }))
  const error = await new Promise(resolve => api.aiStreamChat('hello', {
    onToken: () => {}, onDone: () => assert.fail('unexpected completion'), onError: resolve,
  }))
  assert.equal(error.message, '请求过于频繁')
})

test('EOF without a terminal event reports an interrupted response', async () => {
  const api = loadApi(async () => streamResponse(['event: token\ndata: half\n\n']))
  const error = await new Promise(resolve => api.aiStreamChat('hello', {
    onToken: () => {}, onDone: () => assert.fail('unexpected completion'), onError: resolve,
  }))
  assert.equal(error.message, '连接已中断，请重新发送')
})

test('SSE error produces one error callback', async () => {
  const api = loadApi(async () => streamResponse(['event: error\ndata: AI 服务暂不可用\n\n']))
  let count = 0
  await new Promise(resolve => api.aiStreamChat('hello', {
    onToken: () => {}, onDone: () => assert.fail('unexpected completion'),
    onError: error => { count++; assert.equal(error.message, 'AI 服务暂不可用'); resolve() },
  }))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(count, 1)
})
