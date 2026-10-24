import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

const require = createRequire(import.meta.url)
function compile(source, dependencies = {}, globals = {}) {
  const exports = {}
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText, {
    ...vue, exports, AbortController, ...globals,
    require: name => dependencies[name] || require(name),
    useRouter: () => ({ push() {} }), AudioStore: () => ({ addTracks() {} }),
    useAudioPlayer: () => ({ loadTrack() {}, play() {} }),
    onMounted() {}, defineExpose() {},
  })
  return exports
}
const conversation = compile(readFileSync(new URL('../src/utils/aiConversation.ts', import.meta.url), 'utf8'))

/**
 * 直接加载真实的 `src/api/ai.ts`（只替换 HTTP 与 Pinia），
 * 这样 `start` 事件解析这种「协议形状」逻辑才会被真正执行到。
 */
function loadApi(fetchMock) {
  const source = readFileSync(new URL('../src/api/ai.ts', import.meta.url), 'utf8')
    .replace('import.meta.env.VITE_AI_BASE', 'undefined')
  return compile(source, {
    '@/stores/modules/user': { UserStore: () => ({ userInfo: { token: 'test-token' } }) },
    '@/utils/http': { http: () => {} },
    '@/utils/media': { normalizeMediaUrls: value => value },
  }, { fetch: fetchMock, TextDecoder, Response, ReadableStream, TextEncoder, setTimeout })
}

const songs = Array.from({ length: 5 }, (_, n) => ({ songId: n + 1, songName: `歌${n + 1}` }))
const action = { taskId: 'draft', title: '精选', songs, version: 1, status: 'PENDING' }
function widget(overrides = {}) {
  const api = {
    aiRecent: async () => [], aiStreamChat() { assert.fail('pagination must not call the model') },
    aiSongPage: async (_search, page, size) => ({ page, size, total: 15, songs: songs.map(s => ({ ...s, songId: s.songId + (page - 1) * size })) }),
    ...overrides,
  }
  const actions = {
    getPendingAction: async () => ({ code: 0, data: { ...action, status: 'SUCCEEDED', playlistId: 42 } }),
    listPendingActions: async () => ({ code: 0, data: [action] }),
    ...overrides,
  }
  const source = readFileSync(new URL('../src/components/Common/AiWidget.vue', import.meta.url), 'utf8')
    .split('<script setup lang="ts">')[1].split('</script>')[0]
  return compile(source + '\nexport { open, messages, changeSongPage, refreshAction, historyLoading,'
    + ' send, input, activeChatId, pendingNewConversation };', {
    '@/api/ai': api, '@/api/agent': actions,
    '@/utils/aiConversation': conversation, '@/utils/aiMarkdown': { renderAiMarkdown: text => text },
    '@/stores/modules/user': { UserStore: () => ({ userInfo: { token: 'test' } }) },
  })
}

test('opening after refresh reconciles a saved pending card to its completed server state without duplicates', async () => {
  const app = widget({ aiRecent: async () => [
    { messageType: 'ASSISTANT', content: '请确认' },
    { messageType: 'TOOL_RESULT', content: JSON.stringify({ id: 'draft', text: JSON.stringify({ ok: true, kind: 'PLAYLIST_PREVIEW', data: action }) }) },
  ] })
  await app.open()
  const cards = app.messages.value.flatMap(m => m.blocks).filter(b => b.kind === 'confirm')
  assert.equal(cards.length, 1)
  assert.equal(cards[0].action.status, 'SUCCEEDED')
  assert.equal(cards[0].action.playlistId, 42)
  assert.equal(cards[0].verified, true)
  assert.equal(app.historyLoading.value, false)
})

