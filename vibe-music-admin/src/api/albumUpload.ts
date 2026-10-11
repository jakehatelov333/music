import { http } from "@/utils/http";
import { getToken } from "@/utils/auth";

export type AlbumUploadSong = {
  stagingKey?: string;
  fileName: string;
  songName: string;
  artistName?: string | null;
  artistId?: number | null;
  trackNumber: number;
  discNumber: number;
  duration?: number | null;
  durationText?: string | null;
  fileSize?: number | null;
  coverUrl?: string | null;
  lyrics?: string | null;
};

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
export type AlbumUploadSession = { uploadId: string; expiresInHours?: number; files?: Array<Record<string, unknown>> };

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

export const commitAlbumUpload = (data: { uploadId: string; album: Record<string, unknown>; songs: AlbumUploadSong[] }) =>
  http.request<Result<{ albumId: number; songCount: number }>>("post", "/admin/album-upload/commit", {
    headers: { ...headers(), "Content-Type": "application/json" },
    timeout: 180000,
    data
  });

export const clearAlbumUpload = (uploadId: string) =>
  http.request<Result<null>>("delete", `/admin/album-upload/${uploadId}`, { headers: headers() });
