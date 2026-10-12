<script setup lang="ts">
import { aiRecent, aiStreamChat } from '@/api/ai'
import { UserStore } from '@/stores/modules/user'

const userStore = UserStore()

const visible = ref(false)
const minimized = ref(false)
const loading = ref(false)
const input = ref('')
const messages = ref<Array<{ role: 'user' | 'assistant'; content: string }>>([])
const abortController = ref<AbortController | null>(null)

// 拖拽相关
const pos = reactive({ x: 0, y: 0 })
const dragging = ref(false)
const dragOffset = { x: 0, y: 0 }
const widgetRef = ref<HTMLElement | null>(null)

const savePos = () => localStorage.setItem('aiWidgetPos', JSON.stringify(pos))
const loadPos = () => {
  const s = localStorage.getItem('aiWidgetPos')
  if (s) {
    try {
      const v = JSON.parse(s)
      if (typeof v.x === 'number' && typeof v.y === 'number') {
        pos.x = v.x
        pos.y = v.y
      }
    } catch {
      // 本地布局或历史读取失败时保留当前界面，仍可继续聊天。
    }
  } else {
    // 默认右下角
    const margin = 16
    const width = 320
    const height = Math.min(Math.round(window.innerHeight * 0.6), 520)
    pos.x = Math.max(0, window.innerWidth - width - margin)
    pos.y = Math.max(0, window.innerHeight - height - 24 * 4) // 约等于 bottom-24
  }
}

const clampToViewport = () => {
  const width = widgetRef.value?.offsetWidth ?? 320
  const height =
    widgetRef.value?.offsetHeight ?? Math.round(window.innerHeight * 0.6)
  pos.x = Math.min(Math.max(0, pos.x), window.innerWidth - width)
  pos.y = Math.min(Math.max(0, pos.y), window.innerHeight - height)
}

const onMouseMove = (e: MouseEvent) => {
  if (!dragging.value) return
  pos.x = e.clientX - dragOffset.x
  pos.y = e.clientY - dragOffset.y
  clampToViewport()
}
const onMouseUp = () => {
  if (!dragging.value) return
  dragging.value = false
  savePos()
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
}
const startDrag = (e: MouseEvent | TouchEvent) => {
  if (e instanceof TouchEvent) {
    const t = e.touches[0]
    dragOffset.x = t.clientX - pos.x
    dragOffset.y = t.clientY - pos.y
    window.addEventListener('touchmove', onTouchMove, { passive: false })
    window.addEventListener('touchend', onTouchEnd)
  } else {
    dragOffset.x = e.clientX - pos.x
    dragOffset.y = e.clientY - pos.y
    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', onMouseUp)
  }
  dragging.value = true
}
const onTouchMove = (e: TouchEvent) => {
  if (!dragging.value) return
  const t = e.touches[0]
  pos.x = t.clientX - dragOffset.x
  pos.y = t.clientY - dragOffset.y
  clampToViewport()
}
const onTouchEnd = () => {
  if (!dragging.value) return
  dragging.value = false
  savePos()
  window.removeEventListener('touchmove', onTouchMove)
  window.removeEventListener('touchend', onTouchEnd)
}

onMounted(() => {
  loadPos()
  window.addEventListener('resize', () => {
    clampToViewport()
    savePos()
  })
})

// 身份变化时清理上一位用户的本地消息和正在执行的请求。
watch(
  () => userStore.userInfo?.token,
  () => {
    abortController.value?.abort()
    abortController.value = null
    messages.value = []
    loading.value = false
  },
  { flush: 'sync' }
)

const open = async () => {
  visible.value = true
  if (messages.value.length === 0) {
    try {
      const identity = userStore.userInfo?.token
      const list = await aiRecent(10)
      if (identity !== userStore.userInfo?.token || messages.value.length > 0)
        return
      if (Array.isArray(list)) {
        messages.value = list
          .filter(
            (m: any) =>
              m.messageType === 'USER' || m.messageType === 'ASSISTANT'
          )
          .map((m: any) => ({
            role:
              m.messageType === 'USER'
                ? ('user' as const)
                : ('assistant' as const),
            content: m.content,
          }))
          .reverse()
      }
    } catch {
      // 本地布局或历史读取失败时保留当前界面，仍可继续聊天。
    }
  }
}

