;(() => {
  'use strict'

  const canvas = document.getElementById('ambient-canvas')
  const toggle = document.getElementById('motion-toggle')
  const label = document.getElementById('motion-label')
  const audio = document.getElementById('audio')
  const context = canvas?.getContext('2d', { alpha: true })
  if (!context || !toggle || !label) return

  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
  const coarsePointer = window.matchMedia('(pointer: coarse)')
  const storageKey = 'vibe-music-preview-motion'
  let preference = true
  try {
    preference = sessionStorage.getItem(storageKey) !== 'off'
  } catch {
    // The effect also works when browser storage is unavailable.
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
  let backgroundGradient
  let lineGradients = []
  const ripples = []
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
    // Bound the backing store on Retina and large monitors.
    scale = Math.min(
      window.devicePixelRatio || 1,
      compact ? 1.25 : 1.5,
      Math.sqrt(3000000 / (width * height))
    )
    canvas.width = Math.max(1, Math.round(width * scale))
    canvas.height = Math.max(1, Math.round(height * scale))
    context.setTransform(scale, 0, 0, scale, 0, 0)
    pointer.x = pointer.targetX = width * 0.68
    pointer.y = pointer.targetY = height * 0.38
    pointer.active = false
    pointer.strength = 0
    ripples.length = 0

    backgroundGradient = context.createRadialGradient(
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
      const gradient = context.createLinearGradient(0, 0, width, 0)
      gradient.addColorStop(0, `rgba(${left}, 0.68)`)
      gradient.addColorStop(0.2, `rgba(${left}, 0.4)`)
      gradient.addColorStop(0.43, `rgba(${left}, 0.18)`)
      gradient.addColorStop(0.67, `rgba(${right}, 0.25)`)
      gradient.addColorStop(1, `rgba(${right}, 0.68)`)
      return gradient
    })
    render()
  }

  function waveY(x, strand, band) {
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
    context.clearRect(0, 0, width, height)
    context.globalAlpha = 1
    context.fillStyle = backgroundGradient
    context.fillRect(0, 0, width, height)

    const strands = compact ? 4 : 6
    const step = compact ? 20 : 18
    for (let band = 0; band < 2; band += 1) {
      context.strokeStyle = lineGradients[band]
      for (let strand = 0; strand < strands; strand += 1) {
        const leading = strand === 1 || strand === strands - 1
        const opacity = Math.min(
          1,
          (leading ? 0.95 : 0.56 + (strand / strands) * 0.24) *
            (1 + energy * 0.1)
        )
        context.beginPath()
        context.moveTo(0, waveY(0, strand, band))
        for (let x = step; x < width; x += step)
          context.lineTo(x, waveY(x, strand, band))
        context.lineTo(width, waveY(width, strand, band))
        if (leading) {
          context.lineWidth = compact ? 5 : 7
          context.globalAlpha = opacity * 0.14
          context.stroke()
        }
        context.lineWidth = leading ? (compact ? 1.6 : 2) : 1.1
        context.globalAlpha = opacity
        context.stroke()
      }
      // Small travelling highlights make the lines read as sound, without a particle field.
      const highlights = compact ? 2 : 4
      context.fillStyle = band === 0 ? '#ddc7ff' : '#b8e2f5'
      for (let i = 0; i < highlights; i += 1) {
        const strand = i % strands
        const x = ((i / highlights + band * 0.17 + phase * 0.033) % 1) * width
        const edge = Math.abs(x / width - 0.5) * 2
        context.globalAlpha = 0.28 + edge * 0.4
        context.beginPath()
        context.arc(
          x,
          waveY(x, strand, band),
          compact ? 1.6 : 2,
          0,
          Math.PI * 2
        )
        context.fill()
      }
    }

    if (pointer.strength > 0.015) {
      const radius = compact ? 180 : 290
      const glow = context.createRadialGradient(
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
      context.globalAlpha = pointer.strength
      context.fillStyle = glow
      context.fillRect(
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
      const radius =
        12 + (1 - Math.pow(1 - progress, 2)) * (compact ? 160 : 260)
      const fade = Math.pow(1 - progress, 1.35)
      context.strokeStyle = '#d5b8ff'
      context.lineWidth = compact ? 1.5 : 2
      context.globalAlpha = fade * 0.72
      context.beginPath()
      context.arc(ripple.x, ripple.y, radius, 0, Math.PI * 2)
      context.stroke()
      context.lineWidth = 1.2
      context.globalAlpha = fade * 0.32
      context.beginPath()
      context.arc(ripple.x, ripple.y, radius * 0.72, 0, Math.PI * 2)
      context.stroke()
    }
    context.globalAlpha = 1
  }

  function tick(now) {
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
    toggle.disabled = reducedMotion.matches
    toggle.setAttribute('aria-pressed', String(enabled))
    toggle.setAttribute(
      'aria-label',
      reducedMotion.matches
        ? '背景已静止，遵循系统减少动态效果设置'
        : enabled
          ? '暂停背景动效'
          : '开启背景动效'
    )
    label.textContent = reducedMotion.matches
      ? '系统已减少动态'
      : enabled
        ? '背景动效：开'
        : '背景动效：关'
    toggle.title = reducedMotion.matches
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

  window.addEventListener(
    'pointermove',
    (event) => {
      if (!enabled || event.pointerType === 'touch') return
      pointer.targetX = event.clientX
      pointer.targetY = event.clientY
      pointer.active = true
    },
    { passive: true }
  )
  document.documentElement.addEventListener('pointerleave', () => {
    pointer.active = false
  })
  window.addEventListener('blur', () => {
    pointer.active = false
  })
  window.addEventListener(
    'click',
    (event) => {
      if (
        !enabled ||
        event.detail === 0 ||
        !(event.target instanceof Element) ||
        event.target.closest(blockers) ||
        window.getSelection()?.toString()
      )
        return
      if (ripples.length >= 5) ripples.shift()
      ripples.push({ x: event.clientX, y: event.clientY, started: clock })
    },
    { passive: true }
  )
  toggle.addEventListener('click', () => {
    if (reducedMotion.matches) return
    preference = !preference
    try {
      sessionStorage.setItem(storageKey, preference ? 'on' : 'off')
    } catch {
      /* Session-only preference. */
    }
    updatePreference()
  })
  window.addEventListener('resize', resize, { passive: true })
  coarsePointer.addEventListener('change', resize)
  reducedMotion.addEventListener('change', updatePreference)
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stop()
    else resume()
  })
  window.addEventListener('pagehide', () => {
    disposed = true
    stop()
  })
  window.addEventListener('pageshow', () => {
    disposed = false
    resume()
  })
  audio?.addEventListener('playing', () => {
    playing = true
  })
  for (const event of ['pause', 'ended', 'waiting', 'emptied', 'error'])
    audio?.addEventListener(event, () => {
      playing = false
    })

  resize()
  toggle.hidden = false
  updatePreference()
})()
