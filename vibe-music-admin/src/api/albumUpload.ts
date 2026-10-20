import { http } from "@/utils/http";
import { getToken } from "@/utils/auth";

/** 音频标签里歌手字段的解析结论（与后端 ArtistParsingVO 对齐） */
export type AlbumUploadArtistCandidate = {
  artistId?: number | null;
  artistName: string;
  matched: boolean;
};

export type AlbumUploadArtistParsing = {
  raw?: string | null;
  strategy?:
    | "EXACT_MATCH"
    | "SPLIT_CONFIRMED"
    | "SPLIT_CANDIDATE"
    | "UNRESOLVED"
    | string;
  separator?: string | null;
  candidates?: AlbumUploadArtistCandidate[];
};

export type AlbumUploadSong = {
  stagingKey?: string;
  fileName: string;
  songName: string;
  /**
   * 音频标签里的歌手原始串（如「银临、Tacke竹桑」）。
   * 这是提交时唯一可信的歌手来源，也可在预览里直接修改。
   */
  artistName?: string | null;
  artistId?: number | null;
  /** 高置信度解析出的歌手 id，顺序即展示顺序；其余情况为 null，需人工确认 */
  artistIds?: number[] | null;
  /** 该曲目歌手字段的解析结论（含未匹配的名字） */
  artistParsing?: AlbumUploadArtistParsing | null;
  trackNumber: number;
  discNumber: number;
  duration?: number | null;
  durationText?: string | null;
  fileSize?: number | null;
  coverUrl?: string | null;
  lyrics?: string | null;
};

export type AlbumUploadCommitResult = { albumId: number; songCount: number };

/** 提交返回 code=409 时的确认清单：确认后才会创建这些歌手 */
export type AlbumUploadPendingArtists = {
  missingArtists: string[];
  ambiguousArtists: string[];
  songCount: number;
};

/** 与后端 Result.CODE_CONFIRMATION_REQUIRED 对齐：需要人工确认，尚未写任何数据 */
export const ALBUM_UPLOAD_CONFIRMATION_REQUIRED = 409;

export type AlbumUploadPrepareResult = {
  uploadId: string;
  albumName?: string | null;
  artistName?: string | null;
  artistId?: number | null;
  releaseDate?: string | null;
  coverUrl?: string | null;
  warning?: string | null;
  songs: AlbumUploadSong[];
};

type Result<T> = { code: number; message: string; data: T };
const headers = () => ({ Authorization: getToken().accessToken });

export type AlbumUploadSessionFile = { fileName: string; size: number; contentType?: string; cover?: boolean };
export type AlbumUploadSession = { uploadId: string; expiresInHours?: number; expiresAt?: number; files?: Array<Record<string, unknown>> };

export const createAlbumUploadSession = (files: AlbumUploadSessionFile[]) =>
  http.request<Result<AlbumUploadSession>>("post", "/admin/album-upload/session", { headers: headers(), data: files });

export const signAlbumUploadFile = (uploadId: string, file: File, cover = false) =>
  http.request<Result<{ url: string; objectName: string }>>("post", `/admin/album-upload/${uploadId}/sign`, {
    headers: headers(),
    params: { fileName: file.name, size: file.size, contentType: file.type, cover }
  });

export const completeAlbumUploadFile = (uploadId: string, file: File, objectName: string) =>
  http.request<Result<{ uploaded: boolean }>>("post", `/admin/album-upload/${uploadId}/complete`, {
    headers: headers(), params: { fileName: file.name, size: file.size, objectName }
  });

export const parseAlbumUpload = (uploadId: string) =>
  http.request<Result<AlbumUploadPrepareResult>>("post", `/admin/album-upload/${uploadId}/parse`, { headers: headers(), timeout: 900000 });

export const getAlbumUploadSession = (uploadId: string) =>
  http.request<Result<AlbumUploadSession>>("get", `/admin/album-upload/${uploadId}`, { headers: headers() });

export const prepareAlbumUpload = (audioFiles: File[], cover?: File) => {
  const data = new FormData();
  audioFiles.forEach(file => data.append("audio", file));
  if (cover) data.append("cover", cover);
  return http.request<Result<AlbumUploadPrepareResult>>("post", "/admin/album-upload/prepare", {
    baseURL: "http://127.0.0.1:8080",
    headers: headers(),
    timeout: 900000,
    data
  });
};

/**
 * 提交专辑。`allowCreate` 为 false 时，只要出现库里不存在的歌手，后端会返回
 * `code = 409` 与待创建清单（不写任何数据）；前端弹确认框后再带 `allowCreate: true` 重提。
 */
export const commitAlbumUpload = (data: {
  uploadId: string;
  album: Record<string, unknown>;
  songs: AlbumUploadSong[];
  allowCreate?: boolean;
}) =>
  http.request<Result<AlbumUploadCommitResult | AlbumUploadPendingArtists>>(
    "post",
    "/admin/album-upload/commit",
    {
      headers: { ...headers(), "Content-Type": "application/json" },
      timeout: 180000,
      data
    }
  );

export const clearAlbumUpload = (uploadId: string) =>
  http.request<Result<null>>("delete", `/admin/album-upload/${uploadId}`, { headers: headers() });
