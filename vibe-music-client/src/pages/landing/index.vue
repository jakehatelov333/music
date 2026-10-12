<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import landingCss from './landing.css?inline'

/**
 * 媒体资源基地址。
 * 本地默认指向本机 MinIO；部署时通过环境变量 VITE_MEDIA_BASE 覆盖，
 * 无需改动组件代码。曲目数据中的 coverPath / audioPath 均为相对该地址的路径。
 */
const MEDIA_BASE = (
  import.meta.env.VITE_MEDIA_BASE || 'http://localhost:9005/vibe-music-data'
).replace(/\/+$/, '')

interface TrackSeed {
  songId: number
  songName: string
  artistName: string
  album: string
  coverPath: string
  audioPath: string
}

interface Track extends TrackSeed {
  coverUrl: string
  audioUrl: string
}

/** 固定展示的四首曲目（编辑精选）。 */
const tracks: Track[] = (
  [
    {
      songId: 74,
      songName: "Don't Start Now",
      artistName: 'Dua Lipa',
      album: "Don't Start Now",
      coverPath: 'songCovers/682835cb-3a0f-456d-a30d-16283780d6e3-blob',
      audioPath:
        "songs/660fa0c6-cb1f-46b6-beeb-0ceb13409fc9-Dua Lipa - Don't Start Now.mp3",
    },
    {
      songId: 8,
      songName: 'bad idea',
      artistName: 'Ariana Grande',
      album: 'thank u, next',
      coverPath: 'songCovers/4889a18e-11dd-48e1-880b-cfa2966155b7-blob',
      audioPath:
        'songs/237599b5-f2c2-46b4-8147-af9abd6659ce-Ariana Grande - bad idea.mp3',
    },
    {
      songId: 128,
      songName: 'What Do I Know?',
      artistName: 'Ed Sheeran',
      album: '÷ (Deluxe',
      coverPath: 'songCovers/e8bd4886-df06-402a-8104-7e8905e9100e-blob',
      audioPath:
        'songs/b71f8ec7-9892-416a-a32a-6b4603ec4d9a-Ed Sheeran - What Do I Know.mp3',
    },
    {
      songId: 529,
      songName: 'Diamonds',
      artistName: 'Rihanna',
      album: 'Diamonds',
      coverPath: 'songCovers/f21dcd37-df64-45f1-94e2-9cb7de94baa7-blob',
      audioPath:
        'songs/b95c5d9a-6f3d-449d-bdce-2ee25da241b8-Rihanna - Diamonds.mp3',
    },
  ] as TrackSeed[]
).map((track) => ({
  ...track,
  coverUrl: `${MEDIA_BASE}/${track.coverPath}`,
  audioUrl: `${MEDIA_BASE}/${track.audioPath}`,
}))

/** 与曲目一一对应的展示文案（固定内容）。 */
const contexts = [
  {
    tag: '流行 · 活力时刻',
    category: '给日常充电',
    caption: '让明快的节奏，点亮今天',
    context: '让节奏，唤醒好心情',
  },
  {
    tag: '流行 · 随心聆听',
    category: '流行心动',
    caption: '跟着旋律，换一种心情',
    context: '跟着旋律，找到你的节奏',
  },
  {
    tag: '流行 · 轻松日常',
    category: '轻松一点',
    caption: '把日常，调到舒服的频率',
    context: '给日常，留一点轻松',
  },
  {
    tag: '流行 · 夜晚时光',
    category: '夜晚时光',
    caption: '留一首歌，陪今天慢慢收尾',
    context: '给夜晚，留一首喜欢的歌',
  },
]

/** AI 音乐助手的三组场景示例。 */
const scenarios = {
  focus: {
    question: '准备看一会儿书，想听点不打扰的音乐。',
    answer: '可以从轻柔的纯音乐开始。少一点人声，让旋律陪你慢慢进入专注。',
    title: '给专注留一点空间',
    style: '轻音乐 / 钢琴 / 低干扰',
    reason: '还可以告诉我你喜欢的乐器或歌手。',
    icon: 'headphones',
    color: 'linear-gradient(150deg,#616b96,#2c3555)',
  },
  morning: {
    question: '正在上班路上，来点轻快的音乐吧。',
    answer:
      '给路上加一点节奏。可以试试明快的流行或放克，让通勤也有个好心情。',
    title: '让步伐，跟上节拍',
    style: '流行 / 放克 / 明快节奏',
    reason: '告诉我路上还有多久，也可以继续缩小选择。',
    icon: 'sun',
    color: 'linear-gradient(150deg,#b68053,#5b4445)',
  },
  night: {
    question: '忙了一整天，现在只想放松一会儿。',
    answer: '那就让音乐慢下来。温柔的人声、舒缓的旋律，很适合陪你结束今天。',
    title: '给夜晚一段留白',
    style: '轻柔人声 / 舒缓 / 慢节奏',
    reason: '喜欢纯音乐的话，我们也可以换个方向。',
    icon: 'moon',
    color: 'linear-gradient(150deg,#766a9f,#37304d)',
  },
} as const

type ScenarioKey = keyof typeof scenarios

/** 统一收集监听器，便于组件卸载时一次性释放。 */
const disposers: Array<() => void> = []
const listen = (
  target: EventTarget,
  type: string,
  handler: EventListenerOrEventListenerObject,
  options?: AddEventListenerOptions
) => {
  target.addEventListener(type, handler, options)
  disposers.push(() => target.removeEventListener(type, handler, options))
}

let previousTitle = ''

