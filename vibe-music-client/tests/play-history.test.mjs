import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'

/**
 * 有效播放判定与播放历史 API 的前端测试。
 *
 * <p>用 node:test + TypeScript 转译（与项目里已有的 ai-*.test.mjs / my-playlists.test.mjs
 * 同一套做法），不需要挂载 Vue 组件。
 */

function loadModule(relativePath, requires = {}) {
  const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8')
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const exports = {}
  vm.runInNewContext(js, {
    exports,
    console,
    Date,
    Math,
    Number,
    Promise,
    globalThis: { crypto: undefined },
    require(name) {
      if (requires[name]) return requires[name]
      throw new Error(`Unexpected dependency: ${name}`)
    },
  })
  return exports
}

function loadPlayHistory() {
  return loadModule('../src/utils/playHistory.ts')
}

/** 用固定 playId 的替身，方便断言「同一个 playId 没有被重复上报」。 */
function trackerWithRecorder(playHistory) {
  const calls = []
  const tracker = new playHistory.PlaybackTracker((songId, playId, position) => {
    calls.push({ songId, playId, position })
  })
  return { tracker, calls }
}

/** 模拟播放器连续上报进度（每次 +step 秒）。 */
function advance(tracker, from, to, step, duration, playing = true) {
  for (let position = from; position <= to; position += step) {
    tracker.tick(position, duration, playing)
  }
}

// ------------------------------------------------------------------ 阈值

test('long songs need only a few seconds, so short real listening still counts', () => {
  const playHistory = loadPlayHistory()
  // 阈值取 5 秒：用户听十几秒就切歌也应当被记录（30 秒会让这类用户历史永远是空的）
  assert.equal(playHistory.effectivePlayThreshold(240), 5)
  assert.equal(playHistory.effectivePlayThreshold(60), 5)
})

test('short songs count at half their length, so they are not impossible to record', () => {
  const playHistory = loadPlayHistory()
  assert.equal(playHistory.effectivePlayThreshold(8), 4)
  assert.equal(playHistory.effectivePlayThreshold(6), 3)
  // 拿不到时长时回退到固定阈值，而不是「永远不算」
  assert.equal(playHistory.effectivePlayThreshold(0), 5)
  assert.equal(playHistory.effectivePlayThreshold(NaN), 5)
  assert.equal(playHistory.effectivePlayThreshold(-5), 5)
})

// ------------------------------------------------------------------ 有效 vs 无效播放

test('merely starting playback never records anything', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  // 页面刚加载 / 误点：位置几乎没动
  tracker.tick(0, 200, true)
  tracker.tick(0.25, 200, true)

  assert.equal(calls.length, 0, '刚点开就关掉不应产生播放记录')
})

test('playing past the threshold records exactly once', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  advance(tracker, 0.25, 35, 0.25, 200)

  assert.equal(calls.length, 1)
  assert.equal(calls[0].songId, '10')
  assert.ok(calls[0].position >= 5)
})

/**
 * 真实用户行为回归：在推荐页点一首、听十几秒觉得不对就切下一首。
 * 30 秒阈值时代这类用户的历史永远是空的（实测过），现在必须被记录。
 */
test('listening for about ten seconds then switching still records the song', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10', true)
  advance(tracker, 0.25, 10, 0.25, 205) // 听了约 10 秒
  tracker.start('11', true)              // 切歌

  assert.equal(calls.length, 1, '听 10 秒就切歌也应记下这一首')
  assert.equal(calls[0].songId, '10')
})

test('continuing to play after the threshold does not record again', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  advance(tracker, 0.25, 35, 0.25, 200)
  advance(tracker, 35, 120, 0.25, 200)

  assert.equal(calls.length, 1, '同一首歌一次播放只能记一次')
})

test('seeking straight to the middle without listening does not count', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  // 用户直接拖到 90 秒：位置很高，但真正播放时长只有一次 tick
  tracker.tick(90, 200, true)
  tracker.tick(90.25, 200, true)

  assert.equal(calls.length, 0, '拖动进度条不等于听了这么久')
})

