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
const { restoreConversation, songBlock, visibleSongs, songPageCount, hasMoreSongs } = load('aiConversation')
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

/**
 * 推荐卡片与检索结果卡片是两种语义，不能共用同一个数字。
 *
 * <p>真实事故：用户问「推荐最近播放歌曲类似风格的歌」，模型按默认 5 首推荐，
 * 卡片却显示「共 57 首 · 第 1/12 页」—— 因为前端收到 songs 事件后立刻用 search 条件
 * 重新拉了第一页，把曲库匹配总数覆盖进了 total。用户看到的数字和模型说的话对不上。
 *
 * <p>现在卡片默认只显示工具返回的那几首，曲库总数单独放在「查看更多」入口上，
 * 且只有用户主动点击才切换成翻页浏览。
 */
test('recommendation card reports what the tool returned, not the library total', () => {
  const card = songBlock(songs.slice(0, 5), '找到 5 首', { style: '华语流行' })
  assert.equal(card.total, 5, '未展开时 total 就是工具返回条数')
  assert.equal(card.libraryTotal, null, '曲库总数要等探测才知道')
  assert.equal(hasMoreSongs(card), false, '不知道总数时不给「查看更多」')

  card.libraryTotal = 57
  assert.equal(hasMoreSongs(card), true, '曲库有 57 首、只给了 5 首 → 应提示还有更多')
  assert.equal(card.total, 5, '探测总数不得改写卡片上的推荐条数')

  // 展开后语义切换：songs 变成某一页，total 变成曲库总数。
  card.remote = true
  card.total = 57
  assert.equal(hasMoreSongs(card), false, '已展开就不再提示「查看更多」')
  assert.equal(songPageCount(card), 12)
})

test('a card without search criteria never offers to browse more', () => {
  // 随机推荐没有筛选条件，服务端翻页无从谈起，只能本地翻。
  const card = songBlock(songs, '随机推荐')
  card.libraryTotal = 999
  assert.equal(hasMoreSongs(card), false, '没有 search 就不该出现「查看更多」')
  assert.equal(songPageCount(card), 3)
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
