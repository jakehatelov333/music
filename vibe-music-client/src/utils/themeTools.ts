import { ElMessage } from 'element-plus'
/**
 * hex颜色转rgb颜色
 * @param str 颜色值字符串
 * @returns 处理后的 rgb 数组；格式非法时返回 null（同时提示用户）
 *
 * 历史上这里返回 `number[] | void`，调用方靠 `!` 断言，类型检查其实没起作用
 * （`void` 赋给 `number[]` 必然报 TS2322）。改为「非法返回 null」后，
 * 调用方必须显式判空，类型系统才真正守住这条路径。
 */
export function hexToRgb(str: string): number[] | null {
  const reg: RegExp = /^#?[0-9A-Fa-f]{6}$/
  if (!reg.test(str)) {
    ElMessage.warning('输入错误的hex')
    return null
  }
  str = str.replace('#', '')
  const hexs: string[] = str.match(/../g) ?? []
  const rgb: number[] = hexs.map((h) => parseInt(h, 16))
  return rgb
}

/**
 * rgb颜色转Hex颜色
 * @param r 代表红色
 * @param g 代表绿色
 * @param b 代表蓝色
 * @returns 返回处理后的颜色值
 */
export function rgbToHex(r: number, g: number, b: number): string | void {
  const reg: RegExp = /^\d{1,3}$/
  if (!reg.test(String(r)) || !reg.test(String(g)) || !reg.test(String(b))) {
    ElMessage.warning('输入错误的rgb颜色值')
    return
  }
  const hexs: string[] = [r, g, b].map((n) => n.toString(16).padStart(2, '0'))
  return `#${hexs.join('')}`
}

/**
 * 加深颜色值
 * @param color 颜色值字符串
 * @param level 加深的程度，限0-1之间
 * @returns 返回处理后的颜色值
 */
export function getDarkColor(color: string, level: number): string | void {
  const reg: RegExp = /^#?[0-9A-Fa-f]{6}$/
  if (!reg.test(color)) {
    ElMessage.warning('输入错误的hex颜色值')
    return
  }
  const parsed = hexToRgb(color)
  if (!parsed) return
  let rgb: number[] = parsed
  rgb = rgb.map((c) => Math.floor(c * (1 - level)))
  return rgbToHex(rgb[0], rgb[1], rgb[2])
}

/**
 * 变浅颜色值
 * @param color 颜色值字符串
 * @param level 加深的程度，限0-1之间
 * @returns 返回处理后的颜色值
 */
export function getLightColor(color: string, level: number): string | void {
  const reg: RegExp = /^#?[0-9A-Fa-f]{6}$/
  if (!reg.test(color)) {
    ElMessage.warning('输入错误的hex颜色值')
    return
  }
  const parsed = hexToRgb(color)
  if (!parsed) return
  let rgb: number[] = parsed
  rgb = rgb.map((c) => Math.floor((255 - c) * level + c))
  return rgbToHex(rgb[0], rgb[1], rgb[2])
}

/**
 * Rgba 转换 hex
 * @param rgba 颜色的字符串
 * @returns 返回处理后的颜色值
 */
export function rgbaToHex(rgba: string): string {
  const regex = /^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)$/
  const match = rgba.match(regex)
  if (!match) {
    throw new Error('Invalid RGBA string')
  }
  const r = parseInt(match[1], 10)
  const g = parseInt(match[2], 10)
  const b = parseInt(match[3], 10)
  const hex = ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1)
  return `#${hex}`
}
