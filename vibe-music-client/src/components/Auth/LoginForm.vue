<script setup lang="ts">
import { ref, reactive } from 'vue'
import { Message, Lock } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { UserStore } from '@/stores/modules/user'

const emit = defineEmits(['success', 'switch-tab', 'guest'])
const userStore = UserStore()

const loading = ref(false)
const loginFormRef = ref<FormInstance>()

const loginForm = reactive({
  email: '',
  password: '',
})

// 表单验证规则
const loginRules = reactive<FormRules>({
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' },
  ],
  password: [
    // 登录只校验必填：老用户密码可能不满足最新的强度规则，
    // 在这里套用密码规则会把合法老用户挡在门外，强度判断交给服务端。
    { required: true, message: '请输入密码', trigger: 'blur' },
  ],
})

// 登录处理
const handleLogin = async () => {
  if (!loginFormRef.value) return
  await loginFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const result = await userStore.userLogin(loginForm)
        if (result.success) {
          ElMessage.success(result.message)
          emit('success')
        } else {
          ElMessage.error(result.message)
        }
      } catch (error: any) {
        ElMessage.error(error.message || '登录失败')
      } finally {
        loading.value = false
      }
    }
  })
}

function switchToRegister() {
  // 通知父组件切换到注册标签
  emit('switch-tab', 'register')
}

function switchToReset() {
  // 通知父组件切换到重置密码标签
  emit('switch-tab', 'reset')
}

function enterAsGuest() {
  emit('guest')
}
</script>

<template>
  <div class="login-container">
    <p class="form-subtitle">输入您的邮箱以登录您的账户</p>

    <el-form
      ref="loginFormRef"
      :model="loginForm"
      :rules="loginRules"
      label-width="0"
      size="large"
      @keyup.enter="handleLogin"
    >
      <el-form-item prop="email">
        <el-input
          v-model="loginForm.email"
          placeholder="邮箱"
          :prefix-icon="Message"
        />
      </el-form-item>

      <el-form-item prop="password" class="mt-6">
        <el-input
          v-model="loginForm.password"
          type="password"
          placeholder="密码"
          :prefix-icon="Lock"
          show-password
        />
      </el-form-item>

      <div class="forgot-password">
        <a href="#" @click.prevent="switchToReset">忘记密码？</a>
      </div>

      <el-form-item class="mt-6">
        <el-button
          class="submit-btn"
          type="primary"
          :loading="loading"
          @click="handleLogin"
        >
          登录
        </el-button>
      </el-form-item>
    </el-form>

    <p class="signup-text">
      没有账户？
      <a href="#" @click.prevent="switchToRegister">注册</a>
    </p>

    <button class="guest-login" type="button" @click="enterAsGuest">
      <span class="guest-login__icon" aria-hidden="true">◌</span>
      <span>游客体验</span>
      <small>无需账号，先逛逛</small>
    </button>
  </div>
</template>

<style scoped>
.login-container {
  width: 100%;
  max-width: 400px;
  margin: 0 auto;
  padding: 20px;
}

.form-subtitle {
  color: #666;
  margin-bottom: 24px;
  font-size: 14px;
}

:deep(.el-form-item) {
  margin-bottom: 20px;
}

:deep(.el-input__wrapper) {
  border-radius: 8px;
}

.submit-btn {
  width: 100%;
  border-radius: 8px;
  height: 40px;
  font-size: 16px;
}

.signup-text {
  text-align: center;
  margin-top: 16px;
  color: #666;
}

.signup-text a {
  color: #2a68fa;
  font-weight: 600;
  text-decoration: none;
}

.signup-text a:hover {
  text-decoration: underline;
}

.forgot-password {
  text-align: right;
  margin: -10px 0 10px;
}

.forgot-password a {
  color: #666;
  font-size: 14px;
  text-decoration: none;
}

.forgot-password a:hover {
  color: #2a68fa;
  text-decoration: underline;
}

.guest-login {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 100%;
  min-height: 44px;
  margin-top: 18px;
  border: 1px solid rgba(151, 170, 219, 0.34);
  border-radius: 9px;
  color: #e0e7fb;
  background: rgba(255, 255, 255, 0.06);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  transition:
    border-color 0.2s ease,
    background-color 0.2s ease,
    color 0.2s ease;
}

.guest-login:hover,
.guest-login:focus-visible {
  border-color: rgba(125, 157, 255, 0.58);
  color: #f3f6ff;
  background: rgba(92, 119, 211, 0.16);
  outline: none;
}

.guest-login__icon {
  color: #8da6ff;
  font-size: 17px;
}

.guest-login small {
  color: #a3b0cc;
  font-size: 11px;
}
</style>
