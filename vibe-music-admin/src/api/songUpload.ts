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
  coverSource?: "EMBEDDED" | "UPLOADED" | null;
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

export type SongUploadPrepareResult = {
  uploadId: string;
  metadata: SongUploadMetadata;
  files: SongUploadFiles;
  matchedArtistId?: number | null;
  matchedArtistName?: string | null;
  artistMatched: boolean;
  warning?: string | null;
};

export type SongUploadCommitPayload = {
  uploadId: string;
  songName: string;
  artistId?: number | null;
  artistName?: string | null;
  album: string;
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
