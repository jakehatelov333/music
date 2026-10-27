interface FormItemProps {
  /** 用于判断是`新增`还是`修改` */
  title: string;
  /** 主艺人 id（display_order 最小者）；与 artistIds 首位保持一致 */
  artistId: number;
  /** 多歌手展示串，例如「银临 / Aki阿杰」；兼容字段，只读展示 */
  artistName: string;
  /**
   * 演唱歌手 id 列表，顺序即展示顺序（第一个为主艺人）。
   * 为空时后端回退到旧的单值 artistId。
   */
  artistIds?: Array<number>;
  /** 结构化歌手列表，用于在编辑表单里把 id 还原成名字显示 */
  artists?: Array<{ artistId: number; artistName: string }>;
  songId: number;
  songName: string;
  album: string;
  confirmCreateAlbum?: boolean;
  style: Array<string>;
  releaseTime: Date;
  /** 是否已确认该曲目为伴奏或无需歌词 */
  instrumental?: boolean;
  /** 歌词处理状态，列表行回填使用 */
  lyricStatus?: number;
}
interface FormProps {
  formInline: FormItemProps;
}

export type { FormItemProps, FormProps };
