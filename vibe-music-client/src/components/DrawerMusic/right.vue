<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { Comment, SongDetail } from '@/api/interface'
import {
  addSongComment,
  deleteComment,
  getSongDetail,
  likeComment,
} from '@/api/system'
import { useAudioPlayer } from '@/hooks/useAudioPlayer'
import { UserStore } from '@/stores/modules/user'
import { formatNumber } from '@/utils'
import coverImg from '@/assets/cover.png'
import LyricsPanel from '@/components/Lyrics/LyricsPanel.vue'

type PlayerTab = 'lyrics' | 'comments'

const props = withDefaults(
  defineProps<{
    songDetail: SongDetail | null
    loading?: boolean
    activeTab: PlayerTab
  }>(),
  {
    loading: false,
  }
)

const emit = defineEmits<{
  'update:activeTab': [tab: PlayerTab]
}>()

const userStore = UserStore()
const { currentTime, isPlaying, seek, currentTrack } = useAudioPlayer()
const commentContent = ref('')
const maxLength = 180

const currentUsername = computed(() => userStore.userInfo?.username || '')
const comments = computed(() => {
  const list = props.songDetail?.comments || []
  return [...list].sort((left, right) => right.commentId - left.commentId)
})

const switchTab = (tab: PlayerTab) => {
  emit('update:activeTab', tab)
}

const refreshDetail = async () => {
  const songId = props.songDetail?.songId
  if (!songId) return

  const response = await getSongDetail(songId)
  if (response.code === 0 && response.data) {
    Object.assign(props.songDetail, response.data)
  }
}

const handleComment = async () => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }

  const content = commentContent.value.trim()
  if (!content) {
    ElMessage.warning('请输入评论内容')
    return
  }

  const songId = props.songDetail?.songId
  if (!songId) return

  try {
    const response = await addSongComment({ songId, content })
    if (response.code !== 0) {
      ElMessage.error(response.message || '评论发布失败')
      return
    }

    commentContent.value = ''
    await refreshDetail()
    ElMessage.success('评论发布成功')
  } catch {
    ElMessage.error('评论发布失败')
  }
}

const handleLike = async (comment: Comment) => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }

  try {
    const response = await likeComment(comment.commentId)
    if (response.code !== 0) {
      ElMessage.error(response.message || '点赞失败')
      return
    }

    comment.likeCount += 1
    ElMessage.success('点赞成功')
  } catch {
    ElMessage.error('点赞失败')
  }
}

const handleDelete = async (comment: Comment) => {
  try {
    const response = await deleteComment(comment.commentId)
    if (response.code !== 0) {
      ElMessage.error(response.message || '删除失败')
      return
    }

    await refreshDetail()
    ElMessage.success('删除成功')
  } catch {
    ElMessage.error('删除失败')
  }
}
</script>

