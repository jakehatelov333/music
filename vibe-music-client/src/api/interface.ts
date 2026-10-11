export interface PlaylistSong {
  songId: number
  songName: string
  artistName: string
  album: string
  duration: string
  coverUrl: string | null
  audioUrl: string
  likeStatus: number
  releaseTime: string | null
}

export interface Comment {
  commentId: number
  username: string
  userAvatar: string | null
  content: string
  createTime: string
  likeCount: number
}

export type PlaylistComment = Comment

export interface PlaylistDetail {
  playlistId: number
  title: string
  coverUrl: string | null
  introduction: string
  songs: PlaylistSong[]
  likeStatus: number
  comments: PlaylistComment[]
  isCollected: boolean
}

export interface Song {
  songId: number
  albumId?: number | null
  songName: string
  artistName: string
  album: string
  discNumber?: number | null
  trackNumber?: number | null
  duration: string
  coverUrl: string | null
  audioUrl: string
  likeStatus: number
  releaseTime: string | null
}

export interface SongDetail extends Song {
  lyric: string | null
  comments: Comment[]
}

export interface ArtistSearchResult {
  artistId: number
  artistName: string
  avatar: string | null
}

export interface PlaylistSearchResult {
  playlistId: number
  title: string
  coverUrl: string | null
}

export interface Album {
  albumId: number
  artistId: number
  albumName: string
  artistName: string
  coverUrl: string | null
  releaseDate: string | null
  description: string | null
  songCount: number
}

export interface AlbumDetail extends Album {
  songs: Song[]
}

export interface GlobalSearchResult {
  songs: Song[]
  artists: ArtistSearchResult[]
  albums: Album[]
  playlists: PlaylistSearchResult[]
}
