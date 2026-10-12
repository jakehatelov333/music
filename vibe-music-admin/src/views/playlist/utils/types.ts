interface FormItemProps {
  /** 用于判断是`新增`还是`修改` */
  formTitle: string;
  playlistId?: number;
  title: string;
  introduction: string;
  style: string;
  /** 新增歌单时一并提交的歌曲 id，修改歌单时不使用 */
  songIds?: number[];
}
interface FormProps {
  formInline: FormItemProps;
}

export type { FormItemProps, FormProps };
