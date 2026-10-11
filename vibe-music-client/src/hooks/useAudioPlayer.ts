import { trackModel } from '@/stores/interface'
import { defaultSong } from '@/mock'
import { ElNotification } from 'element-plus'
import { PlayMode } from './interface'
import { urlV1 } from '@/api'

interface AudioPlayer {
  isPlaying: Ref<boolean>
  currentTrack: ComputedRef<trackModel>
  currentTime: Ref<number>
  duration: Ref<number>
  volume: Ref<number>
  audioElement: Ref<HTMLAudioElement | null>
  play: () => void
  pause: () => void
  nextTrack: () => void
  prevTrack: () => void
  seek: (time: number) => void
  togglePlayPause: () => void
  setVolume: (volume: number) => void
  setPlayMode: (mode: PlayMode) => void
  loadTrack: () => Promise<void>
}

/**
 * 全局唯一的 <audio> 元素。
 *
 * 关键点：**必须 append 到 document 上**。
 * 旧实现用 `new Audio()` 创建的是游离节点（从未插入文档），
 * 页面切到后台标签页时浏览器可能把它当成可回收对象挂起 / 回收，
 * 表现就是「切到管理端那个标签页，音乐就停了」。
 * 挂进 DOM 后浏览器才会把它当作正常的媒体元素，后台播放才稳定。
 */
let sharedAudioElement: HTMLAudioElement | null = null

const getSharedAudioElement = (): HTMLAudioElement => {
  if (sharedAudioElement) return sharedAudioElement

  const el = document.createElement('audio')
  el.id = 'vibe-music-audio'
  el.preload = 'auto'
  el.setAttribute('playsinline', 'true')
  // 视觉上完全不可见，但保持「已渲染」状态，
  // 避免个别浏览器内核把 display:none 的媒体元素当作可挂起对象。
  el.style.position = 'absolute'
  el.style.width = '1px'
  el.style.height = '1px'
  el.style.opacity = '0'
  el.style.pointerEvents = 'none'
  el.style.left = '-9999px'

  const attach = () => {
    if (!document.body.contains(el)) document.body.appendChild(el)
  }
  if (document.body) attach()
  else document.addEventListener('DOMContentLoaded', attach, { once: true })

  sharedAudioElement = el
  return el
}

const setPlaybackState = (state: 'none' | 'paused' | 'playing') => {
  if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
  try {
    navigator.mediaSession.playbackState = state
  } catch {
    /* 部分浏览器不支持，忽略 */
  }
}

const bindMediaAction = (action: MediaSessionAction, handler: (details?: any) => void) => {
  if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
  try {
    navigator.mediaSession.setActionHandler(action, handler)
  } catch {
    /* 该 action 在当前浏览器不受支持，忽略 */
  }
}

