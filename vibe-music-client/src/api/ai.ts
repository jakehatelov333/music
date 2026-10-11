import { http } from '@/utils/http'
import { UserStore } from '@/stores/modules/user'

export type AiChatResponse = string

// 简单聊天（非流式）
export const aiSimpleChat = (query: string, chatId?: string) => {
  return http<AiChatResponse>('get', '/test/simple/chat', {
    params: { query, chatId },
    timeout: 60000, // AI 回复可能较慢，单独放宽超时
  })
}

// 获取历史
export const aiHistory = (chatId: string) => {
  return http<any>('get', '/test/chat/history', { params: { chatId }, timeout: 30000 })
}

// 获取最近N条
export const aiRecent = (chatId: string, limit = 10) => {
  return http<any>('get', '/test/chat/recent', { params: { chatId, limit }, timeout: 30000 })
}

// SSE 事件回调接口
export interface SSECallbacks {
  onToken: (token: string) => void
  onDone: (fullText: string) => void
  onError: (error: Error) => void
}

/**
 * 流式聊天（SSE），使用 fetch + ReadableStream 解析 Server-Sent Events
 */
export const aiStreamChat = (
  query: string,
  chatId: string,
  callbacks: SSECallbacks,
  signal?: AbortSignal,
): void => {
  const baseURL = 'http://localhost:8080'
  const userStore = UserStore()
  const token = userStore.userInfo?.token

  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
  }
  if (token) {
    headers.Authorization = token
  }

  const url = `${baseURL}/test/simple/chat/stream?query=${encodeURIComponent(query)}&chatId=${encodeURIComponent(chatId)}`

  fetch(url, {
    method: 'GET',
    headers,
    credentials: 'include',
    signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }

      const reader = response.body!.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let currentEvent = ''
      let currentData = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            currentData = line.slice(5).trim()
          } else if (line === '') {
            if (currentData) {
              switch (currentEvent) {
                case 'token':
                  callbacks.onToken(currentData)
                  break
                case 'done':
                  callbacks.onDone(currentData)
                  break
                case 'error':
                  callbacks.onError(new Error(currentData))
                  break
              }
            }
            currentEvent = ''
            currentData = ''
          }
        }
      }
    })
    .catch((err: Error) => {
      if (err.name === 'AbortError') return
      callbacks.onError(err)
    })
}