const close = () => {
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
  loading.value = false
  visible.value = false
}

const toggleMin = () => {
  minimized.value = !minimized.value
}

const send = () => {
  const text = input.value.trim()
  if (!text || loading.value) return
  if (text.length > 2000) return

  if (abortController.value) {
    abortController.value.abort()
  }

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true

  const controller = new AbortController()
  abortController.value = controller

  const assistantIdx = messages.value.length
  messages.value.push({ role: 'assistant', content: '' })

  aiStreamChat(
    text,
    {
      onToken: (token: string) => {
        if (controller.signal.aborted) return
        messages.value[assistantIdx].content += token
      },
      onDone: (fullText: string) => {
        if (controller.signal.aborted) return
        if (fullText) messages.value[assistantIdx].content = fullText
        if (!messages.value[assistantIdx].content) {
          messages.value[assistantIdx].content = '（AI 未生成回复）'
        }
        loading.value = false
        abortController.value = null
      },
      onError: (error: Error) => {
        if (controller.signal.aborted) return
        if (messages.value[assistantIdx].content) {
          messages.value[assistantIdx].content +=
            '\n\n[回复中断: ' + error.message + ']'
        } else {
          messages.value[assistantIdx].content =
            '抱歉，AI 回复出错: ' + error.message
        }
        loading.value = false
        abortController.value = null
      },
    },
    controller.signal
  )
}

defineExpose({ open, close })
</script>

<template>
  <!-- 唤起按钮（固定右下角） -->
  <button
    class="fixed right-4 bottom-24 z-40 rounded-full w-12 h-12 bg-primary text-white shadow-lg flex items-center justify-center hover:opacity-90"
    @click="open"
    v-if="!visible"
  >
    <icon-tabler:message-2 />
  </button>

  <!-- 小窗主体 -->
  <div
    v-if="visible"
    ref="widgetRef"
    class="fixed z-40 w-[320px] max-h-[60vh] bg-white border rounded-xl shadow-xl flex flex-col overflow-hidden"
    :style="{ left: pos.x + 'px', top: pos.y + 'px' }"
  >
    <div
      class="h-10 px-3 flex items-center justify-between border-b cursor-move select-none"
      @mousedown="startDrag"
      @touchstart.prevent="startDrag"
    >
      <div class="font-medium">Vibe AI 助手</div>
      <div class="flex items-center gap-2">
        <button
          class="text-sm text-muted-foreground hover:text-foreground"
          @click="toggleMin"
        >
          <icon-tabler:minus v-if="!minimized" />
          <icon-tabler:plus v-else />
        </button>
        <button
          class="text-sm text-muted-foreground hover:text-foreground"
          @click="close"
        >
          <icon-tabler:x />
        </button>
      </div>
    </div>

    <div
      v-show="!minimized"
      class="flex-1 overflow-auto p-3 space-y-3 bg-white"
    >
      <div v-for="(m, i) in messages" :key="i" class="text-sm">
        <div :class="m.role === 'user' ? 'text-right' : 'text-left'">
          <span
            :class="
              m.role === 'user'
                ? 'inline-block bg-primary text-white'
                : 'inline-block bg-muted'
            "
            class="px-3 py-2 rounded-lg max-w-[90%] whitespace-pre-wrap"
            >{{ m.content }}</span
          >
        </div>
      </div>
      <div v-if="loading" class="text-xs text-muted-foreground">
        AI 正在思考...
      </div>
    </div>

    <div v-show="!minimized" class="p-2 border-t flex items-center gap-2">
      <input
        v-model="input"
        maxlength="2000"
        type="text"
        placeholder="问点与音乐相关的问题试试..."
        @keyup.enter="send"
        class="flex-1 px-3 py-2 rounded-md bg-white border outline-none"
      />
      <button
        class="px-3 h-9 rounded-md bg-primary text-white disabled:opacity-60"
        :disabled="loading"
        @click="send"
      >
        发送
      </button>
    </div>
  </div>
</template>

<style scoped></style>