test('seeking backwards does not add listening time', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  // 每次只推进 2 秒（低于 5 秒阈值），靠「来回拖」凑不出有效播放
  tracker.start('10')
  advance(tracker, 0.25, 2, 0.25, 200) // 听了约 2 秒
  tracker.tick(0, 200, true)           // 拖回开头
  advance(tracker, 0.25, 2, 0.25, 200) // 再听 2 秒
  tracker.tick(0, 200, true)
  advance(tracker, 0.25, 2, 0.25, 200) // 再听 2 秒

  assert.equal(calls.length, 0, '来回拖动不能被累加成有效播放')
})

test('a pause in the middle keeps the accumulated listening time', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  advance(tracker, 0.25, 2, 0.25, 200)   // 听 2 秒
  tracker.tick(2, 200, false)            // 暂停（此时还没到 5 秒）
  assert.equal(calls.length, 0)
  advance(tracker, 2.25, 8, 0.25, 200)   // 继续播过阈值

  assert.equal(calls.length, 1, '暂停后继续播应当累计，而不是重新计时')
})

test('paused playback alone never accumulates', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  advance(tracker, 0.25, 60, 0.25, 200, false)

  assert.equal(calls.length, 0, '没在播就不该累计时长')
})

test('a huge time jump (background tab) does not count as listening', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  // 浏览器后台节流后一次跳 120 秒：那段时间用户并没有在听
  tracker.tick(120, 200, true)
  tracker.tick(120.25, 200, true)

  assert.equal(calls.length, 0)
})

test('a short song records when it reaches the end', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('short')
  advance(tracker, 0.25, 9, 0.25, 12) // 12 秒的歌，阈值 6 秒
  assert.equal(calls.length, 1, '短歌播到一半就该算听过')
})

// ------------------------------------------------------------------ 切歌 / 重复事件 / 失败

test('switching songs starts a fresh attempt and never mixes listening time', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10', true)
  advance(tracker, 0.25, 2, 0.25, 200) // 第一首只听了约 2 秒（未到 5 秒阈值）
  assert.equal(calls.length, 0)

  tracker.start('11', true) // 切歌
  advance(tracker, 0.25, 2, 0.25, 200) // 第二首也只听了 2 秒
  assert.equal(calls.length, 0, '上一首的进度不能被算到新歌头上')

  advance(tracker, 2.25, 10, 0.25, 200)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].songId, '11')
})

test('rapidly switching songs never reports the song that was skipped', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  for (const id of ['10', '11', '12', '13']) {
    tracker.start(id, true)
    tracker.tick(0.25, 200, true)
    tracker.tick(0.5, 200, true)
  }

  assert.equal(calls.length, 0, '连续切歌不应该产生任何播放记录')
  assert.equal(tracker.snapshot().songId, '13')
})

test('repeating the same song after it was recorded starts a new attempt', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10', true)
  advance(tracker, 0.25, 35, 0.25, 200)
  assert.equal(calls.length, 1)
  const firstPlayId = calls[0].playId

  // 用户点了「重新播放」：restart=true 开新的尝试，应当再记一次
  tracker.start('10', true)
  advance(tracker, 0.25, 35, 0.25, 200)

  assert.equal(calls.length, 2)
  assert.notEqual(calls[1].playId, firstPlayId, '新的一次播放必须换新的幂等键')
})

test('single-song loop does not count every loop as a new play', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  advance(tracker, 0.25, 35, 0.25, 200)
  assert.equal(calls.length, 1)

  // 单曲循环：ended 后从 0 秒重新开始，但 start 没有被再次调用（restart=false）
  tracker.start('10')
  advance(tracker, 0.25, 35, 0.25, 200)
  tracker.ended(200)

  assert.equal(calls.length, 1, '一次循环不等于一次新的播放')
})

test('a failed playback discards the attempt so it is never recorded', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  advance(tracker, 0.25, 2, 0.25, 200) // 还没到阈值就失败了
  tracker.fail() // 音频加载失败 / 网络中断

  assert.equal(calls.length, 0, '播放失败不能记历史')
  assert.equal(tracker.snapshot().songId, null)
})

