<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { UserStore } from '@/stores/modules/user'
import AuthTabs from '@/components/Auth/AuthTabs.vue'
import { changePassword, changeEmail, logout, deleteUser, sendEmailCode } from '@/api/system'
import { clearAllConversations } from '@/api/ai'
import { maskEmail, maskPhone } from '@/utils/mask'
import { PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE } from '@/utils/password'

defineOptions({ name: 'UserPrivacySecurity' })

const router = useRouter()
const userStore = UserStore()

const authVisible = ref(false)

/* ---------------- 账号安全：修改密码 ---------------- */

const pwdFormRef = ref<FormInstance>()
const pwdLoading = ref(false)
const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  repeatPassword: '',
})

const pwdRules = reactive<FormRules>({
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      pattern: PASSWORD_PATTERN,
      message: PASSWORD_RULE_MESSAGE,
      trigger: 'blur',
    },
  ],
  repeatPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== pwdForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
})

const handleChangePassword = async () => {
  if (!pwdFormRef.value) return
  await pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    pwdLoading.value = true
    try {
      const res = await changePassword({
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword,
        repeatPassword: pwdForm.repeatPassword,
      })
      if (res.code === 0) {
        // 后端在改密成功后立即作废当前 token，必须清本地登录态并回到登录页
        ElMessage.success('密码修改成功，请重新登录')
        pwdFormRef.value?.resetFields()
        userStore.clearUserInfo()
        pwdLoading.value = false
        router.push('/login')
        return
      } else {
        ElMessage.error(res.message || '密码修改失败')
      }
    } catch (error: any) {
      ElMessage.error(error.message || '密码修改失败')
    } finally {
      pwdLoading.value = false
    }
  })
}

/* ---------------- 账号安全：绑定信息（脱敏展示） ---------------- */

const maskedEmail = maskEmail(userStore.userInfo.email)
const maskedPhone = maskPhone(userStore.userInfo.phone) || '未绑定'

/* ---------------- 账号安全：换绑恢复邮箱 ---------------- */

const emailDialogVisible = ref(false)
const emailFormRef = ref<FormInstance>()
const emailSubmitting = ref(false)
const oldCodeCountdown = ref(0)
const newCodeCountdown = ref(0)
let oldCodeTimer: ReturnType<typeof setInterval> | null = null
let newCodeTimer: ReturnType<typeof setInterval> | null = null

const emailForm = reactive({
  currentPassword: '',
  oldEmail: userStore.userInfo.email || '',
  oldEmailCode: '',
  newEmail: '',
  newEmailCode: '',
})

