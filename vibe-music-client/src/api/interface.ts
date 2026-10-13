/** 结构化歌手引用：带真实 artistId，供跳转歌手详情页 */
export interface ArtistRef {
  artistId: number
  artistName: string
}

export interface PlaylistSong {
  songId: number
  /** 专辑 id，供专辑名做成可跳转链接；单曲可能为 null */
  albumId?: number | null
  songName: string
  /** 多歌手展示串，例如「银临 / Aki阿杰」；兼容字段 */
  artistName: string
  /** 结构化歌手列表，按展示顺序；无关系时为空数组 */
  artists?: ArtistRef[]
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
  ownerUserId?: number | null
  isPublic?: boolean
  editable?: boolean
  style?: string | null
  /** DRAFT / PUBLISHED，用户歌单恒为 PUBLISHED */
  status?: string | null
  /** true 表示封面是用户手动上传的，不会被默认封面覆盖 */
  coverIsCustom?: boolean | null
}

export interface Song {
  songId: number
  albumId?: number | null
  songName: string
  /** 多歌手展示串，例如「银临 / Aki阿杰」；兼容字段 */
  artistName: string
  /** 结构化歌手列表，按展示顺序；无关系时为空数组 */
  artists?: ArtistRef[]
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
  /** 4 表示已确认是伴奏或无需歌词 */
  lyricStatus?: number | null
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