// ---- 页面级样式：进入时立即注入（早于首次渲染，避免无样式闪烁），
//      离开时移除，避免污染其它页面 ----
const styleEl = document.createElement('style')
styleEl.dataset.landingStyle = 'true'
styleEl.textContent = landingCss
document.head.append(styleEl)

onMounted(() => {
  previousTitle = document.title
  document.title = 'Vibe Music · 让音乐贴近此刻'
  document.documentElement.lang = 'zh-CN'

  setupPlayer()
  setupScenarios()
  setupAmbient()
})

onBeforeUnmount(() => {
  disposers.splice(0).forEach((dispose) => dispose())
  styleEl.remove()
  document.title = previousTitle
  document.documentElement.lang = 'en'
})

/* ------------------------------------------------------------------ *
 * 播放器
 * ------------------------------------------------------------------ */
function setupPlayer() {
  const audio = document.getElementById('audio') as HTMLAudioElement | null
  const play = document.getElementById('play') as HTMLButtonElement | null
  const heroListen = document.getElementById('hero-listen') as HTMLButtonElement | null
  const seek = document.getElementById('seek') as HTMLInputElement | null
  const status = document.getElementById('player-status')
  const grid = document.getElementById('music-grid')
  if (!audio || !play || !heroListen || !seek || !status || !grid) return

  let current = 0
  let generation = 0
  let pending = false

  const formatTime = (value: number) =>
    Number.isFinite(value)
      ? `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, '0')}`
      : '—:—'

  const icon = (name: string) => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('class', 'icon')
    svg.setAttribute('aria-hidden', 'true')
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use')
    use.setAttribute('href', `#i-${name}`)
    svg.append(use)
    return svg
  }

  const element = (tag: string, className: string, text?: string) => {
    const node = document.createElement(tag)
    node.className = className
    if (text !== undefined) node.textContent = text
    return node
  }

  function syncPlayback() {
    const playing = !audio!.paused && !audio!.ended
    const name = tracks[current]?.songName || '歌曲'
    play!.replaceChildren(icon(playing ? 'pause' : 'play'))
    play!.setAttribute('aria-label', `${playing ? '暂停' : '播放'} ${name}`)
    heroListen!.replaceChildren(
      icon(playing ? 'pause' : 'play'),
      element('span', '', playing ? '暂停试听' : '先听一首')
    )
    document.getElementById('player')?.classList.toggle('is-playing', playing)
    grid!.querySelectorAll('.song-card').forEach((card, index) => {
      const active = index === current
      card.classList.toggle('active', active)
      const cardButton = card.querySelector('button')
      cardButton?.setAttribute(
        'aria-label',
        `${active && playing ? '暂停' : '播放'} ${tracks[index].songName}，${tracks[index].artistName}`
      )
      card
        .querySelector('.song-play')
        ?.replaceChildren(icon(active && playing ? 'pause' : 'play'))
    })
  }

  function syncTime() {
    const duration = audio!.duration
    const elapsed = audio!.currentTime
    const percentage =
      Number.isFinite(duration) && duration > 0 ? (elapsed / duration) * 100 : 0
    const elapsedEl = document.getElementById('elapsed')
    const durationEl = document.getElementById('duration')
    if (elapsedEl) elapsedEl.textContent = formatTime(elapsed)
    if (durationEl) durationEl.textContent = formatTime(duration)
    seek!.disabled = !Number.isFinite(duration) || duration <= 0
    seek!.value = String(percentage)
    seek!.style.setProperty('--progress', `${percentage}%`)
    seek!.setAttribute(
      'aria-valuetext',
      `${formatTime(elapsed)} / ${formatTime(duration)}`
    )
  }

  async function startPlayback() {
    if (pending) return
    const selectedGeneration = generation
    pending = true
    status!.textContent = '正在加载音频…'
    try {
      await audio!.play()
      if (selectedGeneration === generation)
        status!.textContent = '正在播放 · 享受这一首'
    } catch (error) {
      if (
        selectedGeneration === generation &&
        (error as DOMException)?.name !== 'AbortError'
      ) {
        status!.textContent = '暂时无法播放，请稍后点击重试'
      }
    } finally {
      if (selectedGeneration === generation) pending = false
      syncPlayback()
    }
  }

  function chooseTrack(index: number, shouldPlay = false) {
    generation += 1
    pending = false
    audio!.pause()
    current = (index + tracks.length) % tracks.length
    const track = tracks[current]
    const context = contexts[current]
    audio!.src = track.audioUrl
    const cover = document.getElementById('current-cover') as HTMLImageElement | null
    if (cover) {
      cover.src = track.coverUrl
      cover.alt = `${track.songName} 专辑封面`
    }
    const titleEl = document.getElementById('current-title')
    const artistEl = document.getElementById('current-artist')
    const tagEl = document.getElementById('current-tag')
    const contextEl = document.querySelector('.track-context')
    if (titleEl) titleEl.textContent = track.songName
    if (artistEl) artistEl.textContent = track.artistName
    if (tagEl) tagEl.textContent = context.tag
    if (contextEl) contextEl.textContent = context.context
    status!.textContent = '已就绪 · 点击播放，听听看'
    syncTime()
    syncPlayback()
    if (shouldPlay) void startPlayback()
  }

  function togglePlayback() {
    if (pending) {
      generation += 1
      pending = false
      audio!.pause()
      status!.textContent = '已暂停 · 随时继续'
    } else if (audio!.paused) {
      void startPlayback()
    } else {
      audio!.pause()
    }
  }

  function renderTracks() {
    const fragment = document.createDocumentFragment()
    tracks.forEach((track, index) => {
      const card = element('article', 'song-card')
      const button = element('button', 'song-cover-button') as HTMLButtonElement
      button.type = 'button'
      const cover = element('img', '') as HTMLImageElement
      cover.src = track.coverUrl
      cover.alt = `${track.songName} 专辑封面`
      cover.width = 400
      cover.height = 360
      cover.loading = 'lazy'
      const category = element(
        'span',
        'song-category',
        contexts[index].category
      )
      const control = element('span', 'song-play')
      control.append(icon('play'))
      button.append(cover, category, control)
      button.addEventListener('click', () =>
        index === current ? togglePlayback() : chooseTrack(index, true)
      )
      const meta = element('div', 'song-meta')
      meta.append(
        element('h3', '', track.songName),
        element('p', '', track.artistName)
      )
      card.append(
        button,
        meta,
        element('p', 'song-caption', contexts[index].caption)
      )
      fragment.append(card)
    })
    grid!.replaceChildren(fragment)
    grid!.setAttribute('aria-busy', 'false')
  }

  listen(play, 'click', togglePlayback)
  listen(heroListen, 'click', togglePlayback)
  listen(document.getElementById('next')!, 'click', () =>
    chooseTrack(current + 1, !audio.paused || pending)
  )
  listen(document.getElementById('previous')!, 'click', () =>
    chooseTrack(current - 1, !audio.paused || pending)
  )
  listen(document.getElementById('mute')!, 'click', (event) => {
    audio.muted = !audio.muted
    const target = event.currentTarget as HTMLElement
    target.setAttribute('aria-pressed', String(audio.muted))
    target.setAttribute('aria-label', audio.muted ? '取消静音' : '静音')
  })
  listen(seek, 'input', () => {
    if (Number.isFinite(audio.duration))
      audio.currentTime = (Number(seek.value) / 100) * audio.duration
    syncTime()
  })
  listen(audio, 'loadedmetadata', syncTime)
  listen(audio, 'durationchange', syncTime)
  listen(audio, 'timeupdate', syncTime)
  listen(audio, 'play', syncPlayback)
  listen(audio, 'playing', () => {
    status.textContent = '正在播放 · 享受这一首'
  })
  listen(audio, 'pause', () => {
    syncPlayback()
    status.textContent = '已暂停 · 随时继续'
  })
  listen(audio, 'ended', () => {
    syncPlayback()
    status.textContent = '这首听完了 · 试试下一首'
  })
  listen(audio, 'waiting', () => {
    if (!audio.paused) status.textContent = '正在缓冲…'
  })
  listen(audio, 'error', () => {
    status.textContent = '音频暂时不可用，请重试或切换歌曲'
    syncPlayback()
  })

  // 游客模式：先体验再登录
  document.querySelectorAll<HTMLElement>('[data-explore]').forEach((link) => {
    listen(link, 'click', () => {
      try {
        sessionStorage.setItem('vibe-music-guest-mode', 'true')
      } catch {
        window.location.assign('/login')
      }
    })
  })

  renderTracks()
  chooseTrack(0)
  play.disabled = false
  heroListen.disabled = false
  const nextBtn = document.getElementById('next') as HTMLButtonElement | null
  const prevBtn = document.getElementById('previous') as HTMLButtonElement | null
  if (nextBtn) nextBtn.disabled = false
  if (prevBtn) prevBtn.disabled = false

  // 离开页面时停止播放，避免后台继续出声
  disposers.push(() => {
    audio.pause()
    audio.removeAttribute('src')
  })
}

