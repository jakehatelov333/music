import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
import * as pinia from 'pinia'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'

function fixture() {
  const pending = { get: [], collect: [], cancel: [] }
  const messages = []
  const user = vue.reactive({
    isLoggedIn: true,
    userInfo: { userId: 1, token: 'a' },
  })
  const source = readFileSync(
    new URL('../src/stores/modules/favorite.ts', import.meta.url),
    'utf8'
  )
  const exports = {}
  const request = (kind) => () =>
    new Promise((resolve) => pending[kind].push(resolve))
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS },
    }).outputText,
    {
      exports,
      require(name) {
        if (name === 'pinia') return pinia
        if (name === '@/stores/modules/user') return { UserStore: () => user }
        if (name === '@/api/system')
          return {
            getFavoritePlaylists: request('get'),
            collectPlaylist: request('collect'),
            cancelCollectPlaylist: request('cancel'),
          }
        if (name === 'element-plus')
          return {
            ElMessage: {
              success: (value) => messages.push(value),
              error: (value) => messages.push(value),
            },
          }
        if (name.endsWith('.png')) return { default: 'cover.png' }
        throw new Error(`Unexpected dependency: ${name}`)
      },
    }
  )
  const store = exports.useFavoriteStore(pinia.createPinia())
  return { pending, user, store, messages }
}
const page = (title, id = 7) => ({
  code: 0,
  data: { items: [{ playlistId: id, title, coverUrl: null }] },
})

test('a previous account response cannot replace the current account favorites', async () => {
  const f = fixture()
  const old = f.store.getFavoritePlaylists()
  f.user.userInfo = { userId: 2, token: 'b' }
  const current = f.store.getFavoritePlaylists()
  f.pending.get[1](page('B private list'))
  await current
  f.pending.get[0](page('A private list'))
  await old
  assert.equal(f.store.favoritePlaylists[0].name, 'B private list')
})

test('clear invalidates in-flight reads and writes even while the same account stays signed in', async () => {
  const f = fixture()
  const read = f.store.getFavoritePlaylists()
  const write = f.store.collectPlaylist(7)
  f.store.clearFavoritePlaylists()
  f.pending.get[0](page('obsolete private list'))
  f.pending.collect[0]({ code: 0 })
  await Promise.all([read, write])
  assert.equal(f.store.favoritePlaylists.length, 0)
  assert.equal(f.store.loading, false)
  assert.equal(f.pending.get.length, 1)
  assert.equal(f.messages.length, 0)
})

test('the latest list request owns both list content and loading state', async () => {
  const f = fixture()
  const first = f.store.getFavoritePlaylists()
  const second = f.store.getFavoritePlaylists()
  f.pending.get[0](page('stale'))
  await first
  assert.equal(f.store.loading, true)
  assert.equal(f.store.favoritePlaylists.length, 0)
  f.pending.get[1](page('fresh'))
  await second
  assert.equal(f.store.loading, false)
  assert.equal(f.store.favoritePlaylists[0].name, 'fresh')
})

test('late collect and cancel responses cannot refresh or remove another account favorites', async () => {
  const f = fixture()
  const collect = f.store.collectPlaylist(9)
  const cancel = f.store.cancelCollectPlaylist(7)
  f.user.userInfo = { userId: 2, token: 'b' }
  f.store.favoritePlaylists = [
    { id: 7, name: 'B private list', coverImgUrl: '' },
  ]
  f.pending.collect[0]({ code: 0 })
  f.pending.cancel[0]({ code: 0 })
  assert.deepEqual(await Promise.all([collect, cancel]), [false, false])
  assert.equal(f.store.favoritePlaylists[0].name, 'B private list')
  assert.equal(f.pending.get.length, 0)
  assert.equal(f.messages.length, 0)
})

test('an older refresh cannot restore a playlist after cancellation succeeds', async () => {
  const f = fixture()
  f.store.favoritePlaylists = [{ id: 7, name: 'old favorite', coverImgUrl: '' }]
  const cancel = f.store.cancelCollectPlaylist(7)
  const read = f.store.getFavoritePlaylists()
  f.pending.cancel[0]({ code: 0 })
  await cancel
  f.pending.get[0](page('old favorite'))
  await read
  assert.equal(f.store.favoritePlaylists.length, 0)
  assert.equal(f.store.loading, false)
})

test('a newer mutation on the same playlist invalidates its old success callback', async () => {
  const f = fixture()
  const collect = f.store.collectPlaylist(7)
  const cancel = f.store.cancelCollectPlaylist(7)
  f.pending.cancel[0]({ code: 0 })
  await cancel
  f.pending.collect[0]({ code: 0 })
  assert.equal(await collect, false)
  assert.equal(f.pending.get.length, 0)
  assert.deepEqual(f.messages, ['取消收藏成功'])
})

test('sidebar synchronously clears and reloads favorites when a signed-in account changes', () => {
  const user = vue.reactive({
    isLoggedIn: true,
    userInfo: { userId: 1, token: 'a' },
  })
  const calls = []
  const store = {
    clearFavoritePlaylists: () => calls.push('clear'),
    getFavoritePlaylists: () => calls.push('load'),
  }
  const source = readFileSync(
    new URL('../src/layout/components/aside/index.vue', import.meta.url),
    'utf8'
  )
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, {
    id: 'sidebar-account-test',
  }).content
  const exports = {}
  vm.runInNewContext(
    ts.transpileModule(script, {
      compilerOptions: { module: ts.ModuleKind.CommonJS },
    }).outputText,
    {
      exports,
      require(name) {
        if (name === 'vue') return vue
        if (name === 'vue-router')
          return { useRoute: () => ({ path: '/' }), useRouter: () => ({}) }
        if (name === '@/stores/modules/user') return { UserStore: () => user }
        if (name === '@/stores/modules/favorite')
          return { useFavoriteStore: () => store }
        if (name === './data') return { MenuData: [] }
        if (name === '@iconify/vue') return { Icon: {} }
        if (name === 'element-plus') return { ElMessage: {} }
        if (name.endsWith('.vue')) return { default: {} }
        throw new Error(`Unexpected dependency: ${name}`)
      },
    }
  )
  const scope = vue.effectScope()
  try {
    scope.run(() => exports.default.setup({}, { expose() {}, emit() {} }))
    assert.deepEqual(calls, ['clear', 'load'])
    user.userInfo = { userId: 2, token: 'b' }
    assert.deepEqual(calls, ['clear', 'load', 'clear', 'load'])
    user.isLoggedIn = false
    assert.deepEqual(calls, ['clear', 'load', 'clear', 'load', 'clear'])
  } finally {
    scope.stop()
  }
})
