export interface CoverColors {
  primary: string
  secondary: string
}

export const DEFAULT_COVER_COLORS: CoverColors = {
  primary: '#2a68fa',
  secondary: '#8b5cf6',
}

const colorCache = new Map<string, CoverColors>()

function toRgbString(red: number, green: number, blue: number) {
  return `rgb(${Math.round(red)}, ${Math.round(green)}, ${Math.round(blue)})`
}

function colorDistance(a: [number, number, number], b: [number, number, number]) {
  return Math.sqrt(
    (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2
  )
}

function getBucketScore(
  red: number,
  green: number,
  blue: number,
  count: number
) {
  const max = Math.max(red, green, blue)
  const min = Math.min(red, green, blue)
  const saturation = max === 0 ? 0 : (max - min) / max
  const brightness = max / 255
  const brightnessScore = 1 - Math.abs(brightness - 0.58)
  return count * (0.35 + saturation * 1.8) * (0.55 + brightnessScore)
}

async function sampleImage(url: string): Promise<CoverColors> {
  return new Promise((resolve) => {
    const image = new Image()
    image.crossOrigin = 'anonymous'
    image.referrerPolicy = 'no-referrer'

    image.onload = () => {
      try {
        const canvas = document.createElement('canvas')
        const size = 36
        canvas.width = size
        canvas.height = size

        const context = canvas.getContext('2d', { willReadFrequently: true })
        if (!context) {
          resolve(DEFAULT_COVER_COLORS)
          return
        }

        context.drawImage(image, 0, 0, size, size)
        const pixels = context.getImageData(0, 0, size, size).data
        const buckets = new Map<
          string,
          { color: [number, number, number]; count: number }
        >()

        for (let index = 0; index < pixels.length; index += 16) {
          const alpha = pixels[index + 3]
          if (alpha < 180) continue

          const red = pixels[index]
          const green = pixels[index + 1]
          const blue = pixels[index + 2]
          const max = Math.max(red, green, blue)
          const min = Math.min(red, green, blue)

          if (max > 246 && min > 238) continue
          if (max < 18) continue
          if (max - min < 12 && max > 205) continue

          const key = [
            Math.round(red / 32),
            Math.round(green / 32),
            Math.round(blue / 32),
          ].join('-')
          const bucket = buckets.get(key)

          if (bucket) {
            bucket.color[0] += red
            bucket.color[1] += green
            bucket.color[2] += blue
            bucket.count += 1
          } else {
            buckets.set(key, {
              color: [red, green, blue],
              count: 1,
            })
          }
        }

        const candidates = [...buckets.values()]
          .map((bucket) => ({
            color: bucket.color.map((channel) =>
              channel / bucket.count
            ) as [number, number, number],
            count: bucket.count,
          }))
          .sort(
            (left, right) =>
              getBucketScore(...right.color, right.count) -
              getBucketScore(...left.color, left.count)
          )

        if (candidates.length === 0) {
          resolve(DEFAULT_COVER_COLORS)
          return
        }

        const primary = candidates[0]
        const secondary =
          candidates.find(
            (candidate) =>
              colorDistance(candidate.color, primary.color) > 92
          ) ?? candidates[1] ?? primary

        resolve({
          primary: toRgbString(...primary.color),
          secondary: toRgbString(...secondary.color),
        })
      } catch {
        resolve(DEFAULT_COVER_COLORS)
      }
    }

    image.onerror = () => resolve(DEFAULT_COVER_COLORS)
    image.src = url
  })
}

export async function extractCoverColors(
  coverUrl?: string | null
): Promise<CoverColors> {
  if (!coverUrl) return DEFAULT_COVER_COLORS
  const cached = colorCache.get(coverUrl)
  if (cached) return cached

  const colors = await sampleImage(coverUrl)
  colorCache.set(coverUrl, colors)
  return colors
}
