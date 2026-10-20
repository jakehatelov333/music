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
  /**
   * 新增时选中的头像文件。只在新增模式使用 —— 修改模式的头像走独立的
   * 「上传头像」入口（那里有裁剪），不在这里混着改。
   */
  avatarFile?: File | null;
  /** 新增时头像的本地预览地址（objectURL），不提交给后端 */
  avatarPreview?: string;
}
interface FormProps {
  formInline: FormItemProps;
}

export type { FormItemProps, FormProps };
