import { defineStore } from 'pinia'
import {
  getFavoritePlaylists,
  collectPlaylist,
  cancelCollectPlaylist,
} from '@/api/system'
import type { ResultTable } from '@/api/system'
import { ElMessage } from 'element-plus'
import coverImg from '@/assets/cover.png'
import { UserStore } from '@/stores/modules/user'

function currentAccount() {
  const user = UserStore()
  return user.isLoggedIn && user.userInfo?.userId && user.userInfo?.token
    ? `${user.userInfo.userId}:${user.userInfo.token}`
    : ''
}

interface FavoritePlaylist {
  id: number
  name: string
  coverImgUrl: string
}

export const useFavoriteStore = defineStore('favorite', {
  state: () => ({
    favoritePlaylists: [] as FavoritePlaylist[],
    loading: false,
    sessionVersion: 0,
    listRequestVersion: 0,
    mutationVersions: {} as Record<number, number>,
  }),

  actions: {
    // 获取收藏的歌单列表
    async getFavoritePlaylists() {
      const account = currentAccount()
      if (!account) {
        this.clearFavoritePlaylists()
        return
      }
      const session = this.sessionVersion
      const request = ++this.listRequestVersion
      const isCurrent = () =>
        currentAccount() === account &&
        this.sessionVersion === session &&
        this.listRequestVersion === request
      try {
        this.loading = true
        const res = await getFavoritePlaylists({
          pageNum: 1,
          pageSize: 50,
          title: '',
          style: '',
        })
        if (!isCurrent()) return
        if (res.code === 0 && res.data) {
          const data = res.data as ResultTable['data']
          if (data?.items) {
            this.favoritePlaylists = data.items.map((item) => ({
              id: item.playlistId,
              name: item.title,
              coverImgUrl: item.coverUrl ?? coverImg,
            }))
          }
        }
      } catch {
        if (isCurrent()) ElMessage.error('获取收藏歌单失败')
      } finally {
        if (isCurrent()) this.loading = false
      }
    },

    // 收藏歌单
    async collectPlaylist(playlistId: number) {
      const account = currentAccount()
      if (!account) return false
      const session = this.sessionVersion
      const mutation = (this.mutationVersions[playlistId] || 0) + 1
      this.mutationVersions[playlistId] = mutation
      this.listRequestVersion++
      this.loading = false
      const isCurrent = () =>
        currentAccount() === account &&
        this.sessionVersion === session &&
        this.mutationVersions[playlistId] === mutation
      try {
        const res = await collectPlaylist(playlistId)
        if (!isCurrent()) return false
        if (res.code === 0) {
          ElMessage.success('收藏成功')
          // 重新获取收藏列表
          await this.getFavoritePlaylists()
          return isCurrent()
        }
        return false
      } catch {
        if (isCurrent()) ElMessage.error('收藏失败')
        return false
      }
    },

    // 取消收藏歌单
    async cancelCollectPlaylist(playlistId: number) {
      const account = currentAccount()
      if (!account) return false
      const session = this.sessionVersion
      const mutation = (this.mutationVersions[playlistId] || 0) + 1
      this.mutationVersions[playlistId] = mutation
      this.listRequestVersion++
      this.loading = false
      const isCurrent = () =>
        currentAccount() === account &&
        this.sessionVersion === session &&
        this.mutationVersions[playlistId] === mutation
      try {
        const res = await cancelCollectPlaylist(playlistId)
        if (!isCurrent()) return false
        if (res.code === 0) {
          ElMessage.success('取消收藏成功')
          this.listRequestVersion++
          this.loading = false
          // 从列表中移除
          this.favoritePlaylists = this.favoritePlaylists.filter(
            (item) => item.id !== playlistId
          )
          return true
        }
        return false
      } catch {
        if (isCurrent()) ElMessage.error('取消收藏失败')
        return false
      }
    },

    // 清空收藏列表
    clearFavoritePlaylists() {
      this.sessionVersion++
      this.listRequestVersion++
      this.mutationVersions = {}
      this.loading = false
      this.favoritePlaylists = []
    },
  },
})