/* ------------------------------------------------------------------ *
 * AI 场景示例
 * ------------------------------------------------------------------ */
function setupScenarios() {
  document.querySelectorAll<HTMLElement>('[data-scenario]').forEach((button) => {
    listen(button, 'click', () => {
      const scenario = scenarios[button.dataset.scenario as ScenarioKey]
      if (!scenario) return
      document
        .querySelectorAll<HTMLElement>('[data-scenario]')
        .forEach((item) => {
          item.classList.toggle('active', item === button)
          item.setAttribute('aria-pressed', String(item === button))
        })
      for (const field of [
        'question',
        'answer',
        'title',
        'style',
        'reason',
      ] as const) {
        const el = document.getElementById(`scenario-${field}`)
        if (el) el.textContent = scenario[field]
      }
      const art = document.getElementById('scenario-art')
      if (art) {
        art.style.background = scenario.color
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
        svg.setAttribute('class', 'icon')
        svg.setAttribute('aria-hidden', 'true')
        const use = document.createElementNS('http://www.w3.org/2000/svg', 'use')
        use.setAttribute('href', `#i-${scenario.icon}`)
        svg.append(use)
        art.replaceChildren(svg)
      }
    })
  })
}

/* ------------------------------------------------------------------ *
 * 背景声波动效
 * ------------------------------------------------------------------ */
