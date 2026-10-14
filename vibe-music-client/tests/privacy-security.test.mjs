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

// ---------------------------------------------------------------------------
// 清除全部 AI 会话：改走专用接口 DELETE /ai/conversations
// ---------------------------------------------------------------------------

// 加载 api/ai.ts。它引用了 import.meta.env（Vite 专有），在 vm 里无法直接执行，
// 因此先把 import.meta 替换成可注入的占位对象；类型导入转译后会被丢弃。
function loadAiApi(httpCalls) {
  const raw = readFileSync(new URL('../src/api/ai.ts', import.meta.url), 'utf8')
  const source = raw.replace(/\bimport\.meta\b/g, '__importMeta')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(js, {
    __importMeta: { env: {} },
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
      if (name === '@/stores/modules/user') {
        return { UserStore: () => ({ userInfo: {} }) }
      }
      // '@/api/system' / '@/api/agent' 仅类型导入，转译后不会出现
      throw new Error(`Unexpected dependency: ${name}`)
    },
  })
  return exports
}

test('clearAllConversations 只发一次 DELETE /ai/conversations，且不携带任何身份参数', async () => {
  const calls = []
  const api = loadAiApi(calls)
  await api.clearAllConversations()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'delete')
  assert.equal(calls[0].url, '/ai/conversations')
  assert.equal(calls[0].config.withCredentials, true)
  assert.equal(calls[0].config.timeout, 60000)
  // 身份取自服务端认证上下文，前端不得传 userId / params / data
  assert.equal(calls[0].config.params, undefined)
  assert.equal(calls[0].config.data, undefined)
})

test('api/ai.ts 保留 listConversations / deleteConversation，并导出 ClearConversationsResultVO', () => {
  const raw = readFileSync(new URL('../src/api/ai.ts', import.meta.url), 'utf8')
  assert.match(raw, /export const listConversations/)
  assert.match(raw, /export const deleteConversation/)
  assert.match(raw, /export interface ClearConversationsResultVO/)
  assert.match(raw, /conversationCount/)
  assert.match(raw, /messageCount/)
  assert.match(raw, /summaryCount/)
  assert.match(raw, /cancelledTaskCount/)
})

// .vue 无法直接 vm 执行，用源码静态断言验证页面逻辑。
const privacySource = readFileSync(
  new URL('../src/pages/user/privacy-security/index.vue', import.meta.url),
  'utf8'
)

test('清除全部会话不再通过会话列表循环删除（无 listConversations / deleteConversation / for 循环）', () => {
  assert.match(privacySource, /clearAllConversations\(\)/)
  assert.doesNotMatch(privacySource, /listConversations/)
  assert.doesNotMatch(privacySource, /deleteConversation/)
})