test('after a failure the same song starts timing from zero', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  // 用 2 秒（低于 5 秒阈值）构造「还没记过就失败」的状态
  tracker.start('10')
  advance(tracker, 0.25, 2, 0.25, 200)
  tracker.fail()

  tracker.start('10', true)
  advance(tracker, 0.25, 2, 0.25, 200)
  assert.equal(calls.length, 0, '失败前听的那几秒不能被带进新的尝试')

  advance(tracker, 2.25, 8, 0.25, 200)
  assert.equal(calls.length, 1)
})

test('dispose stops any further reporting', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10')
  advance(tracker, 0.25, 2, 0.25, 200)
  tracker.dispose()
  advance(tracker, 2.25, 60, 0.25, 200)

  assert.equal(calls.length, 0, '组件卸载后不应再有上报')
})

test('a rejected report does not make the tracker report the same play twice', async () => {
  const playHistory = loadPlayHistory()
  const calls = []
  const tracker = new playHistory.PlaybackTracker((songId, playId) => {
    calls.push(playId)
    return Promise.reject(new Error('network down'))
  })

  tracker.start('10')
  advance(tracker, 0.25, 35, 0.25, 200)
  advance(tracker, 35, 60, 0.25, 200)
  await new Promise(setImmediate)

  assert.equal(calls.length, 1, '上报失败不应该触发无限重试或重复上报')
})

test('every attempt gets its own idempotency key', () => {
  const playHistory = loadPlayHistory()
  const { tracker, calls } = trackerWithRecorder(playHistory)

  tracker.start('10', true)
  advance(tracker, 0.25, 35, 0.25, 200)
  tracker.start('11', true)
  advance(tracker, 0.25, 35, 0.25, 200)

  assert.equal(calls.length, 2)
  assert.notEqual(calls[0].playId, calls[1].playId)
  assert.match(calls[0].playId, /^[A-Za-z0-9-]{8,64}$/)
})

// ------------------------------------------------------------------ API 封装

function loadApi(requests) {
  return loadModule('../src/api/playHistory.ts', {
    '@/utils/http': {
      http: async (...args) => {
        requests.push(args)
        return { code: 0 }
      },
    },
  })
}

test('paged query never sends a user id: ownership comes from the token', async () => {
  const requests = []
  const api = loadApi(requests)
  await api.getPlayHistoryPage(2, 20, '夜色')

  assert.equal(requests[0][0], 'post')
  assert.equal(requests[0][1], '/play-history/page')
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0][2].data)), {
    pageNum: 2,
    pageSize: 20,
    songName: '夜色',
  })
  assert.ok(!('userId' in requests[0][2].data), '客户端不得传 userId')
})

test('recent plays uses GET with a limit only', async () => {
  const requests = []
  const api = loadApi(requests)
  await api.getRecentPlays(10)

  assert.equal(requests[0][0], 'get')
  assert.equal(requests[0][1], '/play-history/recent')
  assert.equal(requests[0][2].params.limit, 10)
})

test('deleting one record addresses it by history id only', async () => {
  const requests = []
  const api = loadApi(requests)
  await api.deletePlayHistory(42)

  assert.equal(requests[0][0], 'delete')
  assert.equal(requests[0][1], '/play-history/42')
})

test('clearing history hits the clear endpoint under the permission prefix', async () => {
  const requests = []
  const api = loadApi(requests)
  await api.clearPlayHistory()

  assert.equal(requests[0][0], 'delete')
  // 必须是 /play-history/clear：权限前缀是 /play-history/（带斜杠），
  // DELETE /play-history 会被 403 挡住（见后端 PlayHistoryController 注释）
  assert.equal(requests[0][1], '/play-history/clear')
})

test('recording a play sends the song, the idempotency key and the position', async () => {
  const requests = []
  const api = loadApi(requests)
  await api.recordPlay(10, 'play-abc', 35)

  assert.equal(requests[0][0], 'post')
  assert.equal(requests[0][1], '/play-history/record')
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0][2].data)), {
    songId: 10,
    playId: 'play-abc',
    positionSeconds: 35,
  })
})

test('recordPlay is marked silent so switching songs does not flash the progress bar', async () => {
  const requests = []
  const api = loadApi(requests)
  await api.recordPlay(10, 'play-abc', 35)

  assert.equal(requests[0][2].headers['X-Silent-Request'], '1')
})
