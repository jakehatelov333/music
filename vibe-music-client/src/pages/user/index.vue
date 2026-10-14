<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

defineOptions({ name: 'UserCenterShell' })

const route = useRoute()
const router = useRouter()

const tabs = [
  { label: '个人资料', path: '/user' },
  { label: 'AI 记忆与偏好', path: '/user/ai-memory' },
  { label: '隐私与安全', path: '/user/privacy-security' },
]

const activePath = computed(() => route.path)
</script>

<template>
  <div class="user-shell">
    <div class="user-shell-header">
      <h2 class="shell-title">个人中心</h2>
      <nav class="shell-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.path"
          type="button"
          class="shell-tab"
          :class="{ active: activePath === tab.path }"
          @click="router.push(tab.path)"
        >
          {{ tab.label }}
        </button>
      </nav>
    </div>

    <router-view />
  </div>
</template>

<style scoped>
.user-shell-header {
  max-width: 1000px;
  margin: 30px auto 0;
  padding: 20px 40px;
  background-color: var(--el-bg-color);
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.shell-title {
  margin: 0 0 16px 0;
  font-size: 20px;
  color: var(--el-text-color-primary);
  font-weight: normal;
}

.shell-tabs {
  display: flex;
  gap: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.shell-tab {
  appearance: none;
  border: none;
  background: transparent;
  padding: 10px 16px;
  font-size: 14px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  border-radius: 8px 8px 0 0;
  position: relative;
  transition: color 0.2s;
}

.shell-tab:hover {
  color: var(--el-color-primary);
}

.shell-tab.active {
  color: var(--el-color-primary);
  font-weight: 600;
}

.shell-tab.active::after {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: -1px;
  height: 2px;
  border-radius: 2px;
  background-color: var(--el-color-primary);
}

@media (max-width: 768px) {
  .user-shell-header {
    margin: 16px 12px 0;
    padding: 16px 20px;
  }
}
</style>
