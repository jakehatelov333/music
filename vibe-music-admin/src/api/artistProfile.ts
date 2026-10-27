import { getToken } from "@/utils/auth";
import { http } from "@/utils/http";

/** 后端统一返回结构（与 api/system.ts 的 Result 一致，这里内联以免循环依赖） */
type ApiResult<T> = { code: number; message: string; data?: T | null };

/** 一个歌手搜索结果候选（与后端 ArtistCandidateVO 对齐） */
export type ArtistCandidate = {
  /** QQ 音乐歌手 mid，点选后用它取详情 */
  singerMid: string;
  /** QQ 侧歌手名，可能与库里写法不同（如 G.E.M.邓紫棋） */
  artistName: string;
  /** 各尺寸头像地址：{ "300": url, "500": url, "800": url } */
  avatarUrls?: Record<string, string> | null;
  /** 该候选是否已在本系统库里 */
  existingInLibrary?: boolean | null;
  /** 已存在时对应的本地歌手 id */
  existingArtistId?: number | null;
  /** 是否与搜索词完全一致（只有 true 才应默认选中） */
  exactMatch?: boolean | null;
  /** 匹配得分，仅用于排序，前端不依赖具体数值 */
  score?: number | null;
};

/** 某位歌手的完整资料（与后端 ArtistProfileVO 对齐） */
export type ArtistProfile = {
  singerMid: string;
  artistName: string;
  /** 0-男，1-女，2-组合/乐队；null 表示无法确定 */
  gender?: number | null;
  /** yyyy-MM-dd */
  birth?: string | null;
  area?: string | null;
  introduction?: string | null;
  foreignName?: string | null;
  avatarUrls?: Record<string, string> | null;
  /** 对方是否确实有头像（地址能拼出来不代表图存在） */
  hasAvatar?: boolean | null;
  /** wiki 里解析出的其余键值对：出生地、职业、出道日期、代表作品… */
  extraFields?: Record<string, string> | null;
  /** 本次实际有值的字段名，前端据此提示「填充了哪几项」 */
  filledFields?: string[] | null;
  existingInLibrary?: boolean | null;
  existingArtistId?: number | null;
};

/**
 * 按名字搜索 QQ 音乐的歌手候选。
 *
 * 名字带尾部括号（如 `IU (아이유)`）且原名搜不到时，后端会自动去掉括号重试一次。
 */
export const searchQqArtists = (keyword: string) => {
  const userData = getToken();
  return http.request<ApiResult<ArtistCandidate[]>>(
    "get",
    "/admin/artists/qq/search",
    {
      headers: { Authorization: userData.accessToken },
      params: { keyword }
    }
  );
};

/** 取某位候选歌手的完整资料（性别/生日/国籍/简介/头像）。 */
export const getQqArtistProfile = (singerMid: string) => {
  const userData = getToken();
  return http.request<ApiResult<ArtistProfile>>(
    "get",
    `/admin/artists/qq/${singerMid}/profile`,
    {
      headers: { Authorization: userData.accessToken }
    }
  );
};

/**
 * 取某位候选歌手的头像原图字节（后端同源代理，供裁剪用）。
 *
 * 腾讯 CDN 只对白名单 Origin 回 CORS 头，浏览器直接把 CDN 图画进 canvas
 * 会因画布污染导不出裁剪结果，必须经此代理转成同源 blob 再裁。
 */
export const getQqArtistAvatar = (singerMid: string, size?: number) => {
  const userData = getToken();
  return http.request<Blob>("get", `/admin/artists/qq/${singerMid}/avatar`, {
    headers: { Authorization: userData.accessToken },
    params: size ? { size } : undefined,
    responseType: "blob"
  });
};
