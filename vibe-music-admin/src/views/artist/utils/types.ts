interface FormItemProps {
  /** 用于判断是`新增`还是`修改` */
  title: string;
  artistId?: number;
  artistName: string;
  gender: number;
  birth: Date;
  area: string;
  introduction: string;
  /** 歌手状态：ACTIVE-启用，DISABLED-停用（软删除）；仅修改模式可改 */
  status: string;
}
interface FormProps {
  formInline: FormItemProps;
}

export type { FormItemProps, FormProps };
