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

/** 歌词-查询批量任务进度 */
export const getLyricsTask = (taskId: string) => {
  return http.request<Result>("get", `/admin/song/lyrics/task/${taskId}`, {
    headers: authHeaders()
  });
};
