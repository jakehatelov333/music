/**
 * 歌曲表格的栅格列定义。
 *
 * 表头与表体必须使用同一份定义，否则两边列宽会各自计算、无法对齐。
 * 列顺序：曲序 / 封面 / 标题 / 歌手 / 专辑 / 喜欢 / 时长 / 下载
 * （`showTrackNumber` 为 false 时不含「曲序」列）
 */
export const tableGridColumns = (showTrackNumber: boolean): string =>
  showTrackNumber
    ? 'grid-cols-[2.5rem_auto_4fr_3fr_3fr_1fr_2fr_1fr]'
    : 'grid-cols-[auto_4fr_3fr_3fr_1fr_2fr_1fr]'