function setupAmbient() {
  const canvas = document.getElementById('ambient-canvas') as HTMLCanvasElement | null
  const toggle = document.getElementById('motion-toggle') as HTMLButtonElement | null
  const label = document.getElementById('motion-label')
  const audio = document.getElementById('audio')
  const context = canvas?.getContext('2d', { alpha: true })
  if (!canvas || !context || !toggle || !label) return

  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
  const coarsePointer = window.matchMedia('(pointer: coarse)')
  const storageKey = 'vibe-music-motion'
  let preference = true
  try {
    preference = sessionStorage.getItem(storageKey) !== 'off'
  } catch {
    // 浏览器存储不可用时动效仍可用。
  }

  let width = 1
  let height = 1
  let scale = 1
  let compact = false
  let enabled = false
  let disposed = false
  let frame = 0
  let lastFrame = 0
  let clock = 0
  let phase = 0
  let energy = 0
  let playing = false
  let backgroundGradient: CanvasGradient
  let lineGradients: CanvasGradient[] = []
  const ripples: Array<{ x: number; y: number; started: number }> = []
  const pointer = {
    x: 0,
    y: 0,
    targetX: 0,
    targetY: 0,
    strength: 0,
    active: false,
  }
  const frameInterval = 1000 / 30
  const blockers =
    'a,button,input,select,textarea,summary,[role="button"],[contenteditable],.music-player,.conversation,.ai-float,.song-card'

  function resize() {
    width = Math.max(1, window.innerWidth)
    height = Math.max(1, window.innerHeight)
    compact = width < 720 || coarsePointer.matches
    // 限制高分屏上的后备缓冲尺寸。
    scale = Math.min(
      window.devicePixelRatio || 1,
      compact ? 1.25 : 1.5,
      Math.sqrt(3000000 / (width * height))
    )
    canvas!.width = Math.max(1, Math.round(width * scale))
    canvas!.height = Math.max(1, Math.round(height * scale))
    context!.setTransform(scale, 0, 0, scale, 0, 0)
    pointer.x = pointer.targetX = width * 0.68
    pointer.y = pointer.targetY = height * 0.38
    pointer.active = false
    pointer.strength = 0
    ripples.length = 0

    backgroundGradient = context!.createRadialGradient(
      width * 0.79,
      height * 0.36,
      0,
      width * 0.79,
      height * 0.36,
      Math.max(width * 0.65, height * 0.7)
    )
    backgroundGradient.addColorStop(0, 'rgba(119, 87, 194, 0.14)')
    backgroundGradient.addColorStop(0.55, 'rgba(69, 119, 170, 0.055)')
    backgroundGradient.addColorStop(1, 'rgba(60, 96, 145, 0)')
    lineGradients = [
      ['183, 151, 250', '114, 189, 228'],
      ['119, 185, 221', '192, 149, 238'],
    ].map(([left, right]) => {
      const gradient = context!.createLinearGradient(0, 0, width, 0)
      gradient.addColorStop(0, `rgba(${left}, 0.68)`)
      gradient.addColorStop(0.2, `rgba(${left}, 0.4)`)
      gradient.addColorStop(0.43, `rgba(${left}, 0.18)`)
      gradient.addColorStop(0.67, `rgba(${right}, 0.25)`)
      gradient.addColorStop(1, `rgba(${right}, 0.68)`)
      return gradient
    })
    render()
  }

  function waveY(x: number, strand: number, band: number) {
    const t = x / width
    const base = height * (band === 0 ? 0.34 : 0.84)
    const amplitude = (compact ? 45 : 80) * (1 + energy * 0.3)
    const drift =
      Math.sin(t * Math.PI * 2.1 + phase * 0.6 + band * 2.3) * amplitude
    const detail =
      Math.sin(t * Math.PI * 4.4 - phase * 0.45 + strand * 0.12) *
      amplitude *
      0.3
    const slope = (t - 0.5) * height * (band === 0 ? -0.31 : 0.19)
    const offset = strand * (compact ? 9 : 12)
    const y = base + drift + detail + slope + offset
    const dx = x - pointer.x
    const dy = y - pointer.y
    const attraction = Math.exp(-(dx * dx + dy * dy) / 90000) * pointer.strength
    return y + (pointer.y - y) * attraction * 0.4
  }

  function render() {
    context!.clearRect(0, 0, width, height)
    context!.globalAlpha = 1
    context!.fillStyle = backgroundGradient
    context!.fillRect(0, 0, width, height)

    const strands = compact ? 4 : 6
    const step = compact ? 20 : 18
    for (let band = 0; band < 2; band += 1) {
      context!.strokeStyle = lineGradients[band]
      for (let strand = 0; strand < strands; strand += 1) {
        const leading = strand === 1 || strand === strands - 1
        const opacity = Math.min(
          1,
          (leading ? 0.95 : 0.56 + (strand / strands) * 0.24) *
            (1 + energy * 0.1)
        )
        context!.beginPath()
        context!.moveTo(0, waveY(0, strand, band))
        for (let x = step; x < width; x += step)
          context!.lineTo(x, waveY(x, strand, band))
        context!.lineTo(width, waveY(width, strand, band))
        if (leading) {
          context!.lineWidth = compact ? 5 : 7
          context!.globalAlpha = opacity * 0.14
          context!.stroke()
        }
        context!.lineWidth = leading ? (compact ? 1.6 : 2) : 1.1
        context!.globalAlpha = opacity
        context!.stroke()
      }
      // 少量移动高光，让线条读起来像声音，而不是粒子场。
      const highlights = compact ? 2 : 4
      context!.fillStyle = band === 0 ? '#ddc7ff' : '#b8e2f5'
      for (let i = 0; i < highlights; i += 1) {
        const strand = i % strands
        const x = ((i / highlights + band * 0.17 + phase * 0.033) % 1) * width
        const edge = Math.abs(x / width - 0.5) * 2
        context!.globalAlpha = 0.28 + edge * 0.4
        context!.beginPath()
        context!.arc(
          x,
          waveY(x, strand, band),
          compact ? 1.6 : 2,
          0,
          Math.PI * 2
        )
        context!.fill()
      }
    }

    if (pointer.strength > 0.015) {
      const radius = compact ? 180 : 290
      const glow = context!.createRadialGradient(
        pointer.x,
        pointer.y,
        0,
        pointer.x,
        pointer.y,
        radius
      )
      glow.addColorStop(0, 'rgba(162, 124, 228, 0.28)')
      glow.addColorStop(0.45, 'rgba(116, 141, 211, 0.11)')
      glow.addColorStop(1, 'rgba(108, 112, 189, 0)')
      context!.globalAlpha = pointer.strength
      context!.fillStyle = glow
      context!.fillRect(
        pointer.x - radius,
        pointer.y - radius,
        radius * 2,
        radius * 2
      )
    }

    for (let i = ripples.length - 1; i >= 0; i -= 1) {
      const ripple = ripples[i]
      const progress = (clock - ripple.started) / 1800
      if (progress >= 1) {
        ripples.splice(i, 1)
        continue
      }
      const radius = 12 + (1 - Math.pow(1 - progress, 2)) * (compact ? 160 : 260)
      const fade = Math.pow(1 - progress, 1.35)
      context!.strokeStyle = '#d5b8ff'
      context!.lineWidth = compact ? 1.5 : 2
      context!.globalAlpha = fade * 0.72
      context!.beginPath()
      context!.arc(ripple.x, ripple.y, radius, 0, Math.PI * 2)
      context!.stroke()
      context!.lineWidth = 1.2
      context!.globalAlpha = fade * 0.32
      context!.beginPath()
      context!.arc(ripple.x, ripple.y, radius * 0.72, 0, Math.PI * 2)
      context!.stroke()
    }
    context!.globalAlpha = 1
  }

  function tick(now: number) {
    frame = 0
    if (!enabled || document.hidden || disposed) return
    if (!lastFrame) lastFrame = now - frameInterval
    const elapsed = now - lastFrame
    if (elapsed >= frameInterval - 0.5) {
      const delta = Math.min(elapsed, 75)
      lastFrame = now
      clock += delta
      energy += ((playing ? 1 : 0) - energy) * 0.04
      phase += delta * (0.00032 + energy * 0.00011)
      pointer.x += (pointer.targetX - pointer.x) * 0.12
      pointer.y += (pointer.targetY - pointer.y) * 0.12
      pointer.strength += ((pointer.active ? 1 : 0) - pointer.strength) * 0.08
      render()
    }
    frame = requestAnimationFrame(tick)
  }

  function stop() {
    if (frame) cancelAnimationFrame(frame)
    frame = 0
    lastFrame = 0
  }

  function resume() {
    if (!frame && enabled && !document.hidden && !disposed)
      frame = requestAnimationFrame(tick)
  }

  function updatePreference() {
    enabled = preference && !reducedMotion.matches
    toggle!.disabled = reducedMotion.matches
    toggle!.setAttribute('aria-pressed', String(enabled))
    toggle!.setAttribute(
      'aria-label',
      reducedMotion.matches
        ? '背景已静止，遵循系统减少动态效果设置'
        : enabled
          ? '暂停背景动效'
          : '开启背景动效'
    )
    label!.textContent = reducedMotion.matches
      ? '系统已减少动态'
      : enabled
        ? '背景动效：开'
        : '背景动效：关'
    toggle!.title = reducedMotion.matches
      ? '已遵循系统的减少动态效果设置'
      : enabled
        ? '暂停流动声波、鼠标光晕与点击涟漪'
        : '开启流动声波、鼠标光晕与点击涟漪'
    if (enabled) resume()
    else {
      stop()
      pointer.active = false
      pointer.strength = 0
      ripples.length = 0
      render()
    }
  }

  listen(
    window,
    'pointermove',
    (event) => {
      const pointerEvent = event as PointerEvent
      if (!enabled || pointerEvent.pointerType === 'touch') return
      pointer.targetX = pointerEvent.clientX
      pointer.targetY = pointerEvent.clientY
      pointer.active = true
    },
    { passive: true }
  )
  listen(document.documentElement, 'pointerleave', () => {
    pointer.active = false
  })
  listen(window, 'blur', () => {
    pointer.active = false
  })
  listen(
    window,
    'click',
    (event) => {
      const mouseEvent = event as MouseEvent
      if (
        !enabled ||
        mouseEvent.detail === 0 ||
        !(mouseEvent.target instanceof Element) ||
        mouseEvent.target.closest(blockers) ||
        window.getSelection()?.toString()
      )
        return
      if (ripples.length >= 5) ripples.shift()
      ripples.push({
        x: mouseEvent.clientX,
        y: mouseEvent.clientY,
        started: clock,
      })
    },
    { passive: true }
  )
  listen(toggle, 'click', () => {
    if (reducedMotion.matches) return
    preference = !preference
    try {
      sessionStorage.setItem(storageKey, preference ? 'on' : 'off')
    } catch {
      /* 仅本次会话生效。 */
    }
    updatePreference()
  })
  listen(window, 'resize', resize, { passive: true })
  listen(coarsePointer, 'change', resize)
  listen(reducedMotion, 'change', updatePreference)
  listen(document, 'visibilitychange', () => {
    if (document.hidden) stop()
    else resume()
  })
  listen(window, 'pagehide', () => {
    disposed = true
    stop()
  })
  listen(window, 'pageshow', () => {
    disposed = false
    resume()
  })
  if (audio) {
    listen(audio, 'playing', () => {
      playing = true
    })
    for (const event of ['pause', 'ended', 'waiting', 'emptied', 'error'])
      listen(audio, event, () => {
        playing = false
      })
  }

  resize()
  toggle.hidden = false
  updatePreference()

  disposers.push(() => {
    disposed = true
    stop()
    ripples.length = 0
  })
}
</script>

