import { http } from "@/utils/http";
import { getToken } from "@/utils/auth";

const authHeaders = () => {
  const userData = getToken();
  return {
    "Content-Type": "application/json",
    Authorization: userData.accessToken
  };
};

/** 封面候选（与后端 CoverCandidateVO 对应） */
export type CoverCandidate = {
  albumMid: string;
  albumName?: string | null;
  artistName?: string | null;
  trackName?: string | null;
  /** 版本标签：录音室 / Live / 翻唱 / 伴奏 / 混音 */
  versionLabel?: string | null;
  score?: number | null;
  durationSeconds?: number | null;
  /** 三档封面地址，key 为 "300" / "500" / "800" */
  coverUrls: Record<string, string>;
};

export type CoverSearchResponse = {
  code: number;
  message: string;
  data?: CoverCandidate[] | null;
};

/**
 * 搜索封面候选。
 *
 * 两种模式：
 * - 不传 keyword：自动模式，按歌名+歌手检索后过滤、按专辑去重，最多 4 个
 * - 传 keyword：手动模式，原样检索不做过滤，最多 8 个
 *
 * 手动模式是必需的逃生通道：音频标签解析错误时，自动模式会拿错误元数据去搜，
 * 结果必然也错，此时由管理员自己输入正确关键词。
 */
export const searchSongCovers = (data: {
  keyword?: string | null;
  songName?: string | null;
  artistName?: string | null;
  album?: string | null;
  durationSeconds?: number | null;
  limit?: number | null;
}) => {
  return http.request<CoverSearchResponse>(
    "post",
    "/admin/song-cover/search",
    {
      headers: authHeaders(),
      data
    }
  );
};

/**
 * 把选中的封面下载到当前上传会话的 staging 目录。
 *
 * 只传 albumMid + size，不传 URL：允许传 URL 等于给管理端开一个任意地址抓取入口。
 */
export const applyCoverFromAlbum = (
  uploadId: string,
  data: { albumMid: string; size?: number | null }
) => {
  return http.request<{
    code: number;
    message: string;
    data: import("./songUpload").SongUploadPrepareResult;
  }>("post", `/admin/song-upload/${uploadId}/cover-from-album`, {
    headers: authHeaders(),
    timeout: 60000,
    data
  });
};