test('next and previous pages use server search and preserve the page on network failure', async () => {
  let fail = false
  const app = widget({ aiSongPage: async (search, page, size) => {
    assert.equal(search.artistName, '歌手')
    assert.equal(size, 5)
    if (fail) throw new Error('offline')
    return { page, size, total: 12, songs: songs.map(s => ({ ...s, songId: s.songId + (page - 1) * size })) }
  } })
  const card = conversation.songBlock(songs, '结果', { artistName: '歌手' })
  await app.changeSongPage(card, 2)
  assert.equal(card.page, 2)
  assert.equal(conversation.visibleSongs(card)[0].songId, 6)
  fail = true
  await app.changeSongPage(card, 3)
  assert.equal(card.page, 2)
  assert.equal(card.songs[0].songId, 6)
  assert.match(card.error, /加载失败/)
  assert.equal(card.busy, false)
  fail = false
  await app.changeSongPage(card, 1)
  assert.equal(card.page, 1)
  assert.equal(card.error, null)
})

test('failed status lookup never enables a stale confirmation card', async () => {
  const app = widget({ getPendingAction: async () => { throw new Error('offline') } })
  const card = { kind: 'confirm', action, verified: false, busy: false, error: null }
  await app.refreshAction(card)
  assert.equal(card.verified, false)
  assert.equal(card.action.status, 'PENDING')
  assert.match(card.error, /最新状态/)
  assert.equal(card.busy, false)
})

/**
 * 回归：点「新对话」后连续发三条，只能产生一段会话。
 *
 * <p>真实事故：服务端把 `chatId` 放在 `start` 事件的 `data` 里，前端按扁平结构读，
 * 拿到 undefined → `pendingNewConversation` 永远清不掉 → 每条消息都带
 * `newConversation:true` → 三轮分别落在三个 chatId 上，模型「突然失忆」。
 * 数据库里能看到 90 秒内出现 4 段各只有一轮的对话。
 */
test('a new conversation is claimed from the start event and reused by every later message', async () => {
  const requests = []
  // 服务端 AgentEvent 的真实形状：chatId 嵌在 data 里。
  const startEvent = chatId => JSON.stringify({
    type: 'start', taskId: 'task-1', toolCallId: null, tool: null, phase: null,
    ok: null, code: null, message: 'Agent 开始处理', total: null, kind: null,
    data: { model: 'qwen-flash', chatId }, durationMs: null, search: null,
  })
  // 服务端行为：只有带 newConversation 的请求才会新建会话，其余沿用传入的 chatId。
  let created = null
  // 用真实的 src/api/ai.ts + mock fetch：本用例要验证的正是「载荷解析」这一段，
  // 替换掉 aiStreamChat 就等于把它整个跳过，测不出任何东西。
  const api = loadApi(async (_url, options) => {
    const payload = JSON.parse(options.body)
    requests.push(payload)
    if (!created || payload.newConversation) created = 'user_chat_id_148_new'
    const sse = `event: start\ndata: ${startEvent(created)}\n\nevent: done\ndata: 好的\n\n`
    return new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(sse))
        controller.close()
      },
    }), { headers: { 'Content-Type': 'text/event-stream' } })
  })
  const app = widget({ aiStreamChat: api.aiStreamChat })

  // 模拟点击「新对话」
  app.pendingNewConversation.value = true
  for (const query of ['我的历史播放都有那些歌', '推荐下类似的歌', '推荐下历史播放列表类似的歌']) {
    app.input.value = query
    app.send()
    await new Promise(resolve => setTimeout(resolve, 20))
  }

  assert.equal(requests.length, 3)
  assert.deepEqual(requests[0], { query: '我的历史播放都有那些歌', newConversation: true },
    '第一条消息才允许新建会话')
  assert.deepEqual(requests[1], { query: '推荐下类似的歌', chatId: 'user_chat_id_148_new' },
    '第二条必须沿用第一轮认领到的会话')
  assert.deepEqual(requests[2], { query: '推荐下历史播放列表类似的歌', chatId: 'user_chat_id_148_new' },
    '第三条同样沿用')
  assert.equal(app.pendingNewConversation.value, false)
  assert.equal(app.activeChatId.value, 'user_chat_id_148_new')
})
