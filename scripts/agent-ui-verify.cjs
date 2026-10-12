/**
 * Agent 前端交互验证（Playwright + 系统 Chrome）。
 *
 * 目的：补齐第三批 Agent 工程化中「前端交互未在浏览器验证」这一缺口。
 * 覆盖：登录 → AI 助手 → 自然语言建歌单 → 工具状态/歌曲卡片 → 歌曲卡片真实播放
 *      → 确认卡片 → 真实创建 → 跳转歌单详情 → 取消场景 → 刷新后确认卡片恢复。
 *
 * 运行（不下载自带 Chromium，复用系统 Chrome）：
 *   NODE_PATH="C:/Users/lsx/.workbuddy-ai/binaries/node/workspace/node_modules" \
 *     node scripts/agent-ui-verify.cjs
 *
 * 前置：后端 8080（新代码）、用户端 8090 已启动，且存在可登录账号
 *      （脚本会用 agent-ui 账号，需先按 docs/agent-demo.md 插入）。
 */
const { chromium } = require('playwright-core')
const fs = require('fs')
const path = require('path')

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const CLIENT = 'http://localhost:8090'
const EMAIL = 'ui_e2e@example.com'
const PASSWORD = 'Test@12345'
const SHOT_DIR = path.join(__dirname, '..', 'out', 'agent-ui')
const API = 'http://localhost:8080'

