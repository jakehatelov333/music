/**
 * 「有效播放」判定与上报。
 *
 * <p>这个模块刻意不依赖 Vue / Pinia / 播放器实例，只做纯计算 + 回调，
 * 因此可以用 node --test 直接测（不需要挂载组件）。
 *
 * <h2>什么叫「有效播放」</h2>
 *
 * 同时满足下面两条才算一次：
 * <ol>
 *   <li>**播放时间**：从本次播放开始，累计真正在播的时间 ≥ {@link EFFECTIVE_PLAY_SECONDS}
 *       （或歌曲本身的 50%，取两者较小值 —— 很短的歌播到一半就该算听过）；</li>
 *   <li>**进度位置**：音频 currentTime ≥ {@link EFFECTIVE_PLAY_MIN_POSITION_SECONDS}。</li>
 * </ol>
 *
 * <p>为什么要两条：只有时长没有位置，用户「播够时长然后拖回开头」会被重复计；
 * 只有位置没有时长，「直接拖到中间」也会被误判成有效播放。
 *
 * <h2>为什么不是「一开始播放就记」</h2>
 *
 * 页面刚加载、用户误点、播放器初始化、自动恢复上次进度 —— 这些都会触发 `play` 事件，
 * 但都不是「听了这首歌」。因此**开始播放不记录**，只有越过阈值才记录。
 *
 * <h2>重复事件怎么处理</h2>
 *
 * 浏览器可能对同一首歌重复触发 play / timeupdate / 恢复播放。本模块用
 * {@link PlaybackTracker} 维护「这一次播放尝试」的状态：
 * - 同一首歌在一次播放尝试里**只会记录一次**（`recorded` 标记）；
 * - 暂停后继续播**不算新的一次**（累计时长继续累加，不重置）；
 * - 切歌 / 重新点同一首歌才会开新的播放尝试，生成新的 playId。
 *
 * <h2>幂等键</h2>
 *
 * 每次播放尝试生成一个 playId，网络重试复用同一个。后端据此去重，
 * 因此即使请求超时后重发，也不会把播放次数加两次。
 */

/**
 * 至少播放这么多秒才算有效播放。
 *
 * <p><b>为什么从 30 秒降到 5 秒</b>（2026-10-20 实测后调整）：30 秒的初衷是
 * 「排除误点」，但真实用户行为是——在推荐/曲库里点一首，听十几秒觉得不对就切下一首。
 * 这类用户听了一整晚，历史里仍然是空的，功能在体感上等于「坏了」。
 *
 * <p>5 秒仍能排除「点开就走 / 页面加载自动触发」这类无效播放（那是 0～2 秒），
 * 同时让「听了几句就切歌」的真实收听被记录下来。主流音乐 App 的常见区间是 5～30 秒，
 * 取偏小的值更符合「最近播放」这个功能的用途：它记的是「我碰过这首歌」，
 * 而不是「我听完了这首歌」。
 */
export const EFFECTIVE_PLAY_SECONDS = 5

/**
 * 至少播到歌曲的这个位置（秒）才算有效播放。
 *
 * <p>与 {@link EFFECTIVE_PLAY_SECONDS} 保持一致：两者只要有一个明显更大，
 * 就会让短促的真实收听被漏记。位置门槛的意义只是「别把从头开始的那一瞬间算进去」。
 */
export const EFFECTIVE_PLAY_MIN_POSITION_SECONDS = 5

/** 有效播放阈值占整首歌的比例：短歌按比例放宽。 */
export const EFFECTIVE_PLAY_RATIO = 0.5

/**
 * 计算本次播放的有效阈值（秒）。
 *
 * @param durationSeconds 歌曲总时长（秒）。未知/非正数时回退到固定阈值，
 *                        不能因为拿不到时长就永远不算有效播放。
 */
export function effectivePlayThreshold(durationSeconds: number): number {
  if (!Number.isFinite(durationSeconds) || durationSeconds <= 0) {
    return EFFECTIVE_PLAY_SECONDS
  }
  const half = durationSeconds * EFFECTIVE_PLAY_RATIO
  return Math.min(EFFECTIVE_PLAY_SECONDS, half)
}