const codePattern = /^[0-9a-zA-Z]{6}$/
const emailRules = reactive<FormRules>({
  currentPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  oldEmail: [
    { required: true, message: '原邮箱不能为空', trigger: 'blur' },
    { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' },
  ],
  oldEmailCode: [
    { required: true, message: '请输入原邮箱验证码', trigger: 'blur' },
    { pattern: codePattern, message: '验证码为 6 位字母或数字', trigger: 'blur' },
  ],
  newEmail: [
    { required: true, message: '请输入新邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value && value.toLowerCase() === emailForm.oldEmail.toLowerCase()) {
          callback(new Error('新邮箱不能与原邮箱相同'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
  newEmailCode: [
    { required: true, message: '请输入新邮箱验证码', trigger: 'blur' },
    { pattern: codePattern, message: '验证码为 6 位字母或数字', trigger: 'blur' },
  ],
})

function startCountdown(target: typeof oldCodeCountdown, timerRef: 'old' | 'new') {
  target.value = 60
  const timer = setInterval(() => {
    target.value--
    if (target.value <= 0) {
      clearInterval(timer)
      if (timerRef === 'old') oldCodeTimer = null
      else newCodeTimer = null
    }
  }, 1000)
  if (timerRef === 'old') oldCodeTimer = timer
  else newCodeTimer = timer
}

const sendOldEmailCode = async () => {
  if (!emailForm.oldEmail) {
    ElMessage.warning('原邮箱不能为空')
    return
  }
  const response = await sendEmailCode(emailForm.oldEmail, 'EMAIL_CHANGE_OLD')
  if (response.code === 0) {
    ElMessage.success('原邮箱验证码已发送')
    startCountdown(oldCodeCountdown, 'old')
  } else {
    ElMessage.error(response.message || '发送验证码失败')
  }
}

const sendNewEmailCode = async () => {
  if (!emailForm.newEmail) {
    ElMessage.warning('请先输入新邮箱')
    return
  }
  if (emailForm.newEmail.toLowerCase() === emailForm.oldEmail.toLowerCase()) {
    ElMessage.warning('新邮箱不能与原邮箱相同')
    return
  }
  const response = await sendEmailCode(emailForm.newEmail, 'EMAIL_CHANGE_NEW')
  if (response.code === 0) {
    ElMessage.success('新邮箱验证码已发送')
    startCountdown(newCodeCountdown, 'new')
  } else {
    ElMessage.error(response.message || '发送验证码失败')
  }
}

const openEmailDialog = () => {
  emailForm.currentPassword = ''
  emailForm.oldEmail = userStore.userInfo.email || ''
  emailForm.oldEmailCode = ''
  emailForm.newEmail = ''
  emailForm.newEmailCode = ''
  emailDialogVisible.value = true
}

const handleChangeEmail = async () => {
  if (!emailFormRef.value) return
  await emailFormRef.value.validate(async (valid) => {
    if (!valid) return
    emailSubmitting.value = true
    try {
      const response = await changeEmail(emailForm)
      if (response.code === 0) {
        ElMessage.success('邮箱换绑成功，请使用新邮箱重新登录')
        emailDialogVisible.value = false
        userStore.clearUserInfo()
        await router.push('/login')
      } else {
        ElMessage.error(response.message || '邮箱换绑失败')
      }
    } catch (error: any) {
      ElMessage.error(error.message || '邮箱换绑失败')
    } finally {
      emailSubmitting.value = false
    }
  })
}

/* ---------------- 账号安全：登录状态 ---------------- */

const loggingOut = ref(false)

const handleLogout = async () => {
  loggingOut.value = true
  try {
    const response = await logout()
    if (response.code === 0) {
      userStore.clearUserInfo()
      ElMessage.success('退出登录成功')
      router.push('/')
    } else {
      ElMessage.error(response.message || '退出失败')
    }
  } catch (error: any) {
    ElMessage.error(error.message || '退出失败')
    // 即使请求失败也清除本地登录态，避免停留在失效会话
    userStore.clearUserInfo()
    router.push('/')
  } finally {
    loggingOut.value = false
  }
}

/* ---------------- 个人数据管理：清除 AI 对话记录 ---------------- */

const clearingChats = ref(false)

const handleClearAllChats = async () => {
  try {
    await ElMessageBox.confirm(
      '将清除你的全部会话与消息记录，且不可恢复。是否继续？',
      '清除 AI 对话记录',
      { confirmButtonText: '确认清除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  clearingChats.value = true
  try {
    // 只调一次专用接口，由后端原子清除全部会话与消息，不再逐条循环删除。
    const response = await clearAllConversations()
    if (response.code === 0) {
      const conversationCount = response.data?.conversationCount ?? 0
      const messageCount = response.data?.messageCount ?? 0
      if (conversationCount === 0 && messageCount === 0) {
        ElMessage.info('没有可清除的 AI 对话记录')
      } else {
        ElMessage.success(`已清除 ${conversationCount} 个会话、${messageCount} 条消息`)
      }
    } else {
      // 请求未抛异常不代表业务成功，必须判断 code；非 0 时绝不显示成功。
      ElMessage.error(response.message || '清除 AI 对话记录失败')
    }
  } catch (error: any) {
    // 409（有会话正在生成）已在 http.ts 响应拦截器里弹出后端文案，这里直接返回，
    // 避免用户看到两条重复提示；其余错误才由页面兜底。
    if (error?.response?.status === 409) return
    ElMessage.error(error.message || '清除 AI 对话记录失败')
  } finally {
    clearingChats.value = false
  }
}

/* ---------------- 危险操作：账号注销 ---------------- */

const deleting = ref(false)

const handleDeleteAccount = async () => {
  try {
    await ElMessageBox.confirm(
      '注销账号后，你的个人资料、收藏、歌单等数据将被清除且无法恢复，此操作不可逆。是否确认注销？',
      '注销账号',
      {
        confirmButtonText: '确认注销',
        cancelButtonText: '取消',
        type: 'error',
        confirmButtonClass: 'el-button--danger',
      }
    )
  } catch {
    return
  }

  deleting.value = true
  try {
    const response = await deleteUser()
    if (response.code === 0) {
      // 账号已经不存在了：无论外部资源是否待清理，都必须清本地登录态并回到首页。
      userStore.clearUserInfo()
      if (response.data?.cleanupPending) {
        // 账号已注销成功，但部分外部数据清理待后台重试 —— 既不能说失败，也不能说毫无遗留。
        ElMessage.warning('账号已注销，但部分外部数据清理将在后台重试')
      } else {
        ElMessage.success(response.message || '账号已注销')
      }
      router.push('/')
    } else {
      // 注销失败，账号与数据未变，保留本地登录态。
      ElMessage.error(response.message || '注销失败')
    }
  } catch (error: any) {
    ElMessage.error(error.message || '注销失败')
  } finally {
    deleting.value = false
  }
}

onUnmounted(() => {
  if (oldCodeTimer) clearInterval(oldCodeTimer)
  if (newCodeTimer) clearInterval(newCodeTimer)
})

onMounted(() => {
  if (!userStore.isLoggedIn) {
    authVisible.value = true
  }
})
</script>

<template>
  <div class="user-container">
    <!-- A 账号安全 -->
    <section class="privacy-section">
      <h3 class="section-heading">账号安全</h3>

      <el-card shadow="never" class="item-card">
        <template #header><span class="card-title">修改密码</span></template>
        <el-form
          ref="pwdFormRef"
          :model="pwdForm"
          :rules="pwdRules"
          label-width="90px"
          class="pwd-form"
          @submit.prevent
        >
          <el-form-item label="旧密码" prop="oldPassword">
            <el-input
              v-model="pwdForm.oldPassword"
              type="password"
              show-password
              placeholder="请输入旧密码"
              autocomplete="current-password"
            />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="pwdForm.newPassword"
              type="password"
              show-password
              placeholder="8-18 位，同时包含字母和数字"
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item label="确认密码" prop="repeatPassword">
            <el-input
              v-model="pwdForm.repeatPassword"
              type="password"
              show-password
              placeholder="请再次输入新密码"
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="pwdLoading" @click="handleChangePassword">
              修改密码
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" class="item-card">
        <template #header><span class="card-title">绑定信息</span></template>
        <div class="bind-row">
          <span class="bind-label">绑定邮箱</span>
          <span class="bind-value">{{ maskedEmail || '未绑定' }}</span>
          <el-button type="primary" link @click="openEmailDialog">换绑邮箱</el-button>
        </div>
        <div class="bind-row">
          <span class="bind-label">绑定手机号</span>
          <span class="bind-value">{{ maskedPhone }}</span>
          <el-tag size="small" type="info">换绑暂未开放</el-tag>
        </div>
      </el-card>

      <el-card shadow="never" class="item-card">
        <template #header><span class="card-title">登录状态</span></template>
        <div class="bind-row">
          <span class="bind-label">当前登录</span>
          <span class="bind-value">{{ userStore.userInfo.username || '已登录' }}</span>
          <el-button type="warning" plain :loading="loggingOut" @click="handleLogout">
            退出登录
          </el-button>
        </div>
        <p class="hint-text">多设备会话管理暂未开放，退出登录将结束当前设备的登录状态。</p>
      </el-card>
    </section>

    <el-dialog
      v-model="emailDialogVisible"
      title="换绑恢复邮箱"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="换绑成功后，所有设备将立即退出登录。请使用当前密码并分别验证原邮箱与新邮箱。"
        class="email-alert"
      />
      <el-form ref="emailFormRef" :model="emailForm" :rules="emailRules" label-width="96px">
        <el-form-item label="当前密码" prop="currentPassword">
          <el-input v-model="emailForm.currentPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="原邮箱" prop="oldEmail">
          <el-input v-model="emailForm.oldEmail" disabled />
        </el-form-item>
        <el-form-item label="原邮箱验证码" prop="oldEmailCode">
          <el-input v-model="emailForm.oldEmailCode" maxlength="6" autocomplete="one-time-code">
            <template #append>
              <el-button :disabled="!!oldCodeCountdown" @click="sendOldEmailCode">
                {{ oldCodeCountdown ? `${oldCodeCountdown}s` : '发送验证码' }}
              </el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="新邮箱" prop="newEmail">
          <el-input v-model="emailForm.newEmail" autocomplete="email" />
        </el-form-item>
        <el-form-item label="新邮箱验证码" prop="newEmailCode">
          <el-input v-model="emailForm.newEmailCode" maxlength="6" autocomplete="one-time-code">
            <template #append>
              <el-button :disabled="!!newCodeCountdown" @click="sendNewEmailCode">
                {{ newCodeCountdown ? `${newCodeCountdown}s` : '发送验证码' }}
              </el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="emailDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="emailSubmitting" @click="handleChangeEmail">
          确认换绑
        </el-button>
      </template>
    </el-dialog>

    <!-- B 隐私与个性化 -->
    <section class="privacy-section">
      <h3 class="section-heading">隐私与个性化</h3>
      <el-card shadow="never" class="item-card">
        <template #header><span class="card-title">数据使用说明</span></template>
        <ul class="usage-list">
          <li>你收藏的歌曲与歌单用于个性化推荐（推荐歌单、推荐歌曲）。</li>
          <li>你创建的歌单与个人资料（用户名、简介）用于展示与推荐计算。</li>
          <li>你与 AI 助手的对话内容与音乐偏好用于 AI 记忆，帮助 AI 更准确地回答和生成歌单。</li>
        </ul>
        <div class="bind-row">
          <span class="bind-label">AI 记忆与偏好</span>
          <el-button type="primary" plain @click="router.push('/user/ai-memory')">
            管理 AI 记忆与偏好
          </el-button>
        </div>
      </el-card>
    </section>

    <!-- C 个人数据管理 -->
    <section class="privacy-section">
      <h3 class="section-heading">个人数据管理</h3>
      <el-card shadow="never" class="item-card">
        <template #header><span class="card-title">清除 AI 对话记录</span></template>
        <p class="hint-text">删除你的全部 AI 会话及其消息记录，删除后不可恢复。AI 音乐画像与偏好的管理请前往「AI 记忆与偏好」。</p>
        <el-button type="danger" plain :loading="clearingChats" @click="handleClearAllChats">
          清除全部 AI 对话记录
        </el-button>
      </el-card>

      <el-card shadow="never" class="item-card">
        <template #header><span class="card-title">数据导出</span></template>
        <p class="hint-text">导出个人数据功能暂未开放。</p>
      </el-card>
    </section>

    <!-- D 危险操作 -->
    <section class="privacy-section">
      <h3 class="section-heading danger-heading">危险操作</h3>
      <el-card shadow="never" class="item-card danger-card">
        <template #header><span class="card-title danger-title">注销账号</span></template>
        <p class="hint-text">
          注销后你的账号将被删除，个人资料、收藏、歌单、AI 记忆与对话记录等数据将被清除且无法恢复。如确认不再使用本服务，请谨慎操作。
        </p>
        <el-button type="danger" :loading="deleting" @click="handleDeleteAccount">
          注销账号
        </el-button>
      </el-card>
    </section>

    <!-- 登录对话框 -->
    <AuthTabs v-model="authVisible" />
  </div>
</template>

<style scoped>
.user-container {
  max-width: 1000px;
  margin: 30px auto;
  padding: 30px 40px 15px;
  background-color: var(--el-bg-color);
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.privacy-section {
  margin-bottom: 28px;
}

.section-heading {
  margin: 0 0 14px 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.danger-heading {
  color: var(--el-color-danger);
}

.item-card {
  border-radius: 10px;
  margin-bottom: 14px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.danger-title {
  color: var(--el-color-danger);
}

.danger-card {
  border-color: var(--el-color-danger-light-7);
}

.pwd-form {
  max-width: 420px;
}

.bind-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}

.bind-row + .bind-row {
  border-top: 1px dashed var(--el-border-color-lighter);
}

.bind-label {
  width: 90px;
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.bind-value {
  flex: 1;
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.usage-list {
  margin: 0 0 14px 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.9;
}

.hint-text {
  margin: 0 0 12px 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.8;
}

.email-alert {
  margin-bottom: 18px;
}
</style>
