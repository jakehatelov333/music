import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import vm from 'node:vm'
import ts from 'typescript'

const require = createRequire(import.meta.url)
function load(name) {
  const source = readFileSync(new URL(`../src/utils/${name}.ts`, import.meta.url), 'utf8')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText
  const exports = {}
  vm.runInNewContext(js, { exports, require })
  return exports
}
const { restoreConversation, songBlock, visibleSongs, songPageCount } = load('aiConversation')
const { renderAiMarkdown } = load('aiMarkdown')
const songs = Array.from({ length: 12 }, (_, n) => ({ songId: n + 1, songName: `歌曲${n + 1}`, artistName: '歌手' }))
const row = (messageType, content) => ({ messageType, content: typeof content === 'string' ? content : JSON.stringify(content) })
const toolResult = (id, result) => row('TOOL_RESULT', { id, text: JSON.stringify(result) })

test('refresh restores playable songs and preview in chronological order, including legacy search filters', () => {
  const history = [
    row('USER', '找歌手的歌'),
    row('TOOL_REQUEST', [{ id: 'find', name: 'searchSongsByArtist', arguments: JSON.stringify({ artistName: '歌手' }) }]),
    toolResult('find', { ok: true, kind: 'SONG_LIST', data: songs, message: '搜索结果' }),
    row('ASSISTANT', '试试听《歌曲1》'),
    row('USER', '创建歌单'),
    toolResult('draft', { ok: true, kind: 'PLAYLIST_PREVIEW', data: { taskId: 'draft1', songs: songs.slice(0, 5), status: 'PENDING' } }),
    row('ASSISTANT', '请确认'),
  ].reverse()
  const messages = restoreConversation(history)
  assert.equal(messages.length, 4)
  const card = messages[1].blocks.find(b => b.kind === 'songs')
  assert.equal(card.search.artistName, '歌手')
  assert.equal(visibleSongs(card).length, 5)
  assert.equal(messages[1].blocks.at(-1).text, '试试听《歌曲1》')
  const confirm = messages[3].blocks.find(b => b.kind === 'confirm')
  assert.equal(confirm.action.taskId, 'draft1')
  assert.equal(confirm.verified, false, 'old PENDING snapshots must be checked against server state')
})

test('malformed tool records do not hide readable history or create broken cards', () => {
  const restored = restoreConversation([
    row('ASSISTANT', '有效回复'),
    toolResult('bad', { ok: true, kind: 'SONG_LIST', data: [null] }),
    row('TOOL_RESULT', '{broken'),
    row('TOOL_REQUEST', 'null'),
  ])
  assert.equal(restored[0].blocks.filter(b => b.kind === 'songs').length, 0)
  assert.equal(restored[0].blocks.at(-1).text, '有效回复')
})

test('new search metadata survives without the original request; random results paginate locally', () => {
  const restored = restoreConversation([toolResult('find', { ok: true, kind: 'SONG_LIST', data: songs, search: { keyword: '夜' } })])
  assert.equal(restored[0].blocks.find(b => b.kind === 'songs').search.keyword, '夜')
  const card = songBlock(songs, '随机推荐')
  assert.equal(songPageCount(card), 3)
  card.page = 3
  assert.deepEqual(visibleSongs(card).map(s => s.songId), [11, 12])
  card.remote = true
  card.songs = songs.slice(5, 10)
  card.page = 2
  assert.equal(visibleSongs(card).length, 5, 'server pages must not be sliced again')
})

test('formatting emphasizes titles and Markdown without executing HTML or remote images', () => {
  const html = renderAiMarkdown('**薛之谦**\n\n- 《演员》：推荐\n\n歌单名称：精选\n<script>alert(1)</script>\n![image](https://example.com/a.png)')
  assert.match(html, /<strong>薛之谦<\/strong>/)
  assert.match(html, /<strong>《演员》<\/strong>/)
  assert.match(html, /<strong>歌单名称：<\/strong>/)
  assert.match(html, /<ul>/)
  assert.doesNotMatch(html, /<script|<img|<a\s/)
  assert.match(html, /&lt;script&gt;/)
})
