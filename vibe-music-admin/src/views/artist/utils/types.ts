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
  /** 库里已有的头像地址，仅用于修改模式回填预览；不随表单提交 */
  avatar?: string | null;
  /**
   * 头像文件：本地上传或裁剪的产物。非空时提交走 avatar 文件字段。
   */
  avatarFile?: File | null;
  /**
   * 头像预览地址，来源决定提交行为：
   * - `blob:` 开头 → 用户上传/裁剪得到的本地文件，提交时走 `avatar` 文件字段
   * - 其他 → 未裁剪前的远程图：QQ 候选图提交时走 `qqSingerMid` 由服务端下载，
   *   库中原有头像不随提交变化
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
