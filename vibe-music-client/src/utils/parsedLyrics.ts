export interface LyricLine {
  time: number | null
  text: string
}

export interface ParsedLyrics {
  lines: LyricLine[]
  hasTimestamps: boolean
}

const TIME_TAG_RE = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g
const META_TAG_RE = /^\[(ar|ti|al|by|re|ve|offset|length|kana):.*\]$/i

function parseTagTime(minutes: string, seconds: string, fraction = '0') {
  const milliseconds = Number(fraction.padEnd(3, '0').slice(0, 3))
  return Number(minutes) * 60 + Number(seconds) + milliseconds / 1000
}

/**
 * Parse the LRC text stored in tb_song.lyric.
 * Time values are returned in seconds so they can be compared directly with
 * HTMLAudioElement.currentTime.
 */
export function parseLrc(rawLyric?: string | null): ParsedLyrics {
  const source = rawLyric?.replace(/\r\n?/g, '\n').trim()
  if (!source) return { lines: [], hasTimestamps: false }

  const parsed: LyricLine[] = []

  for (const rawLine of source.split('\n')) {
    const line = rawLine.trim()
    if (!line || META_TAG_RE.test(line)) continue

    const tags = [...line.matchAll(TIME_TAG_RE)]
    if (tags.length === 0) {
      parsed.push({ time: null, text: line })
      continue
    }

    let tagIndex = 0
    while (tagIndex < tags.length) {
      const groupStart = tagIndex
      let groupEnd = tagIndex

      while (groupEnd + 1 < tags.length) {
        const currentEnd = tags[groupEnd].index + tags[groupEnd][0].length
        const nextStart = tags[groupEnd + 1].index
        if (line.slice(currentEnd, nextStart).trim() !== '') break
        groupEnd += 1
      }

      const textStart = tags[groupEnd].index + tags[groupEnd][0].length
      const nextTagStart =
        groupEnd + 1 < tags.length ? tags[groupEnd + 1].index : line.length
      const text = line.slice(textStart, nextTagStart).trim()

      if (text) {
        for (let index = groupStart; index <= groupEnd; index += 1) {
          const tag = tags[index]
          parsed.push({
            time: parseTagTime(tag[1], tag[2], tag[3]),
            text,
          })
        }
      }

      tagIndex = groupEnd + 1
    }
  }

  const lines = parsed
    .map((line, index) => ({ line, index }))
    .sort((left, right) => {
      if (left.line.time === null && right.line.time === null) {
        return left.index - right.index
      }
      if (left.line.time === null) return 1
      if (right.line.time === null) return -1
      return left.line.time - right.line.time
    })
    .map(({ line }) => line)
    .filter(
      (line, index, allLines) =>
        index === 0 ||
        line.time !== allLines[index - 1].time ||
        line.text !== allLines[index - 1].text
    )

  return {
    lines,
    hasTimestamps: lines.some((line) => line.time !== null),
  }
}
