import { http } from '@/utils/http'
import { UserStore } from '@/stores/modules/user'

export type AiChatResponse = string

export const aiSimpleChat = (query: string) =>
  http<AiChatResponse>('post', '/ai/chat', {
    data: { query },
    withCredentials: true,
    timeout: 65000,
  })

export const aiHistory = () =>
  http<any>('get', '/ai/chat/history', {
    withCredentials: true,
    timeout: 30000,
  })

export const aiRecent = (limit = 10) =>
  http<any>('get', '/ai/chat/recent', {
    params: { limit },
    withCredentials: true,
    timeout: 30000,
  })

export interface SSECallbacks {
  onToken: (token: string) => void
  onDone: (fullText: string) => void
  onError: (error: Error) => void
}

export const aiStreamChat = (
  query: string,
  callbacks: SSECallbacks,
  signal?: AbortSignal
): void => {
  const token = UserStore().userInfo?.token
  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
  }
  if (token) headers.Authorization = token

  fetch('http://localhost:8080/ai/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({ query }),
    credentials: 'include',
    signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        const body = await response.json().catch(() => null)
        throw new Error(body?.message || `请求失败（${response.status}）`)
      }
      if (!response.body) throw new Error('服务器未返回响应内容')
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let currentEvent = ''
      let dataLines: string[] = []
      let completed = false
      const dispatch = () => {
        const data = dataLines.join('\n')
        if (currentEvent === 'token') callbacks.onToken(data)
        else if (currentEvent === 'done') {
          completed = true
          callbacks.onDone(data)
        } else if (currentEvent === 'error') {
          completed = true
          callbacks.onError(new Error(data || 'AI 回复失败'))
        }
        currentEvent = ''
        dataLines = []
      }
      try {
        while (!completed) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let boundary: number
          while ((boundary = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, boundary).replace(/\r$/, '')
            buffer = buffer.slice(boundary + 1)
            if (line.startsWith('event:')) currentEvent = line.slice(6).trim()
            else if (line.startsWith('data:'))
              dataLines.push(line.slice(5).replace(/^ /, ''))
            else if (line === '') dispatch()
            if (completed) break
          }
        }
        if (!completed && !signal?.aborted)
          throw new Error('连接已中断，请重新发送')
      } finally {
        await reader.cancel().catch(() => undefined)
        reader.releaseLock()
      }
    })
    .catch((error: Error) => {
      if (signal?.aborted || error.name === 'AbortError') return
      callbacks.onError(error)
    })
}
