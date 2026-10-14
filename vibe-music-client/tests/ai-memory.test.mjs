import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

// 直接加载 api/memory.ts，隔离 HTTP 封装；验证 URL / 方法 / 请求体与解析 helper 的纯逻辑。
function loadApi(httpCalls) {
  const source = readFileSync(new URL('../src/api/memory.ts', import.meta.url), 'utf8')
    .replace('import.meta.env.VITE_AI_BASE', 'undefined')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(js, {
    exports,
    require(name) {
      if (name === '@/utils/http') {
        return {
          http: (method, url, config) => {
            httpCalls.push({ method, url, config })
            return Promise.resolve({ code: 0, message: 'ok', data: null })
          },
        }
      }
      // '@/api/system' 仅类型导入，转译后不会出现
      throw new Error(`Unexpected dependency: ${name}`)
    },
  })
  return exports
}

// vm 沙箱里的对象与宿主 realm 的原型不同，deepEqual 会误判 —— 统一用 JSON 比较。
const jsonEq = (actual, expected) =>
  assert.equal(JSON.stringify(actual), JSON.stringify(expected))

test('parseTaste safely parses profile JSON strings', () => {
  const { parseTaste } = loadApi([])
  jsonEq(parseTaste('[{"name":"流行","count":3},{"name":"摇滚","count":1}]'), [
    { name: '流行', count: 3 },
    { name: '摇滚', count: 1 },
  ])
})

test('parseTaste degrades dirty input to an empty list instead of throwing', () => {
  const { parseTaste } = loadApi([])
  jsonEq(parseTaste(null), [])
  jsonEq(parseTaste(''), [])
  jsonEq(parseTaste('not json'), [])
  jsonEq(parseTaste('{"name":"流行"}'), []) // 非数组
  // 脏元素被过滤、缺失计数补 0
  jsonEq(parseTaste('[{"name":"流行"},{"count":2},{"name":"摇滚","count":"x"}]'), [
    { name: '流行', count: 0 },
    { name: '摇滚', count: 0 },
  ])
})

test('profile endpoint is read-only and same-origin', async () => {
  const calls = []
  const api = loadApi(calls)
  await api.getAiProfile()
  assert.equal(calls[0].method, 'get')
  assert.equal(calls[0].url, '/ai/profile')
  assert.equal(calls[0].config.baseURL, '')
  assert.equal(calls[0].config.withCredentials, true)
})

test('memory overview and CRUD hit the contracted URLs with the contracted methods', async () => {
  const calls = []
  const api = loadApi(calls)
  await api.getMemoryOverview()
  await api.createMemoryPreference({ category: 'FAVORITE_ARTIST', targetId: 1 })
  await api.updateMemoryPreference(7, { targetValue: '只听粤语' })
  await api.deleteMemoryPreference(7)
  await api.setPersonalization(false)
  await api.clearAutoMemory()
  await api.getMemoryStyles()

  assert.deepEqual(
    calls.map((c) => `${c.method.toUpperCase()} ${c.url}`),
    [
      'GET /ai/memory/overview',
      'POST /ai/memory/preferences',
      'PUT /ai/memory/preferences/7',
      'DELETE /ai/memory/preferences/7',
      'PUT /ai/memory/personalization',
      'POST /ai/memory/clear-auto',
      'GET /ai/memory/styles',
    ]
  )
  jsonEq(calls[1].config.data, { category: 'FAVORITE_ARTIST', targetId: 1 })
  jsonEq(calls[2].config.data, { targetValue: '只听粤语' })
  jsonEq(calls[4].config.data, { enabled: false })
})

test('path parameters are URL-encoded', async () => {
  const calls = []
  const api = loadApi(calls)
  await api.deleteMemoryPreference(123)
  assert.equal(calls[0].url, '/ai/memory/preferences/123')
})
