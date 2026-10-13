import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

const require = createRequire(import.meta.url)
function compile(source, dependencies = {}) {
  const exports = {}
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText, {
    ...vue, exports, AbortController,
    require: name => dependencies[name] || require(name),
    useRouter: () => ({ push() {} }), AudioStore: () => ({ addTracks() {} }),
    useAudioPlayer: () => ({ loadTrack() {}, play() {} }),
    onMounted() {}, defineExpose() {},
  })
  return exports
}
const conversation = compile(readFileSync(new URL('../src/utils/aiConversation.ts', import.meta.url), 'utf8'))
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
  return compile(source + '\nexport { open, messages, changeSongPage, refreshAction, historyLoading };', {
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