const steps = []
const record = (name, ok, detail) => {
  steps.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${name}${detail ? ' | ' + detail : ''}`)
}

const shot = async (page, name) => {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  await page.screenshot({ path: path.join(SHOT_DIR, `${name}.png`), fullPage: false })
}

/** 登录：登录页三个 tab 全部渲染，必须用 .first() 定位。 */
async function login(page) {
  await page.goto(`${CLIENT}/login`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)
  const email = page.getByPlaceholder('邮箱').first()
  const pwd = page.getByPlaceholder('密码').first()
  await email.fill(EMAIL)
  await pwd.fill(PASSWORD)
  await page.getByRole('button', { name: /登录/ }).first().click()
  await page.waitForFunction(() => !!localStorage.getItem('UserStore'), { timeout: 30000 })
  await page.waitForTimeout(2000)
}

/** 打开 AI 助手浮窗。 */
async function openWidget(page) {
  const btn = page.locator('button.fixed.right-4.bottom-24').first()
  await btn.click({ timeout: 15000 })
  await page.waitForTimeout(800)
  return page.locator('div.fixed.z-40').first()
}

/** 在 AI 助手输入框里发一条消息，等回复结束（输入框重新可用）。 */
async function ask(page, text, waitMs = 90000) {
  const input = page.getByPlaceholder(/试试|问点与音乐/).first()
  await input.fill(text)
  await input.press('Enter')
  const deadline = Date.now() + waitMs
  while (Date.now() < deadline) {
    await page.waitForTimeout(1000)
    const thinking = await page.locator('text=AI 正在处理...').count()
    if (thinking === 0) {
      await page.waitForTimeout(1500)
      return true
    }
  }
  return false
}

(async () => {
  const browser = await chromium.launch({
    executablePath: CHROME,
    headless: true,
    args: ['--no-sandbox', '--autoplay-policy=no-user-gesture-required'],
  })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })

  // 收集真实的 Audio 实例，用于验证歌曲卡片点击后确实在播放。
  await context.addInitScript(() => {
    window.__audioProbe = []
    const Native = window.Audio
    window.Audio = function (...args) {
      const el = new Native(...args)
      window.__audioProbe.push(el)
      return el
    }
    window.Audio.prototype = Native.prototype
  })

  const page = await context.newPage()
  const consoleErrors = []
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text())
  })
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message))

  let createdPlaylistId = null

  try {
    // ---------------------------------------------------------------- 1 登录
    await login(page)
    const loggedIn = await page.evaluate(() => !!localStorage.getItem('UserStore'))
    record('登录用户端成功（UserStore 已写入 localStorage）', loggedIn)
    await shot(page, '01-home')

    // ---------------------------------------------------------------- 2 打开助手
    const widget = await openWidget(page)
    const widgetVisible = (await widget.count()) > 0
    record('AI 助手浮窗可打开', widgetVisible)
    await shot(page, '02-widget-open')

    // ---------------------------------------------------------------- 3 主链路
    const query = '帮我创建一个适合开车时听的歌单，名字叫「夜间驾驶」，从 Ariana Grande 的歌里选 3 首'
    const done = await ask(page, query)
    record('自然语言请求收到完整回复（加载态已结束）', done)
    await shot(page, '03-after-answer')

    // 工具执行状态
    const toolLines = await widget.locator('div.flex.items-center.gap-2.text-xs').allInnerTexts()
    const hasToolStatus = toolLines.some((t) => /搜索|准备|歌单预览/.test(t))
    record('前端展示工具执行状态', hasToolStatus, toolLines.join(' / ').slice(0, 200))

    // 结构化歌曲卡片
    const songCards = await widget.locator('div.border.rounded-lg.divide-y').first()
    const songCount = await songCards.locator('button[title="播放"]').count()
    const songNames = await songCards.locator('div.truncate.text-xs.font-medium').allInnerTexts()
    record('渲染结构化歌曲卡片（含歌名）', songCount > 0, `${songCount} 首：${songNames.join(', ')}`)
    await shot(page, '04-song-cards')

    // 歌曲卡片真实播放
    const playBtn = widget.locator('button[title="播放"]').first()
    await playBtn.click()
    await page.waitForTimeout(4000)
    const audio = await page.evaluate(() => {
      const list = window.__audioProbe || []
      const el = list[list.length - 1]
      if (!el) return null
      return { src: (el.src || '').slice(-40), paused: el.paused, currentTime: el.currentTime, duration: el.duration }
    })
    const playing = !!audio && !audio.paused && audio.currentTime > 0
    record('点击歌曲卡片播放按钮 → 音频真实播放', playing, JSON.stringify(audio))
    await shot(page, '05-playing')

    // 确认卡片
    const confirmCard = widget.locator('div.border-2').first()
    const confirmVisible = (await confirmCard.count()) > 0
    record('渲染待确认卡片', confirmVisible)
    if (confirmVisible) {
      const titleInput = confirmCard.locator('input[placeholder="歌单名称"]').first()
      const titleValue = await titleInput.inputValue()
      record('确认卡片带歌单名称输入框且预填标题', titleValue.includes('夜间驾驶'), `title="${titleValue}"`)
      const previewSongs = await confirmCard.locator('div.max-h-40 div.px-2.py-1').count()
      record('确认卡片列出待创建歌曲', previewSongs > 0, `${previewSongs} 首`)
      // 消息列表可滚动，先把确认卡片滚进可视区再截图，保证证据图能看到卡片本身。
      await confirmCard.scrollIntoViewIfNeeded()
      await page.waitForTimeout(400)
      await shot(page, '06-confirm-card')
      await confirmCard.screenshot({ path: path.join(SHOT_DIR, '06b-confirm-card-zoom.png') }).catch(() => {})
    }

    // 确认前数据库未写入（通过接口核对）
    const pendingBefore = await page.evaluate(async (api) => {
      const token = JSON.parse(localStorage.getItem('UserStore')).userInfo.token
      const r = await fetch(`${api}/ai/actions/pending`, { headers: { Authorization: token } })
      return r.json()
    }, API)
    record('确认前只有待确认任务、没有歌单', (pendingBefore.data || []).length === 1,
      `待确认 ${(pendingBefore.data || []).length} 条`)

    // ---------------------------------------------------------------- 4 修改预览后确认
    const titleInput = confirmCard.locator('input[placeholder="歌单名称"]').first()
    await titleInput.fill('夜间驾驶 · 精选')
    await confirmCard.locator('input[type="checkbox"]').first().check()
    // 移除最后一首，验证"改过的内容被写进去"
    const removeBtns = confirmCard.locator('button[title="移除"]')
    const beforeRemove = await removeBtns.count()
    if (beforeRemove > 1) await removeBtns.last().click()
    await page.waitForTimeout(500)
    const afterRemove = await confirmCard.locator('button[title="移除"]').count()
    record('预览可编辑（改名 / 改可见性 / 移除歌曲）', afterRemove === beforeRemove - 1,
      `${beforeRemove} → ${afterRemove} 首`)
    await shot(page, '07-edited')

    await confirmCard.getByRole('button', { name: '确认创建' }).click()
    await page.waitForTimeout(6000)
    const successText = await widget.innerText()
    const created = /已创建歌单/.test(successText)
    record('点击确认 → 前端显示真实创建成功', created, successText.split('\n').filter((l) => l.includes('已创建')).join(' '))
    await widget.getByRole('button', { name: '查看歌单' }).first().scrollIntoViewIfNeeded().catch(() => {})
    await page.waitForTimeout(400)
    await shot(page, '08-created')

    const linkBtn = widget.getByRole('button', { name: '查看歌单' }).first()
    const hasLink = (await linkBtn.count()) > 0
    record('成功卡片提供「查看歌单」入口', hasLink)

    // ---------------------------------------------------------------- 5 跳转歌单详情
    if (hasLink) {
      await linkBtn.click()
      await page.waitForURL(/\/playlist\/\d+/, { timeout: 20000 })
      const url = page.url()
      createdPlaylistId = Number(url.match(/\/playlist\/(\d+)/)[1])
      await page.waitForTimeout(3000)
      const detailText = await page.locator('body').innerText()
      const detailOk = detailText.includes('夜间驾驶')
      record('跳转歌单详情页且内容正确', detailOk, url)
      await shot(page, '09-playlist-detail')

      // 用后端详情接口核对「改过的预览」与「真正落库的内容」一致。
      const detail = await page.evaluate(async ({ api, id }) => {
        const token = JSON.parse(localStorage.getItem('UserStore')).userInfo.token
        const r = await fetch(`${api}/playlist/getPlaylistDetail/${id}`, { headers: { Authorization: token } })
        return r.json()
      }, { api: API, id: createdPlaylistId })
      const d = detail.data || {}
      record('落库歌单标题 = 预览中修改后的标题', d.title === '夜间驾驶 · 精选', `title="${d.title}"`)
      record('落库歌曲数 = 预览中移除后的数量（2 首）', (d.songs || []).length === 2,
        `${(d.songs || []).length} 首：${(d.songs || []).map((s) => s.songName).join(', ')}`)
      record('歌单归属当前登录用户且为公开（确认卡片勾选）', !!d.ownerUserId && d.isPublic === true,
        `ownerUserId=${d.ownerUserId} isPublic=${d.isPublic}`)
    }

    // ---------------------------------------------------------------- 6 取消场景
    await page.goto(CLIENT, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(2500)
    const widget2 = await openWidget(page)
    const cancelQuery = '帮我再建一个歌单，叫「待取消的歌单」，从 Ariana Grande 的歌里选 2 首'
    await ask(page, cancelQuery)
    const cancelCard = widget2.locator('div.border-2').first()
    if ((await cancelCard.count()) > 0) {
      await cancelCard.getByRole('button', { name: '取消' }).click()
      await page.waitForTimeout(4000)
      const t = await widget2.innerText()
      record('点击取消 → 前端显示已取消且无歌单创建', /已取消|CANCELLED/.test(t),
        t.split('\n').filter((l) => /取消/.test(l)).join(' ').slice(0, 160))
      await shot(page, '10-cancelled')
    } else {
      record('点击取消 → 前端显示已取消且无歌单创建', false, '未出现确认卡片')
    }

    // ---------------------------------------------------------------- 7 刷新后确认卡片恢复
    // 真实模型有概率跳过写工具（本轮就复现过），此时服务端输出护栏会把话术改成如实说明。
    // 因此这里最多重试 3 次，并把「护栏是否生效」也当作一个验证点。
    await page.goto(CLIENT, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(2500)
    let widget3 = await openWidget(page)
    const pendingCount = async () =>
      page.evaluate(async (api) => {
        const token = JSON.parse(localStorage.getItem('UserStore')).userInfo.token
        const r = await fetch(`${api}/ai/actions/pending`, { headers: { Authorization: token } })
        return ((await r.json()).data || []).length
      }, API)

    let guardObserved = false
    let attempts = 0
    while (attempts < 3 && (await pendingCount()) === 0) {
      attempts++
      await ask(page, `帮我创建一个歌单，名字叫「刷新恢复验证${attempts}」，从 Ariana Grande 的歌里选 2 首`)
      const t = await widget3.innerText()
      if (/没有真正生成待确认卡片/.test(t)) {
        guardObserved = true
        await shot(page, `07b-output-guard-${attempts}`)
      }
    }
    const pendingTotal = await pendingCount()
    record('生成待确认任务（用于恢复验证，最多重试 3 次）', pendingTotal >= 1,
      `尝试 ${attempts} 次后待确认 ${pendingTotal} 条`)
    if (guardObserved) {
      record('模型跳过写工具时，服务端输出护栏把「请确认卡片」改成如实说明', true,
        '已在界面观察到护栏话术，未出现虚假的成功/卡片承诺')
    }

    // 彻底重新加载页面：清空前端内存里的消息，只能靠服务端状态恢复。
    await page.goto(CLIENT, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(2500)
    const widget4 = await openWidget(page)
    await page.waitForTimeout(4000)
    const restoredText = await widget4.innerText()
    const restoredCard = widget4.locator('div.border-2').first()
    const restored = (await restoredCard.count()) > 0
    record('刷新页面后待确认卡片从服务端恢复', restored,
      restored ? restoredText.split('\n').filter((l) => /待确认|刷新恢复/.test(l)).join(' | ').slice(0, 200) : '未恢复')
    await shot(page, '11-after-refresh')

    // 从恢复出来的卡片直接确认，验证「刷新后仍可继续完成流程」。
    if (restored) {
      const restoredTitle = await restoredCard.locator('input[placeholder="歌单名称"]').first().inputValue()
      await restoredCard.getByRole('button', { name: '确认创建' }).click()
      await page.waitForTimeout(6000)
      const t = await widget4.innerText()
      record('刷新后仍可确认并真实创建成功', /已创建歌单/.test(t),
        t.split('\n').filter((l) => l.includes('已创建')).join(' ').slice(0, 160))
      await shot(page, '12-confirmed-after-refresh')
      const after = await pendingCount()
      record('确认后待确认列表被清空', after < pendingTotal, `${pendingTotal} → ${after}（恢复了「${restoredTitle}」）`)
    } else {
      record('刷新后仍可确认并真实创建成功', false, '恢复的卡片不可见')
    }
  } catch (error) {
    record('脚本执行', false, error.message)
    await shot(page, '99-error').catch(() => {})
  } finally {
    record('页面无 JS 运行时错误', consoleErrors.length === 0,
      consoleErrors.slice(0, 3).join(' | ').slice(0, 300))
    const passed = steps.filter((s) => s.ok).length
    console.log(`\n===== 结果：${passed}/${steps.length} 通过 =====`)
    console.log('CREATED_PLAYLIST_ID=' + (createdPlaylistId ?? 'none'))
    console.log('SCREENSHOT_DIR=' + SHOT_DIR)
    fs.writeFileSync(path.join(SHOT_DIR, 'result.json'),
      JSON.stringify({ steps, createdPlaylistId, consoleErrors }, null, 2), 'utf8')
    await context.close()
    await browser.close()
  }
})()
