<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { UserStore } from '@/stores/modules/user'
import AuthTabs from '@/components/Auth/AuthTabs.vue'

const router = useRouter()
const userStore = UserStore()
const authVisible = ref(true)

const enterAsGuest = () => {
  sessionStorage.setItem('vibe-music-guest-mode', 'true')
  router.push('/')
}

watch(
  () => [authVisible.value, userStore.isLoggedIn],
  ([visible, loggedIn]) => {
    if (!visible && loggedIn) router.push('/')
  }
)
</script>

<template>
  <main class="login-page">
    <div
      class="login-page__glow login-page__glow--one"
      aria-hidden="true"
    ></div>
    <div
      class="login-page__glow login-page__glow--two"
      aria-hidden="true"
    ></div>
    <div class="login-page__header">
      <button
        class="login-page__brand"
        type="button"
        aria-label="返回 Vibe Music 产品介绍"
        @click="router.push('/landing')"
      >
        <span class="login-page__brand-mark">♪</span>
        <span>Vibe Music</span>
      </button>
      <button
        class="login-page__back"
        type="button"
        @click="router.push('/landing')"
      >
        返回产品介绍
      </button>
    </div>
    <section class="login-page__panel" aria-labelledby="login-page-title">
      <div class="login-page__intro">
        <p class="login-page__eyebrow">WELCOME BACK</p>
        <h1 id="login-page-title">让音乐，更懂你。</h1>
        <p>登录 Vibe Music，继续你的专属聆听旅程。</p>
      </div>
      <AuthTabs v-model="authVisible" persistent @guest="enterAsGuest" />
    </section>
  </main>
</template>

<style scoped>
.login-page {
  --login-bg: #070b18;
  height: 100vh;
  min-height: 100vh;
  height: 100dvh;
  min-height: 100dvh;
  position: relative;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  -webkit-overflow-scrolling: touch;
  color: #f8fbff;
  background: var(--login-bg);
  font-family: Inter, ui-sans-serif, system-ui, sans-serif;
}

.login-page__glow {
  position: absolute;
  width: 34rem;
  height: 34rem;
  border-radius: 50%;
  filter: blur(10px);
  opacity: 0.42;
  pointer-events: none;
}

.login-page__glow--one {
  background: #4e47ff;
  top: -16rem;
  right: -8rem;
}
.login-page__glow--two {
  background: #0c90c9;
  bottom: -20rem;
  left: -9rem;
  opacity: 0.28;
}

.login-page__header {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 1180px;
  padding: 28px 30px;
  margin: 0 auto;
}

.login-page__brand,
.login-page__back {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
}

.login-page__brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  font-size: 18px;
}
.login-page__brand-mark {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: linear-gradient(135deg, #865dff, #328bff);
  font-size: 20px;
}
.login-page__back {
  color: #9aa9c6;
  font-size: 13px;
  transition: color 0.2s ease;
}
.login-page__back:hover,
.login-page__brand:hover {
  color: #fff;
}

.login-page__panel {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(300px, 1fr) minmax(400px, 500px);
  align-items: center;
  gap: clamp(36px, 7vw, 96px);
  max-width: 1120px;
  min-height: calc(100vh - 100px);
  padding: 40px 30px 110px;
  margin: 0 auto;
}

.login-page__intro {
  max-width: 390px;
}
.login-page__eyebrow {
  color: #93a0ff;
  font-size: 11px;
  letter-spacing: 0.24em;
  font-weight: 700;
}
.login-page__intro h1 {
  margin: 18px 0 14px;
  font-size: clamp(38px, 5vw, 62px);
  line-height: 1.03;
  letter-spacing: -0.04em;
}
.login-page__intro p:last-child {
  color: #9aa9c6;
  line-height: 1.8;
}

.login-page :deep(.el-dialog__wrapper) {
  backdrop-filter: blur(5px);
}
.login-page :deep(.el-overlay) {
  position: static;
  min-width: 0;
  background-color: transparent;
}
.login-page :deep(.el-overlay-dialog) {
  position: static;
  overflow: visible;
}
.login-page :deep(.el-dialog) {
  width: 100% !important;
  margin: 0 auto !important;
  border: 1px solid rgba(166, 183, 255, 0.22);
  border-radius: 22px;
  background: rgba(17, 24, 46, 0.88);
  box-shadow: 0 28px 90px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(20px);
}
.login-page :deep(.el-tabs__item) {
  color: #7f8eae;
}
.login-page :deep(.el-tabs__item.is-active) {
  color: #7ea4ff;
}
.login-page :deep(.el-tabs__active-bar) {
  background-color: #5b88ff;
}
.login-page :deep(.form-subtitle) {
  color: #9aa9c6;
}
.login-page :deep(.forgot-password a),
.login-page :deep(.signup-text),
.login-page :deep(.login-text) {
  color: #9aa9c6;
}
.login-page :deep(.el-input__wrapper) {
  min-height: 42px;
  border-radius: 9px;
  background-color: rgba(255, 255, 255, 0.075) !important;
  box-shadow: 0 0 0 1px rgba(164, 180, 221, 0.34) inset !important;
  transition:
    background-color 0.2s ease,
    box-shadow 0.2s ease;
}
.login-page :deep(.el-input__wrapper:hover) {
  background-color: rgba(255, 255, 255, 0.11) !important;
  box-shadow: 0 0 0 1px rgba(164, 180, 221, 0.56) inset !important;
}
.login-page :deep(.el-input__wrapper.is-focus) {
  background-color: rgba(75, 102, 171, 0.25) !important;
  box-shadow:
    0 0 0 1px #7095ff inset,
    0 0 0 3px rgba(99, 130, 255, 0.14) !important;
}
.login-page :deep(.el-input__inner) {
  color: #f3f6ff !important;
  caret-color: #aebdff;
}
.login-page :deep(.el-input__inner::placeholder) {
  color: #aab5cf !important;
  opacity: 1;
}
.login-page :deep(.el-input__prefix-inner),
.login-page :deep(.el-input__suffix-inner),
.login-page :deep(.el-input__icon) {
  color: #aebbd7 !important;
}
.login-page :deep(input:-webkit-autofill),
.login-page :deep(input:-webkit-autofill:hover),
.login-page :deep(input:-webkit-autofill:focus) {
  -webkit-text-fill-color: #f3f6ff !important;
  caret-color: #aebdff;
  -webkit-box-shadow: 0 0 0 1000px #202b4a inset !important;
  box-shadow: 0 0 0 1000px #202b4a inset !important;
  transition: background-color 9999s ease-in-out 0s;
}
:deep(.el-dialog__header) {
  padding: 26px 28px 10px;
}
:deep(.el-dialog__title) {
  color: #fff;
  font-size: 20px;
}
:deep(.el-dialog__body) {
  padding: 8px 24px 24px;
}

@media (max-width: 700px) {
  .login-page__header {
    padding: 20px;
  }
  .login-page__panel {
    display: block;
    min-height: auto;
    padding: 50px 20px 80px;
  }
  .login-page__intro {
    margin: 0 auto 26px;
    text-align: center;
  }
  .login-page__intro h1 {
    font-size: 44px;
  }
  .login-page :deep(.el-dialog) {
    width: min(100%, 500px) !important;
  }
}

@media (max-width: 760px) {
  .login-page__panel {
    display: block;
    min-height: auto;
    padding: 50px 20px 80px;
  }
  .login-page__intro {
    margin: 0 auto 26px;
    text-align: center;
  }
}
</style>
