import { http } from "@/utils/http";
import { getToken } from "@/utils/auth";

export type SongUploadMetadata = {
  title?: string | null;
  artist?: string | null;
  album?: string | null;
  year?: number | null;
  duration?: number | null;
  durationText?: string | null;
  durationParsed: boolean;
  metadataAvailable: boolean;
  hasEmbeddedCover: boolean;
  coverUrl?: string | null;
  coverSource?: "EMBEDDED" | "UPLOADED" | "SEARCHED" | null;
  audioFormat?: string | null;
  sampleRate?: number | null;
  bitRate?: number | null;
  fileSize?: number | null;
};

export type SongUploadFiles = {
  audioUrl?: string | null;
  coverUrl?: string | null;
  lyricsUrl?: string | null;
  lyricsContent?: string | null;
};

export type ArtistParsingCandidate = {
  /** 唯一精确命中时的歌手 id；未匹配或歧义时为 null */
  artistId?: number | null;
  artistName: string;
  matched: boolean;
};

/**
 * 歌手字段的解析结果。strategy 决定管理端如何呈现：
 * EXACT_MATCH 单一歌手 / SPLIT_CONFIRMED 可自动拆分 /
 * SPLIT_CANDIDATE 需人工确认 / UNRESOLVED 无法识别。
 */
export type ArtistParsing = {
  raw?: string | null;
  strategy?:
    | "EXACT_MATCH"
    | "SPLIT_CONFIRMED"
    | "SPLIT_CANDIDATE"
    | "UNRESOLVED"
    | null;
  separator?: string | null;
  candidates: ArtistParsingCandidate[];
};

export type SongUploadPrepareResult = {
  uploadId: string;
  metadata: SongUploadMetadata;
  files: SongUploadFiles;
  matchedArtistId?: number | null;
  matchedArtistName?: string | null;
  artistMatched: boolean;
  /** 歌手字段解析结果，供上传预览做人工确认 */
  artistParsing?: ArtistParsing | null;
  warning?: string | null;
};

export type SongUploadCommitPayload = {
  uploadId: string;
  songName: string;
  artistId?: number | null;
  artistName?: string | null;
  /** 演唱歌手 id 列表，顺序即展示顺序（第一个为主艺人）。优先于 artistId */
  artistIds?: number[] | null;
  /**
   * 待新建的歌手名（库里没有、由本次提交创建）。
   * 与 artistIds 配合使用：选择器里既可能选已有歌手，也可能输入新名字。
   */
  pendingArtistNames?: string[] | null;
  /**
   * 完整歌手顺序，元素为 "id:142" 或 "name:新歌手"。
   * 混排时单靠两个数组无法还原顺序（新歌手可能排第一位即主艺人），故显式给出。
   */
  artistOrder?: string[] | null;
  album: string;
  /** 选中的已有专辑 id；为空时后端按 (artistId, album) 反查或新建 */
  albumId?: number | null;
  releaseDate: string;
  year?: number;
  style?: string | null;
  lyrics?: string | null;
};

export type SongUploadCommitResult = {
  songId: number;
  songName: string;
  artistId: number;
  artistName: string;
  artists?: { artistId: number; artistName: string }[] | null;
  artistCreated: boolean;
  album: string;
  duration?: string | null;
  coverUrl?: string | null;
  audioUrl?: string | null;
  releaseTime: string;
};

type SongUploadResult<T> = {
  code: number;
  message: string;
  data: T;
};

const authHeaders = () => {
  const userData = getToken();
  return { Authorization: userData.accessToken };
};

/** 歌曲上传-上传并解析音频 */
export const prepareSongUpload = (audio: File) => {
  const data = new FormData();
  data.append("audio", audio);
  return http.request<SongUploadResult<SongUploadPrepareResult>>(
    "post",
    "/admin/song-upload/prepare",
    {
      headers: {
        "Content-Type": "multipart/form-data",
        ...authHeaders()
      },
      timeout: 120000,
      data
    }
  );
};

/** 歌曲上传-补充或替换封面、歌词 */
export const updateSongUploadAssets = (
  uploadId: string,
  cover?: File,
  lyrics?: File
) => {
  const data = new FormData();
  if (cover) data.append("cover", cover);
  if (lyrics) data.append("lyrics", lyrics);
  return http.request<SongUploadResult<SongUploadPrepareResult>>(
    "post",
    `/admin/song-upload/${uploadId}/assets`,
    {
      headers: {
        "Content-Type": "multipart/form-data",
        ...authHeaders()
      },
      timeout: 120000,
      data
    }
  );
};

/** 歌曲上传-最终保存入库 */
export const commitSongUpload = (data: SongUploadCommitPayload) => {
  return http.request<SongUploadResult<SongUploadCommitResult>>(
    "post",
    "/admin/song-upload/commit",
    {
      headers: {
        "Content-Type": "application/json",
        ...authHeaders()
      },
      data
    }
  );
};

/** 歌曲上传-清空当前 staging 文件 */
export const clearSongUpload = (uploadId: string) => {
  return http.request<SongUploadResult<null>>(
    "delete",
    `/admin/song-upload/${uploadId}`,
    {
      headers: authHeaders()
    }
  );
};
