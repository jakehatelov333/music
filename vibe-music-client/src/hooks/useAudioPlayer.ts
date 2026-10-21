import { trackModel } from '@/stores/interface'
import { defaultSong } from '@/mock'
import { ElNotification } from 'element-plus'
import { PlayMode } from './interface'
import { urlV1 } from '@/api'
import { recordPlay } from '@/api/playHistory'
import { PlaybackTracker } from '@/utils/playHistory'
import { UserStore } from '@/stores/modules/user'
interface AudioPlayer {
  isPlaying: Ref<boolean>
  currentTrack: ComputedRef<trackModel>
  currentTime: Ref<number>
  duration: Ref<number>
  volume: Ref<number>
  //   currentLyricIndex: Ref<number>
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

export const AudioPlayer = () => {
  const audioStore = AudioStore()
  const audioElement = ref<HTMLAudioElement | null>(null)
  const isPlaying = ref(false)
  const volume = ref()
  const playMode = ref<PlayMode>('order') // 默认为顺序播放

  // 当前播放的歌曲
  const currentTrack = computed<trackModel>(
    () => audioStore.trackList[audioStore.currentSongIndex] || defaultSong
  )
  const currentTime = ref(0)
  const duration = ref(0)

  /*
   * ---------------------------------------------------------------- 播放历史
   *
   * 只在「有效播放」时上报一次（判定规则见 utils/playHistory.ts）。
   *
   * 三个刻意的约束：
   * 1. **不阻塞播放**：上报是 fire-and-forget，且失败不弹提示 ——
   *    用户点播放是为了听歌，不是为了看「记录失败」的红字。
   * 2. **游客不上报**：后端会 401，前端先判断登录态，省掉一次必然失败的请求。
   *    游客的「播放列表」仍然留在本地 localStorage（原有行为，未改动）。
   * 3. **旧事件必须清理**：切歌 / 卸载时 tracker 重置，避免上一首的累计时长
   *    算到下一首头上。
   */
  const userStore = UserStore()
  const playback = new PlaybackTracker(
    async (songId, playId, positionSeconds) => {
      if (!userStore.isLoggedIn) return
      try {
        await recordPlay(Number(songId), playId, positionSeconds)
      } catch {
        // 网络失败 / 后端 5xx：静默忽略。播放本身不受影响，
        // 也不做无限重试 —— 播放历史是「锦上添花」，不值得为它堆重试队列。
      }
    }
  )

  //   // 用于追踪当前歌词索引
  //   const currentLyricIndex = ref(0)

  //   currentTrack.value.lyrics?.lyrics

  //   // 更新当前歌曲歌词索引
  //   const updateCurrentLyricIndex = (newTime: number = 0) => {
  //     if (!currentTrack.value.lyrics?.lines) return

  //     // 找到当前时间对应的歌词行
  //     const lyrics = currentTrack.value.lyrics.lines
  //     const targetIndex = lyrics.findIndex(
  //       (line: { time: number }) => line.time > newTime * 1000
  //     )
  //     currentLyricIndex.value =
  //       targetIndex === -1 ? lyrics.length - 1 : targetIndex - 1
  //   }

  // 播放音乐
  const play = () => {
    if (audioElement.value) {
      // 开始/继续播放：tracker 负责判断这是「新的一次」还是「暂停后继续」。
      // 同一首歌重复点播放不会重置已累计的有效时长（除非明确 restart）。
      playback.start(String(currentTrack.value.id))
      const pending = audioElement.value.play()
      isPlaying.value = true
      // play() 返回 Promise：自动播放策略拦截、音频 404 时都会 reject。
      // 这类「其实没播起来」的情况必须让 tracker 丢弃本次尝试，
      // 否则用户看到的是「点了没反应，历史里却多了一条」。
      if (pending && typeof pending.catch === 'function') {
        pending.catch(() => {
          playback.fail()
        })
      }
    }
  }
  // 跳转到指定时间
  const seek = (time: number) => {
    if (audioElement.value) {
      audioElement.value.currentTime = time
      currentTime.value = time
      // 跳转后同步位置基准，避免把「拖动」的差值算成播放时长
      playback.tick(time, duration.value, isPlaying.value)
    }
  }
  // 暂停音乐
  const pause = () => {
    if (audioElement.value) {
      audioElement.value.pause()
      isPlaying.value = false
      // 暂停时同步一次位置（playing=false 不累加时长）
      playback.tick(currentTime.value, duration.value, false)
    }
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
        audioElement.value!.currentTime = 0
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
        audioElement.value!.currentTime = 0
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
    // 歌词是否存在
    // checkLyrics()

    currentTime.value = 0
    duration.value = 0
    // 切歌：开一次新的播放尝试。旧歌的累计时长与「已上报」标记必须在这里清掉，
    // 否则上一首听了 20 秒的进度会被算到新歌头上（甚至直接用旧 playId 上报新歌）。
    playback.start(String(currentTrack.value.id), true)

    if (audioElement.value) {
      audioElement.value.src = currentTrack.value.url
      audioElement.value.load()
    }
  }

  // 检查歌曲 URL
  const checkUrl = async () => {
    // 查看歌曲 URL 是否存在
    if (!currentTrack.value.url) {
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

  // 解析歌词数据
  //   const checkLyrics = () => {
  //     // 查看歌词是否存在
  //     if (!currentTrack.value.lyrics) {
  //       // 如果 currentTrack 的 lyrics 不存在，则获取歌词
  //       lyricNew(currentTrack.value.id).then((response) => {
  //         // 更新 trackList 中的对应歌曲的 url
  //         const trackIndex = audioStore.trackList.findIndex(
  //           (track: { id: any }) => track.id === currentTrack.value.id
  //         )
  //         if (trackIndex !== -1) {
  //           audioStore.trackList[trackIndex].lyrics =
  //             parseAndMergeLyrics(response) // 更新 URL
  //         }
  //       })
  //     }
  //   }

  // 更新当前播放时间
  const updateTime = () => {
    if (audioElement.value) {
      currentTime.value = audioElement.value.currentTime
      // 有效播放判定入口：只在真正在播时累加时长（isPlaying 为 false 时只同步位置）
      playback.tick(
        audioElement.value.currentTime,
        audioElement.value.duration,
        !audioElement.value.paused
      )
    }
  }

  // 更新总时长
  const onLoadedMetadata = () => {
    if (audioElement.value) {
      duration.value = audioElement.value.duration
    }
  }

  /**
   * 播放结束。
   *
   * <p>先让 tracker 结算（短歌可能在最后一个 timeupdate 之后才够阈值），
   * 再走原有的「自动下一首」。**单曲循环不会重复计数** ——
   * tracker 里已经记录过就不会再记（见 utils/playHistory.ts 的 start 注释）。
   */
  const onEnded = () => {
    playback.ended(audioElement.value?.duration ?? 0)
    void nextTrack()
  }

  /**
   * 播放失败。
   *
   * <p>失败**不记历史**，并丢弃本次累计 —— 下次播放同一首歌要重新计时。
   * 这里只清理 tracker，不打断原有播放流程（原来的实现本来就没有错误处理，
   * 不因为加了历史而改变播放行为）。
   */
  const onError = () => {
    playback.fail()
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

  //   // 更新currentLyricIndex
  //   watch(currentTime, (newTime) => {
  //     updateCurrentLyricIndex(newTime)
  //   })

  // 组件挂载时初始化音频元素
  onMounted(() => {
    audioElement.value = new Audio(currentTrack.value.url)
    volume.value = audioStore.volume || 50
    audioElement.value.volume = volume.value / 100
    // 歌词是否存在
    // checkLyrics()
    // 添加事件监听器
    audioElement.value.addEventListener('timeupdate', updateTime)
    audioElement.value.addEventListener('ended', onEnded)
    audioElement.value.addEventListener('loadedmetadata', onLoadedMetadata)
    // 播放失败（音频 404 / 网络中断 / 格式不支持）：不记历史，也不打断播放流程
    audioElement.value.addEventListener('error', onError)
  })

  // 组件卸载时移除事件监听器
  // 必须与 onMounted 注册的函数是**同一个引用**，否则移除不掉 —— 旧实现注册的是 nextTrack，
  // 这里改成 onEnded 后如果还按 nextTrack 移除，会留下一个悬挂监听（组件卸载后仍会触发）。
  onUnmounted(() => {
    if (audioElement.value) {
      audioElement.value.removeEventListener('timeupdate', updateTime)
      audioElement.value.removeEventListener('ended', onEnded)
      audioElement.value.removeEventListener('loadedmetadata', onLoadedMetadata)
      audioElement.value.removeEventListener('error', onError)
    }
    // 丢弃未上报的播放状态：卸载后不应该再有上报发生
    playback.dispose()
  })

  const audioPlayer: AudioPlayer = {
    isPlaying,
    currentTrack,
    currentTime,
    duration,
    volume,
    // currentLyricIndex,
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

export const useAudioPlayer = (): AudioPlayer => {
  const audioPlayer = inject<AudioPlayer>('audioPlayer')
  if (!audioPlayer) {
    throw new Error('useAudioPlayer must be used within a provider')
  }
  return audioPlayer
}
