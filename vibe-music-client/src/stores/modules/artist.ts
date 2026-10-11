import { defineStore } from 'pinia'
import type { Album, Song } from '@/api/interface'

interface ArtistInfo {
  artistId: number
  artistName: string
  avatar: string
  birth: string
  area: string
  introduction: string
  songs: Song[]
  albums: Album[]
}

export const useArtistStore = defineStore('ArtistStore', {
  state: () => ({
    artistInfo: null as ArtistInfo | null,
  }),
  actions: {
    setArtistInfo(info: ArtistInfo) {
      this.artistInfo = info
    },
  },
})
