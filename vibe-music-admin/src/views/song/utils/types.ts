interface FormItemProps {
  /** 用于判断是`新增`还是`修改` */
  title: string;
  artistId: number;
  artistName: string;
  songId: number;
  songName: string;
  album: string;
  confirmCreateAlbum?: boolean;
  style: Array<string>;
  releaseTime: Date;
  /** 歌词（LRC 文本），管理端可手动编辑 */
  lyric?: string;
  /** 是否已确认该曲目为伴奏或无需歌词 */
  instrumental?: boolean;
  /** 歌词处理状态，列表行回填使用 */
  lyricStatus?: number;
}
interface FormProps {
  formInline: FormItemProps;
}

export type { FormItemProps, FormProps };