test('清除会话必须判断业务 code，非 0 不得显示成功', () => {
  assert.match(privacySource, /response\.code === 0/)
  // 非 0 分支使用后端 message 作为失败提示
  assert.match(privacySource, /ElMessage\.error\(response\.message/)
})

test('成功提示使用后端返回的真实数字（会话数 / 消息数）', () => {
  assert.match(privacySource, /conversationCount/)
  assert.match(privacySource, /messageCount/)
  assert.match(privacySource, /已清除 \$\{conversationCount\} 个会话、\$\{messageCount\} 条消息/)
  assert.match(privacySource, /没有可清除的 AI 对话记录/)
})

test('409 冲突不重复弹窗（交由 http.ts 响应拦截器提示）', () => {
  assert.match(privacySource, /status === 409/)
})

test('二次确认文案明确“全部会话与消息记录将被清除且不可恢复”', () => {
  assert.match(privacySource, /全部会话与消息记录，且不可恢复/)
})

test('保留隐私页原有职责：改密 / 脱敏 / 退出登录 / AI 记忆入口 / 注销 / 数据导出', () => {
  assert.match(privacySource, /handleChangePassword/)
  assert.match(privacySource, /maskEmail/)
  assert.match(privacySource, /maskPhone/)
  assert.match(privacySource, /handleLogout/)
  assert.match(privacySource, /\/user\/ai-memory/)
  assert.match(privacySource, /handleDeleteAccount/)
  assert.match(privacySource, /数据导出/)
})

// ---------------------------------------------------------------------------
// 统一密码规则 utils/password.ts
// ---------------------------------------------------------------------------

function loadPassword() {
  const source = readFileSync(new URL('../src/utils/password.ts', import.meta.url), 'utf8')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(js, {
    exports,
    require: () => {
      throw new Error('password.ts should have no dependencies')
    },
  })
  return exports
}

test('PASSWORD_PATTERN 与后端约定的正则字符串完全一致', () => {
  const { PASSWORD_PATTERN } = loadPassword()
  assert.equal(PASSWORD_PATTERN.source, '^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,18}$')
})

test('PASSWORD_RULE_MESSAGE 与约定的提示文案一致', () => {
  const { PASSWORD_RULE_MESSAGE } = loadPassword()
  assert.equal(PASSWORD_RULE_MESSAGE, '密码为 8-18 位，必须同时包含字母和数字，不能包含空格')
})

test('密码规则边界：纯数字 / 纯字母 / 纯符号均拒绝', () => {
  const { isPasswordValid } = loadPassword()
  assert.equal(isPasswordValid('12345678'), false)
  assert.equal(isPasswordValid('abcdefgh'), false)
  assert.equal(isPasswordValid('!@#$%^&*'), false)
})

test('密码规则边界：字母+数字、字母+数字+符号均接受', () => {
  const { isPasswordValid } = loadPassword()
  assert.equal(isPasswordValid('abc12345'), true)
  assert.equal(isPasswordValid('abc123!@'), true)
  assert.equal(isPasswordValid('abc123456789012345'), true) // 恰好 18 位
})

test('密码规则边界：长度越界与含空格均拒绝', () => {
  const { isPasswordValid } = loadPassword()
  assert.equal(isPasswordValid('abc1234'), false) // 7 位
  assert.equal(isPasswordValid('abc1234567890123456'), false) // 19 位
  assert.equal(isPasswordValid('abc 1234'), false) // 含空格
  assert.equal(isPasswordValid(''), false)
})

// ---------------------------------------------------------------------------
// 四个入口的密码规则落地
// ---------------------------------------------------------------------------

test('注册 / 忘记密码 / 修改密码三处「设置新密码」统一使用 PASSWORD_PATTERN 与 PASSWORD_RULE_MESSAGE', () => {
  const register = readFileSync(new URL('../src/components/Auth/RegisterForm.vue', import.meta.url), 'utf8')
  const reset = readFileSync(new URL('../src/components/Auth/ResetPasswordForm.vue', import.meta.url), 'utf8')
  for (const src of [register, reset, privacySource]) {
    assert.match(src, /import \{ PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE \} from '@\/utils\/password'/)
    assert.match(src, /pattern: PASSWORD_PATTERN/)
    assert.match(src, /message: PASSWORD_RULE_MESSAGE/)
    // 旧的正则不应残留
    assert.doesNotMatch(src, /\[0-9A-Za-z\\W\]\{8,18\}/)
  }
})

test('登录表单密码只校验必填，不套用新密码规则（老用户不应被卡住）', () => {
  const login = readFileSync(new URL('../src/components/Auth/LoginForm.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(login, /PASSWORD_PATTERN/)
  assert.doesNotMatch(login, /\[0-9A-Za-z\\W\]\{8,18\}/)
  assert.match(login, /message: '请输入密码'/)
})

test('修改密码表单：oldPassword 只校验必填，newPassword 套用新规则', () => {
  assert.match(privacySource, /oldPassword: \[\{ required: true, message: '请输入旧密码'/)
})

// ---------------------------------------------------------------------------
// 注销账号：Result<AccountDeletionResultVO> 与 cleanupPending 分支
// ---------------------------------------------------------------------------

test('deleteUser 走 DELETE /user/deleteAccount', async () => {
  const calls = []
  const api = loadSystemApi(calls)
  await api.deleteUser()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'delete')
  assert.equal(calls[0].url, '/user/deleteAccount')
})

test('api/system.ts 导出 AccountDeletionResultVO，deleteUser 使用其结果泛型', () => {
  const raw = readFileSync(new URL('../src/api/system.ts', import.meta.url), 'utf8')
  assert.match(raw, /export interface AccountDeletionResultVO/)
  assert.match(raw, /accountDeleted/)
  assert.match(raw, /cleanupPending/)
  assert.match(raw, /pendingItems/)
  assert.match(raw, /http<Result<AccountDeletionResultVO>>\('delete', '\/user\/deleteAccount'\)/)
})

// 抽取 handleDeleteAccount 函数体做静态断言（.vue 无法 vm 执行）。
const deleteFnMatch = privacySource.match(/const handleDeleteAccount = async \(\) => \{[\s\S]*?\n\}/)
const deleteFn = deleteFnMatch ? deleteFnMatch[0] : ''
// 函数内有两处 `} else {`：内层属于 cleanupPending 判断，外层才是 code!==0 失败分支，
// 用 lastIndexOf 取外层，避免把内层的 success/warning 误判成失败分支。
const outerElseIdx = deleteFn.lastIndexOf('} else {')
const successStartIdx = deleteFn.indexOf('if (response.code === 0) {')

test('handleDeleteAccount 函数体可被定位', () => {
  assert.ok(deleteFn.length > 0, '未找到 handleDeleteAccount 函数体')
})

test('注销 code===0：两种情况都清登录态并跳首页', () => {
  assert.match(deleteFn, /if \(response\.code === 0\)/)
  // 整个函数体内只应有一次 clearUserInfo，且位于失败分支之前
  const clearCount = (deleteFn.match(/userStore\.clearUserInfo\(\)/g) || []).length
  assert.equal(clearCount, 1)
  const clearIdx = deleteFn.indexOf('userStore.clearUserInfo()')
  assert.ok(clearIdx >= 0 && outerElseIdx >= 0 && clearIdx < outerElseIdx)

  const successBranch = deleteFn.slice(successStartIdx, outerElseIdx)
  assert.match(successBranch, /userStore\.clearUserInfo\(\)/)
  assert.match(successBranch, /router\.push\('\/'\)/)
})

test('注销 cleanupPending===true 走 warning 而非 success，且提示"后台重试"', () => {
  const successBranch = deleteFn.slice(successStartIdx, outerElseIdx)
  assert.match(successBranch, /cleanupPending/)
  assert.match(successBranch, /ElMessage\.warning\(/)
  assert.match(successBranch, /后台重试/)
  assert.match(successBranch, /ElMessage\.success\(/)
})

test('注销 code!==0：不清登录态、不显示成功/警告', () => {
  assert.ok(outerElseIdx >= 0)
  const failBranch = deleteFn.slice(outerElseIdx)
  assert.match(failBranch, /ElMessage\.error\(response\.message \|\| '注销失败'\)/)
  assert.doesNotMatch(failBranch, /userStore\.clearUserInfo\(\)/)
  assert.doesNotMatch(failBranch, /ElMessage\.success/)
  assert.doesNotMatch(failBranch, /ElMessage\.warning/)
})



