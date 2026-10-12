import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'

function loadApi(http = () => {}) {
  const source = readFileSync(
    new URL('../src/api/myPlaylists.ts', import.meta.url),
    'utf8'
  )
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS },
  }).outputText
  const exports = {}
  vm.runInNewContext(js, {
    exports,
    crypto: { randomUUID },
    FormData,
    Blob,
    require(name) {
      if (name === '@/utils/http') return { http }
      throw new Error(`Unexpected dependency: ${name}`)
    },
  })
  return exports
}

const input = { title: '周末', introduction: '', style: '', isPublic: false }

test('uncertain create retries reuse an intent key; changed input and success get new keys', () => {
  const api = loadApi()
  const intent = api.createPlaylistIntent()
  const initial = intent.keyFor(input)
  assert.match(initial, /^[A-Za-z0-9-]{8,64}$/)
  assert.equal(intent.keyFor({ ...input }), initial)
  const changed = intent.keyFor({ ...input, isPublic: true })
  assert.notEqual(changed, initial)
  intent.reset()
  assert.notEqual(intent.keyFor({ ...input, isPublic: true }), changed)
})

test('create sends the key in the header and explicit private metadata with an empty initial song list', async () => {
  let request
  const api = loadApi(async (...args) => {
    request = args
    return { code: 0 }
  })
  await api.createMyPlaylist(input, 'test-create-key')
  assert.equal(request[0], 'post')
  assert.equal(request[1], '/playlist/mine')
  assert.equal(request[2].headers['Idempotency-Key'], 'test-create-key')
  assert.deepEqual(JSON.parse(JSON.stringify(request[2].data)), {
    ...input,
    songIds: [],
  })
})

test('ownership comes from authentication: add and remove requests send resource IDs only', async () => {
  const requests = []
  const api = loadApi(async (...args) => {
    requests.push(args)
    return { code: 0 }
  })
  await api.addPlaylistSongs(42, [2, 3])
  await api.removePlaylistSong(42, 2)
  assert.equal(requests[0][0], 'post')
  assert.equal(requests[0][1], '/playlist/mine/42/songs')
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0][2].data)), {
    songIds: [2, 3],
  })
  assert.equal(requests[1][0], 'delete')
  assert.equal(requests[1][1], '/playlist/mine/42/songs/2')
})

test('cover upload posts multipart data and reset uses DELETE on the same resource', async () => {
  const requests = []
  const api = loadApi(async (...args) => {
    requests.push(args)
    return { code: 0 }
  })
  await api.updateMyPlaylistCover(7, new Blob(['x'], { type: 'image/png' }))
  assert.equal(requests[0][0], 'patch')
  assert.equal(requests[0][1], '/playlist/mine/7/cover')
  assert.ok(requests[0][2].data instanceof FormData)
  assert.ok(requests[0][2].data.get('cover'))
  assert.equal(requests[0][2].headers['Content-Type'], 'multipart/form-data')

  await api.resetMyPlaylistCover(7)
  assert.equal(requests[1][0], 'delete')
  assert.equal(requests[1][1], '/playlist/mine/7/cover')
})

function loadPage(apiOverrides = {}) {
  const source = readFileSync(
    new URL('../src/pages/my-playlists/index.vue', import.meta.url),
    'utf8'
  )
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, {
    id: 'playlist-state-test',
  }).content
  const js = ts.transpileModule(script, {
    compilerOptions: { module: ts.ModuleKind.CommonJS },
  }).outputText
  const exports = {}
  const user = vue.reactive({
    isLoggedIn: true,
    userInfo: { userId: 1, token: 'account-a' },
  })
  const route = vue.reactive({ path: '/my-playlists', params: {} })
  const pending = []
  const api = {
    ...loadApi(),
    getMyPlaylists: () => new Promise((resolve) => pending.push(resolve)),
    ...apiOverrides,
  }
  vm.runInNewContext(js, {
    exports,
    AbortController,
    require(name) {
      if (name === 'vue') return { ...vue, onBeforeUnmount() {} }
      if (name === 'vue-router')
        return { useRoute: () => route, useRouter: () => ({ push() {} }) }
      if (name === 'element-plus')
        return { ElMessage: { success() {}, error() {} }, ElMessageBox: {} }
      if (name === '@/api/myPlaylists') return api
      if (name === '@/stores/modules/user') return { UserStore: () => user }
      if (name === '@/stores/modules/audio')
        return { AudioStore: () => ({ setAudioStore() {} }) }
      if (name === '@/hooks/useAudioPlayer')
        return { useAudioPlayer: () => ({ loadTrack() {}, play() {} }) }
      if (name.endsWith('.vue') || name.endsWith('.png')) return { default: {} }
      throw new Error(`Unexpected dependency: ${name}`)
    },
  })
  const scope = vue.effectScope()
  const state = scope.run(() => exports.default.setup({}, { expose() {} }))
  return { state, user, route, pending, stop: () => scope.stop() }
}

test('late responses from a previous account never restore its playlist list', async () => {
  const page = loadPage()
  try {
    assert.equal(page.pending.length, 1)
    page.user.userInfo = { userId: 2, token: 'account-b' }
    assert.equal(page.pending.length, 2)
    page.pending[0]({
      code: 0,
      data: { items: [{ title: 'private account A playlist' }], total: 1 },
    })
    await new Promise(setImmediate)
    assert.equal(page.state.playlists.value.length, 0)
    page.pending[1]({
      code: 0,
      data: { items: [{ title: 'account B playlist' }], total: 1 },
    })
    await new Promise(setImmediate)
    assert.equal(page.state.playlists.value[0].title, 'account B playlist')
    page.user.isLoggedIn = false
    assert.equal(page.state.playlists.value.length, 0)
  } finally {
    page.stop()
  }
})

test('cached playlist page does not load an unrelated route or expose stale results', async () => {
  const page = loadPage()
  try {
    page.route.path = '/album/42'
    page.route.params = { id: '42' }
    assert.equal(page.pending.length, 1)
    page.pending[0]({
      code: 0,
      data: { items: [{ title: 'old page data' }], total: 1 },
    })
    await new Promise(setImmediate)
    assert.equal(page.state.playlists.value.length, 0)
  } finally {
    page.stop()
  }
})

test('create conflicts and expired idempotency results show the backend Chinese reason', async () => {
  for (const [status, reason] of [
    [409, '同一请求标识对应的内容不一致'],
    [410, '此请求创建的歌单已删除'],
  ]) {
    const page = loadPage({
      createMyPlaylist: async () => {
        throw { response: { status, data: { message: reason } } }
      },
    })
    try {
      await page.state.save(input)
      assert.equal(page.state.formError.value, reason)
    } finally {
      page.stop()
    }
  }
})
