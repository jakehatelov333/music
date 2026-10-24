const LOCAL_MEDIA_ORIGIN = /^https?:\/\/(?:localhost|127\.0\.0\.1):9005(?=\/)/i

/**
 * 开发环境统一走 Vite 的 /media 代理，避免手机或公网访问时把
 * localhost:9005 解析成设备自身。生产环境保留后端返回的原始地址。
 */
export function normalizeMediaUrls<T>(value: T): T {
  if (!import.meta.env.DEV) return value
  if (typeof value === 'string') {
    return value.replace(LOCAL_MEDIA_ORIGIN, '/media') as T
  }
  if (Array.isArray(value)) {
    return value.map((item) => normalizeMediaUrls(item)) as T
  }
  if (value && typeof value === 'object') {
    const prototype = Object.getPrototypeOf(value)
    if (prototype !== Object.prototype && prototype !== null) return value
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, normalizeMediaUrls(item)])
    ) as T
  }
  return value
}
