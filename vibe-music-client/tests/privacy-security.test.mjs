import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

// 直接加载 api/system.ts，隔离 HTTP 封装；验证 changePassword 的 URL / 方法 / 请求体。
function loadSystemApi(httpCalls) {
  const source = readFileSync(new URL('../src/api/system.ts', import.meta.url), 'utf8')
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
      // '@/api/interface' 仅类型导入，转译后不会出现
      throw new Error(`Unexpected dependency: ${name}`)
    },
  })
  return exports
}

// 直接加载 utils/mask.ts：纯函数，无依赖。
function loadMask() {
  const source = readFileSync(new URL('../src/utils/mask.ts', import.meta.url), 'utf8')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(js, { exports, require: () => { throw new Error('mask.ts should have no dependencies') } })
  return exports
}

const jsonEq = (actual, expected) =>
  assert.equal(JSON.stringify(actual), JSON.stringify(expected))

test('changePassword calls PATCH /user/updateUserPassword with old/new/repeat fields', async () => {
  const calls = []
  const api = loadSystemApi(calls)
  await api.changePassword({
    oldPassword: 'old12345',
    newPassword: 'new12345',
    repeatPassword: 'new12345',
  })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'patch')
  assert.equal(calls[0].url, '/user/updateUserPassword')
  jsonEq(calls[0].config.data, {
    oldPassword: 'old12345',
    newPassword: 'new12345',
    repeatPassword: 'new12345',
  })
})

test('resetPassword stays on the forgot-password endpoint (must not be confused with changePassword)', async () => {
  const calls = []
  const api = loadSystemApi(calls)
  await api.resetPassword({})
  assert.equal(calls[0].method, 'patch')
  assert.equal(calls[0].url, '/user/resetUserPassword')
})

test('maskEmail keeps first char and domain', () => {
  const { maskEmail } = loadMask()
  assert.equal(maskEmail('abc@domain.com'), 'a***@domain.com')
  assert.equal(maskEmail('a@b.co'), 'a***@b.co')
})

test('maskEmail degrades gracefully on empty or malformed input', () => {
  const { maskEmail } = loadMask()
  assert.equal(maskEmail(''), '')
  assert.equal(maskEmail(null), '')
  assert.equal(maskEmail(undefined), '')
  assert.equal(maskEmail('not-an-email'), 'not-an-email')
})

test('maskPhone keeps first 3 and last 4 digits', () => {
  const { maskPhone } = loadMask()
  assert.equal(maskPhone('13812341234'), '138****1234')
})

test('maskPhone passes through empty or non-11-digit input', () => {
  const { maskPhone } = loadMask()
  assert.equal(maskPhone(''), '')
  assert.equal(maskPhone(null), '')
  assert.equal(maskPhone('12345'), '12345')
})