<template>
  <div class="lyrics-card-content flex h-full min-h-0 flex-col">
    <header class="lyrics-header shrink-0">
      <div class="flex items-end justify-between gap-4">
        <div>
          <p class="text-[11px] font-medium uppercase tracking-[0.26em] text-muted-foreground">
            Lyrics & Comments
          </p>
          <h2 class="mt-2 text-xl font-semibold text-foreground">歌词与评论</h2>
        </div>
        <p class="hidden text-xs text-muted-foreground sm:block">点击歌词跳转进度</p>
      </div>

      <div class="lyrics-tabs inline-flex rounded-2xl bg-black/5 p-1 dark:bg-white/5">
        <button
          v-for="tab in [
            { label: '歌词', value: 'lyrics' as PlayerTab },
            { label: `评论 (${comments.length})`, value: 'comments' as PlayerTab },
          ]"
          :key="tab.value"
          type="button"
          class="tab-button"
          :class="{ 'tab-button--active': activeTab === tab.value }"
          @click="switchTab(tab.value)"
        >
          {{ tab.label }}
        </button>
      </div>
    </header>

    <div class="lyrics-body min-h-0 flex-1">
      <div v-show="activeTab === 'lyrics'" class="lyrics-view h-full min-h-0">
        <div
          v-if="loading && !songDetail"
          class="flex h-full flex-col items-center justify-center gap-4 text-muted-foreground"
        >
          <el-skeleton animated class="w-3/4">
            <template #template>
              <el-skeleton-item v-for="index in 5" :key="index" variant="text" class="mb-5 !h-6" />
            </template>
          </el-skeleton>
        </div>
        <LyricsPanel
          v-else
          :lyric="songDetail?.lyric"
          :current-time="currentTime"
          :is-playing="isPlaying"
          :track-id="currentTrack.id"
          @seek="seek"
        />
      </div>

      <div v-show="activeTab === 'comments'" class="h-full min-h-0 overflow-y-auto pr-1">
        <div v-if="songDetail" class="space-y-6">
          <div class="rounded-3xl border border-white/55 bg-white/35 p-4 backdrop-blur-xl dark:border-white/10 dark:bg-white/5">
            <el-input
              v-model="commentContent"
              type="textarea"
              :rows="4"
              :maxlength="maxLength"
              placeholder="说点什么吧"
              resize="none"
              show-word-limit
            />
            <div class="mt-3 flex justify-end">
              <button
                type="button"
                class="rounded-full bg-primary px-5 py-2 text-sm text-white transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-45"
                :disabled="!commentContent.trim()"
                @click="handleComment"
              >
                发布
              </button>
            </div>
          </div>

          <div v-if="comments.length" class="space-y-5">
            <article
              v-for="comment in comments"
              :key="comment.commentId"
              class="group flex gap-3"
            >
              <img
                :src="comment.userAvatar || coverImg"
                alt="avatar"
                class="mt-0.5 h-10 w-10 shrink-0 rounded-full object-cover"
              />
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-2">
                  <span class="truncate text-sm font-medium text-primary">
                    {{ comment.username }}
                  </span>
                </div>
                <p class="mt-1 break-words text-sm leading-6 text-foreground/85">
                  {{ comment.content }}
                </p>
                <div class="mt-2 flex items-center justify-between text-xs text-muted-foreground">
                  <span>{{ comment.createTime }}</span>
                  <div class="flex items-center gap-3">
                    <button
                      v-if="comment.username === currentUsername"
                      type="button"
                      class="opacity-0 transition hover:text-red-500 group-hover:opacity-100"
                      @click="handleDelete(comment)"
                    >
                      删除
                    </button>
                    <button
                      type="button"
                      class="flex items-center gap-1 transition hover:text-foreground"
                      @click="handleLike(comment)"
                    >
                      <span>{{ formatNumber(comment.likeCount) }}</span>
                      <icon-material-symbols:thumb-up-outline />
                    </button>
                  </div>
                </div>
              </div>
            </article>
          </div>

          <div v-else class="py-14 text-center text-sm text-muted-foreground">
            暂无评论，快来抢沙发吧~
          </div>
        </div>

        <div v-else class="flex h-full items-center justify-center">
          <el-empty description="暂无歌曲信息" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tab-button {
  min-width: clamp(72px, 8vw, 86px);
  border-radius: 12px;
  padding: clamp(6px, 0.9vh, 8px) clamp(12px, 1.4vw, 16px);
  color: hsl(var(--muted-foreground));
  font-size: 14px;
  font-weight: 500;
  transition:
    background-color 200ms ease,
    color 200ms ease,
    box-shadow 200ms ease,
    transform 200ms ease;
}

.lyrics-card-content {
  overflow: hidden;
}

.lyrics-header {
  padding: clamp(0.75rem, 1.8vh, 1.25rem) clamp(1rem, 1.8vw, 1.75rem) 0;
}

.lyrics-tabs {
  margin-top: clamp(0.625rem, 1.7vh, 1.25rem);
}

.lyrics-body {
  padding: clamp(0.5rem, 1.3vh, 0.75rem) clamp(0.75rem, 1.5vw, 1.5rem)
    clamp(0.75rem, 2vh, 1.5rem);
}

@media (max-height: 820px) {
  .lyrics-header {
    padding-top: 0.65rem;
  }

  .lyrics-tabs {
    margin-top: 0.5rem;
  }

  .lyrics-body {
    padding-top: 0.5rem;
    padding-bottom: 0.5rem;
  }
}

.tab-button:hover {
  color: hsl(var(--foreground));
}

.tab-button--active {
  background: rgba(255, 255, 255, 0.88);
  color: var(--cover-primary, hsl(var(--primary)));
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
}

.dark .tab-button--active {
  background: rgba(30, 41, 59, 0.9);
}

:deep(.el-input__wrapper),
:deep(.el-textarea__inner) {
  border-radius: 16px !important;
  background: rgba(255, 255, 255, 0.45) !important;
  box-shadow: none !important;
}

.dark :deep(.el-textarea__inner) {
  background: rgba(15, 23, 42, 0.35) !important;
}
</style>
