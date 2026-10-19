import { http } from "@/utils/http";
import type { Result } from "@/api/system";
import { getToken } from "@/utils/auth";

const authHeaders = () => {
  const userData = getToken();
  return {
    "Content-Type": "application/json",
    Authorization: userData.accessToken
  };
};

/** 歌词-抓取预览（不入库，供管理员确认或编辑） */
export const previewLyrics = (songId: number) => {
  return http.request<Result>("post", `/admin/song/lyrics/preview/${songId}`, {
    headers: authHeaders()
  });
};

/**
 * 歌词搜索结果（与后端 LyricsFetchVO 对应）
 *
 * `Result` 的 `data` 是联合类型，这里显式声明具体形状，
 * 避免调用方拿到 `string | number | object | any[]` 而无法类型收窄。
 */
export type LyricsSearchResult = {
  songId?: number | null;
  songName?: string | null;
  artistName?: string | null;
  album?: string | null;
  matched: boolean;
  source?: string | null;
  score?: number | null;
  lyric?: string | null;
  message?: string | null;
  hasExistingLyric?: boolean | null;
};

export type LyricsSearchResponse = {
  code: number;
  message: string;
  data?: LyricsSearchResult | null;
};

/**
 * 歌词-按元数据搜索（不入库）
 *
 * 给「歌曲上传」页用：此时歌曲还没入库、没有 songId，只有表单里的
 * 歌名 / 歌手 / 专辑 / 时长。后端走与 preview 相同的数据源与打分链路。
 */
export const searchLyricsByMeta = (data: {
  songName: string;
  artistName?: string | null;
  album?: string | null;
  durationSeconds?: number | null;
}) => {
  return http.request<LyricsSearchResponse>("post", "/admin/song/lyrics/search", {
    headers: authHeaders(),
    data
  });
};

/** 歌词-保存（手动编辑或预览确认后提交；覆盖已有歌词需传 force） */
export const saveLyrics = (songId: number, data: object) => {
  return http.request<Result>("put", `/admin/song/lyrics/${songId}`, {
    headers: authHeaders(),
    data
  });
};

/**
 * 歌词-提交批量补全任务
 * 传 songIds 处理所选；传 albumId 处理整张专辑；都不传则全量处理缺失歌词的歌曲
 */
export const submitLyricsBatch = (data: object) => {
  return http.request<Result>("post", "/admin/song/lyrics/batch", {
    headers: authHeaders(),
    data
  });
};

/** 歌词-批量标记歌曲为伴奏/无需歌词 */
export const markSongsInstrumental = (songIds: number[]) => {
  return http.request<Result>("post", "/admin/song/lyrics/instrumental", {
    headers: authHeaders(),
    data: songIds
  });
};

/** 歌词-查询批量任务进度 */
export const getLyricsTask = (taskId: string) => {
  return http.request<Result>("get", `/admin/song/lyrics/task/${taskId}`, {
    headers: authHeaders()
  });
};

/** 歌词-请求停止批量任务（当前正在进行的单首查询完成后停止） */
export const cancelLyricsTask = (taskId: string) => {
  return http.request<Result>("post", `/admin/song/lyrics/task/${taskId}/cancel`, {
    headers: authHeaders()
  });
};

/** 歌词-查询当前正在执行的批量任务（无则 data 为 null），用于刷新后恢复进度 */
export const getRunningLyricsTask = () => {
  return http.request<Result>("get", "/admin/song/lyrics/running", {
    headers: authHeaders()
  });
};
