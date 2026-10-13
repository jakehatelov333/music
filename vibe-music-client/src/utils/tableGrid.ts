/**
 * 歌曲表格的栅格列定义。
 *
 * 表头与表体必须使用同一份定义，否则两边列宽会各自计算、无法对齐。
 *
 * 三个维度组合出表格形态：
 * - `showTrackNumber`：是否带「曲序」列（专辑详情用）
 * - `variant`：`library`（曲库 / 歌手 / 喜欢 / 公开歌单，末尾是「喜欢 / 时长 / 下载」）
 *   | `owned`（我的歌单，末尾是「时长 / 移出」）
 * - `showAlbum`：是否带「专辑」列。**专辑详情页要传 false** ——
 *   那一页所有歌都属于同一张专辑，再列一遍专辑名是纯噪音。
 *
 * ⚠️ 这里的类名必须写成字面量，不能靠模板字符串拼出来：
 * Tailwind 是在源码文本里扫描类名的，动态拼接出来的 `grid-cols-[...]` 不会被生成。
 */
export type SongTableVariant = 'library' | 'owned'

export const tableGridColumns = (
  showTrackNumber: boolean,
  variant: SongTableVariant = 'library',
  showAlbum = true
): string => {
  if (variant === 'owned') {
    return showAlbum
      ? 'grid-cols-[auto_4fr_3fr_3fr_2fr_1fr]'
      : 'grid-cols-[auto_4fr_3fr_2fr_1fr]'
  }
  if (showTrackNumber) {
    return showAlbum
      ? 'grid-cols-[2.5rem_auto_4fr_3fr_3fr_1fr_2fr_1fr]'
      : 'grid-cols-[2.5rem_auto_4fr_3fr_1fr_2fr_1fr]'
  }
  return showAlbum
    ? 'grid-cols-[auto_4fr_3fr_3fr_1fr_2fr_1fr]'
    : 'grid-cols-[auto_4fr_3fr_1fr_2fr_1fr]'
}