<template>
  <div class="landing-page">
    <canvas id="ambient-canvas" aria-hidden="true"></canvas>
    <svg class="svg-defs" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <defs>
        <symbol id="i-play" viewBox="0 0 24 24">
          <path d="m9 5 11 7-11 7Z" fill="currentColor" stroke="none" />
        </symbol>
        <symbol id="i-pause" viewBox="0 0 24 24">
          <path d="M8 5v14M16 5v14" stroke-width="4" />
        </symbol>
        <symbol id="i-next" viewBox="0 0 24 24">
          <path d="m5 6 10 6-10 6Z" fill="currentColor" stroke="none" />
          <path d="M19 6v12" />
        </symbol>
        <symbol id="i-prev" viewBox="0 0 24 24">
          <path d="m19 6-10 6 10 6Z" fill="currentColor" stroke="none" />
          <path d="M5 6v12" />
        </symbol>
        <symbol id="i-arrow" viewBox="0 0 24 24">
          <path d="M4 12h15m-6-6 6 6-6 6" />
        </symbol>
        <symbol id="i-down" viewBox="0 0 24 24">
          <path d="m6 9 6 6 6-6" />
        </symbol>
        <symbol id="i-heart" viewBox="0 0 24 24">
          <path
            d="M20.8 4.8a5.5 5.5 0 0 0-7.8 0L12 5.9l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.4a5.5 5.5 0 0 0 0-7.8Z"
          />
        </symbol>
        <symbol id="i-spark" viewBox="0 0 24 24">
          <path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z" />
          <path
            d="m20 2 .5 1.5L22 4l-1.5.5L20 6l-.5-1.5L18 4l1.5-.5Z"
            fill="currentColor"
            stroke="none"
          />
        </symbol>
        <symbol id="i-headphones" viewBox="0 0 24 24">
          <path d="M4 14v-3a8 8 0 0 1 16 0v3" />
          <rect x="3" y="12" width="5" height="9" rx="2" />
          <rect x="16" y="12" width="5" height="9" rx="2" />
        </symbol>
        <symbol id="i-volume" viewBox="0 0 24 24">
          <path
            d="m11 4-6 5H2v6h3l6 5Zm5 4a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"
          />
        </symbol>
        <symbol id="i-list" viewBox="0 0 24 24">
          <path d="M4 6h16M4 12h10M4 18h10m4-5 4 3-4 3Z" />
        </symbol>
        <symbol id="i-disc" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="9" />
          <circle cx="12" cy="12" r="2" />
          <path d="M12 6a6 6 0 0 1 6 6" />
        </symbol>
        <symbol id="i-moon" viewBox="0 0 24 24">
          <path d="M21 13A9 9 0 0 1 11 3a9 9 0 1 0 10 10Z" />
        </symbol>
        <symbol id="i-sun" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="4" />
          <path
            d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"
          />
        </symbol>
        <symbol id="i-book" viewBox="0 0 24 24">
          <path
            d="M12 5v15M12 5C8 2 4 3 2 4v15c3-1 7-1 10 1 3-2 7-2 10-1V4c-2-1-6-2-10 1Z"
          />
        </symbol>
        <symbol id="i-logo" viewBox="0 0 32 32">
          <path
            d="M5 13v6m5-12v18m6-21v24m6-19v14m5-10v6"
            stroke-width="3.2"
            stroke-linecap="round"
          />
        </symbol>
      </defs>
    </svg>
    <a class="skip-link" href="#main">跳到主要内容</a>
    <header class="site-header">
      <div class="shell header-inner">
        <a class="brand" href="#top" aria-label="Vibe Music 首页"
          ><span class="brand-mark"
            ><svg class="icon"><use href="#i-logo" /></svg></span
          >Vibe Music<span class="brand-period">.</span></a
        >
        <nav class="main-nav" aria-label="主导航">
          <a href="#discover">发现音乐</a><a href="#ai">AI 音乐助手</a
          ><a href="#collection">我的音乐生活</a>
        </nav>
        <div class="header-actions">
          <a class="login-link" href="/login">登录 / 注册</a
          ><a class="button button-small button-outline" href="/" data-explore
            >进入音乐<svg class="icon"><use href="#i-arrow" /></svg
          ></a>
        </div>
      </div>
    </header>
    <main id="main">
      <section class="hero shell" id="top" aria-labelledby="hero-title">
        <div class="hero-copy">
          <p class="hero-kicker">
            <span class="tiny-wave" aria-hidden="true"
              ><i></i><i></i><i></i><i></i></span
            >每一种心情，都有它的旋律
          </p>
          <h1 id="hero-title">让音乐，<br />贴近此刻的你。</h1>
          <p class="hero-description">
            发现下一首喜欢的歌，<br />也可以和 AI 聊聊，找到适合当下的声音。
          </p>
          <div class="hero-actions">
            <a class="button button-primary" href="/" data-explore
              >探索音乐<svg class="icon"><use href="#i-arrow" /></svg></a
            ><button class="button button-text" id="hero-listen" type="button">
              <svg class="icon"><use href="#i-play" /></svg
              ><span>先听一首</span>
            </button>
          </div>
          <p class="hero-footnote">
            <svg class="icon"><use href="#i-headphones" /></svg
            >无需登录，先从一首歌开始
          </p>
        </div>
        <div class="hero-stage">
          <div class="stage-aura" aria-hidden="true"></div>
          <section class="music-player" aria-label="音乐试听播放器" id="player">
            <div class="player-top">
              <span class="mini-brand"
                ><svg class="icon"><use href="#i-logo" /></svg>Vibe Music</span
              ><span class="player-top-label">此刻想听</span
              ><span class="live-dot" title="本地曲库"></span>
            </div>
            <div class="player-body">
              <div class="album-layout">
                <div class="album-art">
                  <img
                    id="current-cover"
                    :src="tracks[0].coverUrl"
                    :alt="`${tracks[0].songName} 专辑封面`"
                    fetchpriority="high"
                    width="220"
                    height="220"
                  /><span class="cover-shine" aria-hidden="true"></span>
                </div>
                <div class="track-info">
                  <span class="track-context">{{ contexts[0].context }}</span>
                  <h2 id="current-title">{{ tracks[0].songName }}</h2>
                  <p id="current-artist">{{ tracks[0].artistName }}</p>
                  <span class="track-tag" id="current-tag">{{
                    contexts[0].tag
                  }}</span
                  ><a class="save-track" href="/login"
                    ><svg class="icon"><use href="#i-heart" /></svg
                    >登录后收藏</a
                  >
                </div>
              </div>
              <div class="playback">
                <label class="sr-only" for="seek">播放进度</label
                ><input
                  id="seek"
                  class="seek"
                  type="range"
                  min="0"
                  max="100"
                  value="0"
                  step="0.1"
                  disabled
                  aria-valuetext="尚未播放"
                />
                <div class="time-row">
                  <span id="elapsed">0:00</span><span id="duration">—:—</span>
                </div>
                <div class="transport">
                  <button
                    class="icon-button volume-toggle"
                    id="mute"
                    type="button"
                    aria-label="静音"
                    aria-pressed="false"
                  >
                    <svg class="icon"><use href="#i-volume" /></svg>
                  </button>
                  <div class="transport-main">
                    <button
                      class="icon-button"
                      id="previous"
                      type="button"
                      aria-label="上一首"
                      disabled
                    >
                      <svg class="icon"><use href="#i-prev" /></svg></button
                    ><button
                      class="play-button"
                      id="play"
                      type="button"
                      :aria-label="`播放 ${tracks[0].songName}`"
                      disabled
                    >
                      <svg class="icon"><use href="#i-play" /></svg></button
                    ><button
                      class="icon-button"
                      id="next"
                      type="button"
                      aria-label="下一首"
                      disabled
                    >
                      <svg class="icon"><use href="#i-next" /></svg>
                    </button>
                  </div>
                  <a
                    class="icon-button queue-link"
                    href="#discover"
                    aria-label="浏览更多歌曲"
                    ><svg class="icon"><use href="#i-list" /></svg
                  ></a>
                </div>
              </div>
              <div class="player-bottom">
                <span class="equalizer" aria-hidden="true"
                  ><i></i><i></i><i></i><i></i></span
                ><span id="player-status" role="status">正在准备音乐…</span
                ><span class="quality-label">在这里，听见喜欢</span>
              </div>
            </div>
          </section>
          <aside class="ai-float" aria-label="AI 推荐对话示例">
            <div class="ai-float-heading">
              <span class="spark-box"
                ><svg class="icon"><use href="#i-spark" /></svg></span
              ><strong>你的 AI 音乐搭子</strong
              ><span class="example-label">对话示例</span>
            </div>
            <p class="sample-question">“想听点轻快的，给今天充充电。”</p>
            <p class="sample-answer">
              试试 Dua Lipa，让明快的节奏<br
                class="float-break"
              />陪你进入好状态。
            </p>
            <a href="#ai"
              >看看它怎么找歌<svg class="icon"><use href="#i-arrow" /></svg
            ></a>
          </aside>
        </div>
      </section>
      <section
        class="discover shell"
        id="discover"
        aria-labelledby="discover-title"
      >
        <div class="section-heading">
          <div>
            <p class="section-label">发现音乐</p>
            <h2 id="discover-title">下一首，或许就会喜欢。</h2>
            <p>从一首歌出发，遇见更多好声音。</p>
          </div>
          <a class="text-link" href="/" data-explore
            >浏览更多音乐<svg class="icon"><use href="#i-arrow" /></svg
          ></a>
        </div>
        <div
          class="music-grid"
          id="music-grid"
          aria-label="精选歌曲"
          aria-busy="true"
        >
          <p class="loading-placeholder">正在载入曲库…</p>
        </div>
      </section>
      <section class="ai-section" id="ai" aria-labelledby="ai-title">
        <div class="shell ai-section-inner">
          <div class="ai-copy">
            <p class="section-label">
              <svg class="icon"><use href="#i-spark" /></svg>AI 音乐助手
            </p>
            <h2 id="ai-title">不用知道歌名，<br />说说你现在的心情。</h2>
            <p class="section-description">
              想专注一会儿，还是让一天慢下来？<br />把场景告诉
              AI，一起找到合适的音乐。
            </p>
            <div
              class="scenario-buttons"
              role="group"
              aria-label="选择对话示例"
            >
              <button
                class="scenario-button active"
                type="button"
                data-scenario="focus"
                aria-pressed="true"
              >
                <svg class="icon"><use href="#i-book" /></svg>专注学习</button
              ><button
                class="scenario-button"
                type="button"
                data-scenario="morning"
                aria-pressed="false"
              >
                <svg class="icon"><use href="#i-sun" /></svg>通勤路上</button
              ><button
                class="scenario-button"
                type="button"
                data-scenario="night"
                aria-pressed="false"
              >
                <svg class="icon"><use href="#i-moon" /></svg>夜晚放松
              </button>
            </div>
            <a class="text-link ai-login" href="/login"
              >登录，聊聊你想听的<svg class="icon"><use href="#i-arrow" /></svg
            ></a>
          </div>
          <div class="conversation" aria-label="AI 推荐场景示例">
            <div class="conversation-header">
              <span
                ><span class="spark-box"
                  ><svg class="icon"><use href="#i-spark" /></svg></span
                ><strong>AI 音乐助手</strong></span
              ><span class="example-label">场景示例</span>
            </div>
            <div
              class="conversation-content"
              aria-live="polite"
              aria-atomic="true"
            >
              <p class="user-bubble" id="scenario-question">
                {{ scenarios.focus.question }}
              </p>
              <div class="assistant-message">
                <svg class="icon"><use href="#i-spark" /></svg>
                <div>
                  <p id="scenario-answer">{{ scenarios.focus.answer }}</p>
                  <div class="recommendation-note">
                    <span class="recommendation-art" id="scenario-art"
                      ><svg class="icon"><use href="#i-headphones" /></svg
                    ></span>
                    <div>
                      <strong id="scenario-title">{{
                        scenarios.focus.title
                      }}</strong
                      ><span id="scenario-style">{{
                        scenarios.focus.style
                      }}</span>
                    </div>
                  </div>
                  <p class="assistant-reason" id="scenario-reason">
                    {{ scenarios.focus.reason }}
                  </p>
                </div>
              </div>
            </div>
            <a class="chat-entry" href="/login"
              ><span>描述你的心情，开始一次音乐对话</span
              ><span class="chat-send"
                ><svg class="icon"><use href="#i-arrow" /></svg></span
            ></a>
          </div>
        </div>
      </section>
      <section
        class="collection shell"
        id="collection"
        aria-labelledby="collection-title"
      >
        <div class="collection-intro">
          <p class="section-label">我的音乐生活</p>
          <h2 id="collection-title">喜欢的声音，<br />值得再听一遍。</h2>
          <p class="section-description">
            把偶然听到的喜欢，<br />变成随时都能回来的地方。
          </p>
        </div>
        <div class="collection-features">
          <article class="collection-feature">
            <span class="feature-symbol"
              ><svg class="icon"><use href="#i-heart" /></svg
            ></span>
            <div>
              <h3>收藏每一次心动</h3>
              <p>喜欢的歌曲，收进自己的音乐收藏。</p>
            </div>
          </article>
          <article class="collection-feature">
            <span class="feature-symbol"
              ><svg class="icon"><use href="#i-list" /></svg
            ></span>
            <div>
              <h3>让歌单装下日常</h3>
              <p>为通勤、学习或放松，整理一份专属歌单。</p>
            </div>
          </article>
          <article class="collection-feature">
            <span class="feature-symbol"
              ><svg class="icon"><use href="#i-disc" /></svg
            ></span>
            <div>
              <h3>跟着旋律，发现更多</h3>
              <p>从歌曲走进专辑，继续探索喜欢的歌手。</p>
            </div>
          </article>
          <a class="text-link" href="/login"
            >登录，建立你的音乐收藏<svg class="icon">
              <use href="#i-arrow" /></svg
          ></a>
        </div>
      </section>
      <section class="closing shell" aria-labelledby="closing-title">
        <div class="closing-wave" aria-hidden="true">
          <i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i
          ><i></i>
        </div>
        <div>
          <h2 id="closing-title">把此刻，交给音乐。</h2>
          <p>你的下一首喜欢，就从这里开始。</p>
        </div>
        <a class="button button-primary" href="/" data-explore
          >开始探索<svg class="icon"><use href="#i-arrow" /></svg
        ></a>
      </section>
      <section class="about shell" id="about">
        <details>
          <summary>
            <span>关于 Vibe Music</span
            ><span class="about-summary"
              >音乐与 AI，在这里相遇<svg class="icon">
                <use href="#i-down" /></svg
            ></span>
          </summary>
          <div class="about-content">
            <p>
              Vibe Music 将音乐发现、在线播放、个人歌单与 AI
              音乐对话放在一起。AI
              助手通过理解你的描述，调用音乐检索工具，再结合曲库信息给出回答。
            </p>
            <div class="architecture">
              <div>
                <span>使用界面</span><strong>Vue 3 · TypeScript</strong>
              </div>
              <div>
                <span>音乐服务</span><strong>Spring Boot · MyBatis-Plus</strong>
              </div>
              <div><span>AI 对话</span><strong>LangChain4j · Qwen</strong></div>
              <div>
                <span>数据与媒体</span><strong>MySQL · Redis · MinIO</strong>
              </div>
            </div>
          </div>
        </details>
      </section>
    </main>
    <footer class="shell site-footer">
      <a class="brand" href="#top"
        ><span class="brand-mark"
          ><svg class="icon"><use href="#i-logo" /></svg></span
        >Vibe Music<span class="brand-period">.</span></a
      ><span>让音乐贴近此刻。</span><small>© 2026 Vibe Music</small>
    </footer>
    <button
      class="motion-toggle"
      id="motion-toggle"
      type="button"
      aria-label="暂停背景动效"
      aria-pressed="true"
      hidden
    >
      <svg class="icon" aria-hidden="true"><use href="#i-logo" /></svg>
      <span id="motion-label">背景动效：开</span>
      <span class="motion-indicator" aria-hidden="true"></span>
    </button>
    <audio id="audio" preload="metadata"></audio>
  </div>
</template>
