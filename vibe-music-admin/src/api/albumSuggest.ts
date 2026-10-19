import { http } from "@/utils/http";
import { getToken } from "@/utils/auth";

const authHeaders = () => {
  const userData = getToken();
  return {
    Authorization: userData.accessToken
  };
};

/** 专辑联想建议项（与后端 AlbumSuggestVO 对应） */
export type AlbumSuggestItem = {
  albumId: number;
  albumName: string;
  artistId: number;
  artistName?: string | null;
  /** ACTIVE / DISABLED；停用的歌手名下专辑仍真实存在，前端标注而不隐藏 */
  artistStatus?: "ACTIVE" | "DISABLED" | null;
  coverUrl?: string | null;
  releaseDate?: string | null;
  songCount?: number | null;
};

export type AlbumSuggestResponse = {
  code: number;
  message: string;
  data?: AlbumSuggestItem[] | null;
};

/**
 * 专辑联想建议（管理端）。
 *
 * 与用户端 `/album/search` 的区别：不过滤停用歌手与草稿专辑，且传了 artistId 时
 * 该歌手的专辑排最前 —— 上传歌曲时最可能的意图就是「给这位歌手已有的专辑加歌」。
 *
 * 关键词为空时后端直接返回空列表，不会查全库。
 */
export const suggestAlbums = (
  keyword: string,
  artistId?: number | null,
  limit?: number
) => {
  return http.request<AlbumSuggestResponse>("get", "/admin/albums/suggest", {
    params: {
      keyword,
      ...(artistId ? { artistId } : {}),
      ...(limit ? { limit } : {})
    },
    headers: authHeaders()
  });
};