const createAudioPlayer = (): AudioPlayer => {
  const audioStore = AudioStore()
  const audioElement = ref<HTMLAudioElement | null>(null)
  const isPlaying = ref(false)
  const volume = ref(50)
  const playMode = ref<PlayMode>('order') // 默认为顺序播放

  // 当前播放的歌曲
  const currentTrack = computed<trackModel>(
    () => audioStore.trackList[audioStore.currentSongIndex] || defaultSong
  )
  const currentTime = ref(0)
  const duration = ref(0)

  // 用户是否「希望」在播放（用于 canplay 回调里判断期间有没有被暂停）
  let playRequested = false

  // —— 系统媒体控制（后台播放 / 锁屏 / 媒体键）——
  // 接入 Media Session 后，浏览器会把本页当成一个真正的媒体会话，
  // 切到其他标签页时不会把它当作「无声页面」挂起。
  const updateMediaSessionMetadata = () => {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
    if (typeof MediaMetadata === 'undefined') return

    const track = currentTrack.value
    if (!track || !track.title) return

    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: track.title,
        artist: track.artist || '未知歌手',
        album: track.album || '',
        artwork: track.cover
          ? [{ src: track.cover, sizes: '512x512', type: 'image/jpeg' }]
          : [],
      })
    } catch {
      /* 忽略元数据设置失败 */
    }
  }

  const bindMediaSessionActions = () => {
    bindMediaAction('play', () => play())
    bindMediaAction('pause', () => pause())
    bindMediaAction('previoustrack', () => {
      void prevTrack()
    })
    bindMediaAction('nexttrack', () => {
      void nextTrack()
    })
    bindMediaAction('seekto', (details) => {
      if (details && typeof details.seekTime === 'number') seek(details.seekTime)
    })
  }

  // 播放音乐
  const play = () => {
    const el = audioElement.value
    if (!el) return

    playRequested = true

    // 没有可用音源时先加载，再播放
    if (!el.getAttribute('src')) {
      void loadTrack().then(() => play())
      return
    }

    const start = () => {
      if (!playRequested) return
      const result = el.play()
      // play() 返回 Promise：被浏览器拒绝（自动播放策略、音源未就绪）时
      // 旧代码忽略了这个 Promise，导致 isPlaying 显示为 true 但实际没声音。
      if (result && typeof result.catch === 'function') {
        result.catch((error: DOMException) => {
          isPlaying.value = false
          setPlaybackState('paused')
          if (error?.name === 'NotAllowedError') {
            ElNotification({
              title: '播放被浏览器拦截',
              message: '请点击播放按钮重试',
              type: 'warning',
              duration: 2500,
            })
          }
        })
      }
    }

    // load() 之后立刻 play() 可能因为还没 canplay 被拒绝，
    // 这里等一次 canplay 再播，避免「切歌后卡在 0:00」。
    // HAVE_CURRENT_DATA === 2
    if (el.readyState < 2) {
      const onCanPlay = () => {
        el.removeEventListener('canplay', onCanPlay)
        start()
      }
      el.addEventListener('canplay', onCanPlay)
      window.setTimeout(() => el.removeEventListener('canplay', onCanPlay), 10000)
    } else {
      start()
    }
  }

  // 跳转到指定时间
  const seek = (time: number) => {
    const el = audioElement.value
    if (!el || !Number.isFinite(time)) return
    el.currentTime = time
    currentTime.value = time
  }

  // 暂停音乐
  const pause = () => {
    const el = audioElement.value
    if (!el) return
    playRequested = false
    el.pause()
    isPlaying.value = false
  }

  // 播放下一首
  const nextTrack = async () => {
    switch (playMode.value) {
      case 'loop':
        if (audioStore.currentSongIndex < audioStore.trackList.length - 1) {
          audioStore.currentSongIndex++
        } else {
          audioStore.currentSongIndex = 0 // 从头开始
        }
        break
      case 'shuffle':
        audioStore.currentSongIndex = Math.floor(
          Math.random() * audioStore.trackList.length
        )
        break
      case 'single':
        if (audioElement.value) audioElement.value.currentTime = 0
        break
      case 'order':
      default:
        if (audioStore.currentSongIndex < audioStore.trackList.length - 1) {
          audioStore.currentSongIndex++
        } else {
          audioStore.currentSongIndex = 0 // 从头开始
        }
        break
    }
    await loadTrack()
    play()
  }

  // 播放上一首
  const prevTrack = async () => {
    switch (playMode.value) {
      case 'loop':
        if (audioStore.currentSongIndex > 0) {
          audioStore.currentSongIndex--
        } else {
          audioStore.currentSongIndex = audioStore.trackList.length - 1 // 从尾开始
        }
        break
      case 'shuffle':
        audioStore.currentSongIndex = Math.floor(
          Math.random() * audioStore.trackList.length
        )
        break
      case 'single':
        if (audioElement.value) audioElement.value.currentTime = 0
        break
      case 'order':
      default:
        if (audioStore.currentSongIndex > 0) {
          audioStore.currentSongIndex--
        } else {
          audioStore.currentSongIndex = audioStore.trackList.length - 1 // 从尾开始
        }
        break
    }
    await loadTrack()
    play()
  }

  // 加载当前歌曲
  const loadTrack = async () => {
    // 检查歌曲 URL
    await checkUrl()

    currentTime.value = 0
    duration.value = 0

    const el = audioElement.value
    if (!el) return

    const url = currentTrack.value?.url
    if (!url) {
      // 没有音源：清空 src，避免出现 src="undefined" 这种无效请求
      el.removeAttribute('src')
      el.load()
      isPlaying.value = false
      return
    }

    el.src = url
    el.load()
    updateMediaSessionMetadata()
  }

  // 检查歌曲 URL
  const checkUrl = async () => {
    // 查看歌曲 URL 是否存在
    if (!currentTrack.value?.url) {
      // 如果 currentTrack 的 url 不存在，则获取 URL
      const response = await urlV1(currentTrack.value.id)
      const url = response.data[0]?.url // 获取第一个 URL

      if (!url) return
      // 更新 trackList 中的对应歌曲的 url
      const trackIndex = audioStore.trackList.findIndex(
        (track: { id: any }) => track.id === currentTrack.value.id
      )
      if (trackIndex !== -1) {
        audioStore.trackList[trackIndex].url = url // 更新 URL
      }
    }
    return Promise.resolve()
  }

  // 更新当前播放时间
  const updateTime = () => {
    if (audioElement.value) {
      currentTime.value = audioElement.value.currentTime
    }
  }

  // 更新总时长
  const onLoadedMetadata = () => {
    if (audioElement.value) {
      duration.value = audioElement.value.duration
    }
  }

  // 切换播放/暂停状态
  const togglePlayPause = () => {
    if (isPlaying.value) {
      pause()
    } else {
      play()
    }
  }

  // 设置音量
  const setVolume = (newVolume: number) => {
    if (audioElement.value) {
      volume.value = newVolume
      audioStore.setAudioStore('volume', newVolume)
      audioElement.value.volume = newVolume / 100
    }
  }

  // 设置播放模式
  const setPlayMode = (mode: PlayMode) => {
    playMode.value = mode
    const modeText = {
      order: '顺序播放',
      shuffle: '随机播放',
      loop: '列表循环',
      single: '单曲循环',
    }
    ElNotification({
      title: '播放模式',
      message: `已切换为${modeText[mode]}`,
      type: 'success',
    })
  }

  // —— 初始化：只做一次，不依赖组件生命周期 ——
  const el = getSharedAudioElement()
  audioElement.value = el

  const initialVolume = audioStore.volume || 50
  volume.value = initialVolume
  el.volume = initialVolume / 100

  el.addEventListener('timeupdate', updateTime)
  el.addEventListener('loadedmetadata', onLoadedMetadata)
  el.addEventListener('ended', () => {
    isPlaying.value = false
    void nextTrack()
  })
  // isPlaying 由真实事件驱动，而不是手动置位
  el.addEventListener('play', () => {
    isPlaying.value = true
    setPlaybackState('playing')
  })
  el.addEventListener('pause', () => {
    isPlaying.value = false
    if (!el.ended) setPlaybackState('paused')
  })
  el.addEventListener('error', () => {
    isPlaying.value = false
    // 只是预加载失败（比如历史列表里过期的音源）时不打扰用户，
    // 只有用户主动点了播放才提示。
    if (!playRequested) return
    const src = el.getAttribute('src') || ''
    if (!src || src === 'undefined') return
    ElNotification({
      title: '播放失败',
      message: '音频加载失败，请检查网络或音源地址',
      type: 'error',
      duration: 2500,
    })
  })

  bindMediaSessionActions()
  updateMediaSessionMetadata()

  // 恢复上一次的音源（不自动播放，等用户操作）
  const restoredUrl = currentTrack.value?.url
  if (restoredUrl) el.src = restoredUrl

  // 切歌时同步系统媒体信息
  watch(
    () => currentTrack.value.id,
    () => updateMediaSessionMetadata()
  )

  const audioPlayer: AudioPlayer = {
    isPlaying,
    currentTrack,
    currentTime,
    duration,
    volume,
    audioElement,
    play,
    pause,
    nextTrack,
    prevTrack,
    seek,
    togglePlayPause,
    setVolume,
    setPlayMode,
    loadTrack,
  }

  return audioPlayer
}

/**
 * 播放器单例。
 *
 * 用 `effectScope(true)`（detached scope）承载内部响应式状态，
 * 这样播放器**不绑定任何组件的生命周期**：
 * 无论是路由切换、还是跳到 /login、/landing 这类 standalone 路由，
 * 播放器和它的 <audio> 元素都不会被销毁，音乐可以一直放下去。
 */
let playerInstance: AudioPlayer | null = null

export const AudioPlayer = (): AudioPlayer => {
  if (playerInstance) return playerInstance

  const scope = effectScope(true)
  const created = scope.run(() => createAudioPlayer())
  if (!created) {
    throw new Error('AudioPlayer 初始化失败')
  }
  playerInstance = created
  return playerInstance
}

export const useAudioPlayer = (): AudioPlayer => {
  const injected = inject<AudioPlayer | null>('audioPlayer', null)
  // 兼容没有走到 provide 的场景（例如单测 / 独立组件），回退到全局单例
  if (!injected) return AudioPlayer()
  return injected
}
