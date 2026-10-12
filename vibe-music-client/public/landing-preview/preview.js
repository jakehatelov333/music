;(() => {
  'use strict'
  const audio = document.getElementById('audio')
  const play = document.getElementById('play')
  const heroListen = document.getElementById('hero-listen')
  const seek = document.getElementById('seek')
  const status = document.getElementById('player-status')
  const grid = document.getElementById('music-grid')
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
  let tracks = []
  let current = 0
  let generation = 0
  let pending = false
  const formatTime = (value) =>
    Number.isFinite(value)
      ? `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, '0')}`
      : '—:—'
  const icon = (name) => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('class', 'icon')
    svg.setAttribute('aria-hidden', 'true')
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use')
    use.setAttribute('href', `#i-${name}`)
    svg.append(use)
    return svg
  }
  const element = (tag, className, text) => {
    const node = document.createElement(tag)
    node.className = className
    if (text !== undefined) node.textContent = text
    return node
  }
  function syncPlayback() {
    const playing = !audio.paused && !audio.ended
    const name = tracks[current]?.songName || '歌曲'
    play.replaceChildren(icon(playing ? 'pause' : 'play'))
    play.setAttribute('aria-label', `${playing ? '暂停' : '播放'} ${name}`)
    heroListen.replaceChildren(
      icon(playing ? 'pause' : 'play'),
      element('span', '', playing ? '暂停试听' : '先听一首')
    )
    document.getElementById('player').classList.toggle('is-playing', playing)
    grid.querySelectorAll('.song-card').forEach((card, index) => {
      const active = index === current
      card.classList.toggle('active', active)
      const cardButton = card.querySelector('button')
      cardButton.setAttribute(
        'aria-label',
        `${active && playing ? '暂停' : '播放'} ${tracks[index].songName}，${tracks[index].artistName}`
      )
      card
        .querySelector('.song-play')
        .replaceChildren(icon(active && playing ? 'pause' : 'play'))
    })
  }
  function syncTime() {
    const duration = audio.duration
    const elapsed = audio.currentTime
    const percentage =
      Number.isFinite(duration) && duration > 0 ? (elapsed / duration) * 100 : 0
    document.getElementById('elapsed').textContent = formatTime(elapsed)
    document.getElementById('duration').textContent = formatTime(duration)
    seek.disabled = !Number.isFinite(duration) || duration <= 0
    seek.value = String(percentage)
    seek.style.setProperty('--progress', `${percentage}%`)
    seek.setAttribute(
      'aria-valuetext',
      `${formatTime(elapsed)} / ${formatTime(duration)}`
    )
  }
  async function startPlayback() {
    if (!tracks.length || pending) return
    const selectedGeneration = generation
    pending = true
    status.textContent = '正在加载音频…'
    try {
      await audio.play()
      if (selectedGeneration === generation)
        status.textContent = '正在播放 · 享受这一首'
    } catch (error) {
      if (selectedGeneration === generation && error.name !== 'AbortError') {
        status.textContent = '暂时无法播放，请稍后点击重试'
      }
    } finally {
      if (selectedGeneration === generation) pending = false
      syncPlayback()
    }
  }
  function chooseTrack(index, shouldPlay = false) {
    if (!tracks.length) return
    generation += 1
    pending = false
    audio.pause()
    current = (index + tracks.length) % tracks.length
    const track = tracks[current]
    const context = contexts[current]
    audio.src = track.audioUrl
    const cover = document.getElementById('current-cover')
    cover.src = track.coverUrl
    cover.alt = `${track.songName} 专辑封面`
    document.getElementById('current-title').textContent = track.songName
    document.getElementById('current-artist').textContent = track.artistName
    document.getElementById('current-tag').textContent = context.tag
    document.querySelector('.track-context').textContent = context.context
    status.textContent = '已就绪 · 点击播放，听听看'
    syncTime()
    syncPlayback()
    if (shouldPlay) void startPlayback()
  }
  function togglePlayback() {
    if (pending) {
      generation += 1
      pending = false
      audio.pause()
      status.textContent = '已暂停 · 随时继续'
    } else if (audio.paused) {
      void startPlayback()
    } else {
      audio.pause()
    }
  }
  function renderTracks() {
    const fragment = document.createDocumentFragment()
    tracks.forEach((track, index) => {
      const card = element('article', 'song-card')
      const button = element('button', 'song-cover-button')
      button.type = 'button'
      const cover = element('img', '')
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
    grid.replaceChildren(fragment)
    grid.setAttribute('aria-busy', 'false')
  }
  async function loadLibrary() {
    try {
      const response = await fetch('./library.json')
      if (!response.ok) throw new Error('Library unavailable')
      const data = await response.json()
      if (
        !Array.isArray(data) ||
        data.length !== 4 ||
        data.some(
          (track) => !track.songName || !track.audioUrl || !track.coverUrl
        )
      )
        throw new Error('Invalid library')
      tracks = data
      renderTracks()
      chooseTrack(0)
      play.disabled = false
      heroListen.disabled = false
      document.getElementById('next').disabled = false
      document.getElementById('previous').disabled = false
    } catch {
      status.textContent = '暂时无法载入曲库，请刷新重试'
      grid.replaceChildren(
        element(
          'p',
          'loading-placeholder',
          '歌曲暂时未能载入，请刷新页面重试。'
        )
      )
      grid.setAttribute('aria-busy', 'false')
    }
  }
  play.addEventListener('click', togglePlayback)
  heroListen.addEventListener('click', togglePlayback)
  heroListen.disabled = true
  document.getElementById('next').disabled = true
  document.getElementById('previous').disabled = true
  document
    .getElementById('next')
    .addEventListener('click', () =>
      chooseTrack(current + 1, !audio.paused || pending)
    )
  document
    .getElementById('previous')
    .addEventListener('click', () =>
      chooseTrack(current - 1, !audio.paused || pending)
    )
  document.getElementById('mute').addEventListener('click', (event) => {
    audio.muted = !audio.muted
    event.currentTarget.setAttribute('aria-pressed', String(audio.muted))
    event.currentTarget.setAttribute(
      'aria-label',
      audio.muted ? '取消静音' : '静音'
    )
  })
  seek.addEventListener('input', () => {
    if (Number.isFinite(audio.duration))
      audio.currentTime = (Number(seek.value) / 100) * audio.duration
    syncTime()
  })
  audio.addEventListener('loadedmetadata', syncTime)
  audio.addEventListener('durationchange', syncTime)
  audio.addEventListener('timeupdate', syncTime)
  audio.addEventListener('play', syncPlayback)
  audio.addEventListener('playing', () => {
    status.textContent = '正在播放 · 享受这一首'
  })
  audio.addEventListener('pause', () => {
    syncPlayback()
    if (tracks.length) status.textContent = '已暂停 · 随时继续'
  })
  audio.addEventListener('ended', () => {
    syncPlayback()
    status.textContent = '这首听完了 · 试试下一首'
  })
  audio.addEventListener('waiting', () => {
    if (!audio.paused) status.textContent = '正在缓冲…'
  })
  audio.addEventListener('error', () => {
    status.textContent = '音频暂时不可用，请重试或切换歌曲'
    syncPlayback()
  })
  document.querySelectorAll('[data-explore]').forEach((link) =>
    link.addEventListener('click', (event) => {
      try {
        sessionStorage.setItem('vibe-music-guest-mode', 'true')
      } catch {
        event.preventDefault()
        window.location.assign('/login')
      }
    })
  )
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
  }
  document.querySelectorAll('[data-scenario]').forEach((button) =>
    button.addEventListener('click', () => {
      const scenario = scenarios[button.dataset.scenario]
      document.querySelectorAll('[data-scenario]').forEach((item) => {
        item.classList.toggle('active', item === button)
        item.setAttribute('aria-pressed', String(item === button))
      })
      for (const field of ['question', 'answer', 'title', 'style', 'reason'])
        document.getElementById(`scenario-${field}`).textContent =
          scenario[field]
      const art = document.getElementById('scenario-art')
      art.style.background = scenario.color
      art.replaceChildren(icon(scenario.icon))
    })
  )
  document
    .querySelectorAll('svg.icon')
    .forEach((svg) => svg.setAttribute('aria-hidden', 'true'))
  void loadLibrary()
})()
