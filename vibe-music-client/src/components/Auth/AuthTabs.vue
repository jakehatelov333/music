<script setup lang="ts">
import { ref, computed } from 'vue'
import LoginForm from './LoginForm.vue'
import RegisterForm from './RegisterForm.vue'
import ResetPasswordForm from './ResetPasswordForm.vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    persistent?: boolean
  }>(),
  {
    persistent: false,
  }
)

const emit = defineEmits(['update:modelValue', 'guest'])

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const activeTab = ref('login')

const tabTitles = {
  login: '登录',
  register: '注册',
  reset: '重置密码',
}

const handleSuccess = () => {
  dialogVisible.value = false
}

const handleClose = () => {
  activeTab.value = 'login'
}

const handleSwitchTab = (tab: string) => {
  activeTab.value = tab
}

const handleGuest = () => {
  dialogVisible.value = false
  emit('guest')
}
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    :title="tabTitles[activeTab]"
    width="500px"
    destroy-on-close
    :teleported="!props.persistent"
    :show-close="!props.persistent"
    :close-on-click-modal="!props.persistent"
    :close-on-press-escape="!props.persistent"
    @close="handleClose"
  >
    <div>
      <el-tabs v-model="activeTab" class="auth-tabs">
        <el-tab-pane name="login" :label="tabTitles.login">
          <LoginForm
            @success="handleSuccess"
            @switch-tab="handleSwitchTab"
            @guest="handleGuest"
          />
        </el-tab-pane>
        <el-tab-pane name="register" :label="tabTitles.register">
          <RegisterForm
            @success="handleSuccess"
            @switch-tab="handleSwitchTab"
          />
        </el-tab-pane>
        <el-tab-pane name="reset" :label="tabTitles.reset">
          <ResetPasswordForm
            @success="handleSuccess"
            @switch-tab="handleSwitchTab"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-dialog>
</template>

<style scoped>
.auth-tabs ::v-deep(.el-tabs__nav) {
  width: 100%;
  display: flex;
  justify-content: center; /* 确保导航居中 */
}

.auth-tabs ::v-deep(.el-tabs__item) {
  flex: 1;
  text-align: center;
}
</style>
