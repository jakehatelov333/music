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
  /**
   * 头像预览地址。两种来源：
   * - `blob:` 开头 → 用户本地上传的文件，提交时走 `avatar` 文件字段
   * - `https:` 开头 → 从 QQ 候选选中的图，提交时走 `qqSingerMid` 由服务端下载
   */
  avatarPreview?: string;
  /** 选中的 QQ 音乐歌手 mid；非空时由服务端下载头像，优先于 avatarFile */
  qqSingerMid?: string;
  /** 从 QQ 下载头像时使用的尺寸（300/500/800） */
  qqAvatarSize?: number;
}
interface FormProps {
  formInline: FormItemProps;
}

export type { FormItemProps, FormProps };