/** 生成一次播放尝试的幂等键。 */
export function createPlayId(): string {
  const cryptoApi = globalThis.crypto
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  // 老浏览器兜底：不依赖 crypto，也不会因为缺少它而完全不记历史
  return `play-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export interface PlaybackState {
  /** 当前跟踪的歌曲 id（字符串形式，与 trackModel.id 一致）；未跟踪时为 null */
  songId: string | null
  /** 本次播放尝试的幂等键 */
  playId: string
  /** 本次播放尝试累计的「真正在播」秒数 */
  listenedSeconds: number
  /** 本次播放尝试是否已经上报过 */
  recorded: boolean
}

export function emptyState(): PlaybackState {
  return { songId: null, playId: '', listenedSeconds: 0, recorded: false }
}

/**
 * 播放跟踪器：喂给它播放器事件，它告诉你「现在该不该上报」。
 *
 * <p>不直接发请求 —— 上报由调用方注入（{@link PlaybackTracker#onEffectivePlay}），
 * 这样测试不需要网络，播放器也可以自由决定失败重试策略。
 */
export class PlaybackTracker {
  private state: PlaybackState = emptyState()

  /** 上一次 timeupdate 的播放位置（秒），用于算增量。 */
  private lastPosition = 0

  /**
   * @param onEffectivePlay 越过阈值时调用一次；返回的 Promise 失败**不会**让
   *                        跟踪器回滚状态（播放已经发生了，重试是调用方的事）
   */
  constructor(
    private readonly onEffectivePlay: (
      songId: string,
      playId: string,
      positionSeconds: number
    ) => void | Promise<void>
  ) {}

  /** 当前状态快照（测试与调试用，外部不要修改）。 */
  snapshot(): Readonly<PlaybackState> {
    return { ...this.state }
  }

  /**
   * 开始播放一首歌。
   *
   * @param songId     歌曲 id
   * @param restart    是否强制开一次新的播放尝试。**点同一首歌重新播放时传 true**，
   *                   否则会被当成「上次没播完，继续」
   */
  start(songId: string, restart = false): void {
    if (!songId) return
    if (!restart && this.state.songId === songId && !this.state.recorded) {
      // 同一首歌、上次还没记过 -> 继续累计，不重置（暂停后继续播就是这条路径）
      return
    }
    if (!restart && this.state.songId === songId && this.state.recorded) {
      // 同一首歌、已经记过了 -> 不重复记，也不开新的尝试。
      // 单曲循环（ended 后回到 0 秒）走的是这条：一次循环不应该算两次播放。
      return
    }
    this.state = {
      songId,
      playId: createPlayId(),
      listenedSeconds: 0,
      recorded: false,
    }
    this.lastPosition = 0
  }

  /**
   * 播放进度更新。播放器每次 timeupdate 调用。
   *
   * <p>只在**位置向前推进**时累加（拖动进度条向后不算听过）。
   * 单次增量上限 {@link MAX_TICK_SECONDS}：浏览器切到后台时 timeupdate 可能一次跳很多，
   * 那段时间用户并没有在听，不能全算成播放时长。
   *
   * @param positionSeconds 当前播放位置
   * @param durationSeconds 歌曲总时长（未知传 0）
   * @param playing         当前是否真的在播（暂停时传 false）
   */
  tick(positionSeconds: number, durationSeconds: number, playing = true): void {
    if (!this.state.songId || this.state.recorded) {
      this.lastPosition = positionSeconds
      return
    }
    const position = Number.isFinite(positionSeconds) ? positionSeconds : 0
    if (playing) {
      const delta = position - this.lastPosition
      // 只累加「向前且幅度合理」的增量
      if (delta > 0 && delta <= MAX_TICK_SECONDS) {
        this.state.listenedSeconds += delta
      }
    }
    this.lastPosition = position
    this.maybeRecord(position, durationSeconds)
  }

  /**
   * 播放结束。结束时如果累计时长已经够阈值，也算一次（短歌可能来不及在 timeupdate 里触发）。
   *
   * <p>**单曲循环的 ended 不算新的一次**：tracker 里已经记录过就不会再记。
   */
  ended(durationSeconds: number): void {
    if (!this.state.songId || this.state.recorded) return
    // 播到结尾时，位置就是总时长；用总时长判定位置阈值
    const position =
      Number.isFinite(durationSeconds) && durationSeconds > 0
        ? durationSeconds
        : this.lastPosition
    this.maybeRecord(position, durationSeconds)
  }

  /**
   * 播放失败（音频加载失败、网络错误、URL 无效）。
   *
   * <p>**失败不记历史**，并丢弃本次尝试 —— 下次播放同一首歌要重新开始计时，
   * 不能把失败前听的那几秒算进去。
   */
  fail(): void {
    this.state = emptyState()
    this.lastPosition = 0
  }

  /** 停止跟踪（组件卸载）。不再上报任何东西。 */
  dispose(): void {
    this.state = emptyState()
    this.lastPosition = 0
  }

  private maybeRecord(position: number, durationSeconds: number): void {
    if (!this.state.songId || this.state.recorded) return
    const threshold = effectivePlayThreshold(durationSeconds)
    if (this.state.listenedSeconds < threshold) return
    // 位置门槛同样受阈值约束：一首 15 秒的歌永远到不了 20 秒的位置，
    // 若写死下限就会「怎么听都记不上」。取两者较小值。
    const positionRequirement = Math.min(
      EFFECTIVE_PLAY_MIN_POSITION_SECONDS,
      threshold
    )
    if (position < positionRequirement) return

    this.state.recorded = true
    // 上报失败不回滚 recorded：这次播放确实发生了，不应该因为网络问题再记一次。
    // 重试由调用方用自己的策略处理（见 useAudioPlayer 的静默失败处理）。
    //
    // 必须显式 catch：`void promise` 只会丢掉返回值，rejection 仍会冒成
    // unhandledrejection —— 在浏览器里就是一条控制台报错。播放历史是后台行为，
    // 失败绝不该污染控制台，更不该让播放器以为出了事。
    try {
      const pending = this.onEffectivePlay(
        this.state.songId,
        this.state.playId,
        Math.round(position)
      )
      if (pending && typeof (pending as Promise<void>).catch === 'function') {
        void (pending as Promise<void>).catch(() => {})
      }
    } catch {
      // 同步抛出的异常同样吞掉：不能让一次上报失败打断 timeupdate 回调
    }
  }
}

/**
 * timeupdate 之间允许计入的最大秒数。
 *
 * <p>正常 timeupdate 间隔约 250ms；浏览器后台节流后可能一次跳几分钟。
 * 那些时间用户并没有在听，因此单次只按 2 秒计（略高于正常间隔，容忍调度抖动）。
 */
export const MAX_TICK_SECONDS = 2
