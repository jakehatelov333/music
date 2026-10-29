<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { Comment, SongDetail } from '@/api/interface'
import { addSongComment, deleteComment, likeComment } from '@/api/system'
import { UserStore } from '@/stores/modules/user'

defineOptions({ name: 'PlayerComments' })

const props = defineProps<{
  songDetail: SongDetail | null
}>()

const emit = defineEmits<{
  refresh: []
}>()

const open = defineModel<boolean>('open', { default: false })
const userStore = UserStore()
const commentContent = ref('')
const submitting = ref(false)
const maxLength = 180

const currentUsername = computed(() => userStore.userInfo?.username || '')
const comments = computed(() =>
  [...(props.songDetail?.comments || [])].sort(
    (left, right) => right.commentId - left.commentId
  )
)
const countLabel = computed(() => {
  const count = comments.value.length
  if (count >= 10000) {
    return `${(count / 10000).toFixed(1).replace(/\.0$/, '')}万`
  }
  return String(count)
})

const close = () => {
  open.value = false
}

const handleComment = async () => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }

  const content = commentContent.value.trim()
  if (!content || submitting.value) {
    if (!content) ElMessage.warning('请输入评论内容')
    return
  }

  const songId = props.songDetail?.songId
  if (!songId) return

  submitting.value = true
  try {
    const response = await addSongComment({ songId, content })
    if (response.code !== 0) {
      ElMessage.error(response.message || '评论发布失败')
      return
    }

    commentContent.value = ''
    emit('refresh')
    ElMessage.success('评论发布成功')
  } catch {
    ElMessage.error('评论发布失败')
  } finally {
    submitting.value = false
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

    emit('refresh')
    ElMessage.success('删除成功')
  } catch {
    ElMessage.error('删除失败')
  }
}
</script>

<template>
  <div
    class="comments-layer"
    :class="{ 'is-open': open }"
    :aria-hidden="!open"
    :inert="!open"
  >
    <div class="comments-scrim" @click="close" />

    <aside
      class="comments-panel"
      role="dialog"
      aria-modal="true"
      aria-label="歌曲评论"
    >
      <div class="panel-head">
        <div>
          <p class="panel-kicker">Song comments</p>
          <h2 class="panel-title">
            评论 <span>{{ countLabel }}</span>
          </h2>
        </div>
        <button
          type="button"
          class="panel-close"
          aria-label="关闭评论"
          @click="close"
        >
          <icon-ri:close-line />
        </button>
      </div>

      <div class="comments-list">
        <article
          v-for="comment in comments"
          :key="comment.commentId"
          class="comment"
        >
          <img
            v-if="comment.userAvatar"
            :src="comment.userAvatar"
            :alt="`${comment.username} 头像`"
            class="avatar"
          />
          <div v-else class="avatar avatar--fallback">
            {{ comment.username.slice(0, 1) }}
          </div>

          <div class="comment-body">
            <div class="comment-meta">
              <span class="comment-name">{{ comment.username }}</span>
              <span class="comment-time">{{ comment.createTime }}</span>
            </div>
            <p class="comment-text">{{ comment.content }}</p>
            <div class="comment-foot">
              <button
                type="button"
                class="comment-action"
                aria-label="点赞评论"
                @click="handleLike(comment)"
              >
                <icon-ri:thumb-up-line />
                {{ comment.likeCount }}
              </button>
              <button
                v-if="comment.username === currentUsername"
                type="button"
                class="comment-action comment-action--danger"
                @click="handleDelete(comment)"
              >
                删除
              </button>
            </div>
          </div>
        </article>

        <div v-if="!comments.length" class="comments-empty">
          <icon-ri:chat-3-line />
          <p>暂无评论，快来抢沙发吧~</p>
        </div>
      </div>

      <div class="panel-compose">
        <textarea
          v-model="commentContent"
          class="panel-input"
          :maxlength="maxLength"
          :placeholder="
            userStore.isLoggedIn ? '说点什么吧...' : '登录后参与评论'
          "
          rows="2"
          @keydown.enter.exact.prevent="handleComment"
        />
        <div class="compose-foot">
          <span class="compose-count">
            {{ commentContent.length }}/{{ maxLength }}
          </span>
          <button
            type="button"
            class="panel-send"
            :disabled="!commentContent.trim() || submitting"
            aria-label="发送评论"
            @click="handleComment"
          >
            <icon-ri:send-plane-2-fill />
          </button>
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.comments-layer {
  position: absolute;
  z-index: 40;
  inset: 0;
  visibility: hidden;
  pointer-events: none;
  transition: visibility 0s linear 450ms;
}

.comments-layer.is-open {
  visibility: visible;
  pointer-events: auto;
  transition-delay: 0s;
}

.comments-scrim {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.34);
  opacity: 0;
  transition: opacity 300ms ease;
}

.comments-layer.is-open .comments-scrim {
  opacity: 1;
}

.comments-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  width: min(400px, 92vw);
  flex-direction: column;
  border-left: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(13, 15, 14, 0.9);
  box-shadow: -30px 0 80px rgba(0, 0, 0, 0.46);
  backdrop-filter: blur(32px) saturate(1.1);
  transform: translateX(102%);
  transition: transform 450ms cubic-bezier(0.22, 1, 0.36, 1);
}

.comments-layer.is-open .comments-panel {
  transform: none;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 28px 26px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.panel-kicker {
  margin: 0;
  color: var(--player-faint);
  font-size: 9px;
  letter-spacing: 0.3em;
  text-transform: uppercase;
}

.panel-title {
  margin: 10px 0 0;
  color: var(--player-ink);
  font-size: 20px;
  font-weight: 500;
  letter-spacing: 0.03em;
}

.panel-title span {
  margin-left: 6px;
  color: var(--player-faint);
  font-size: 11px;
  font-weight: 400;
}

.panel-close {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  color: rgba(246, 241, 233, 0.52);
  font-size: 19px;
  transition:
    background-color 180ms ease,
    color 180ms ease;
}

.panel-close:hover {
  background: rgba(255, 255, 255, 0.07);
  color: var(--player-ink);
}

.comments-list {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 22px;
  overflow-y: auto;
  padding: 24px 26px;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.18) transparent;
}

.comment {
  display: flex;
  gap: 12px;
}

.avatar {
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  border-radius: 50%;
  object-fit: cover;
}

.avatar--fallback {
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #6b5c4a, #2c3430);
  color: rgba(255, 255, 255, 0.82);
  font-size: 12px;
  letter-spacing: 0.05em;
}

.comment-body {
  min-width: 0;
  flex: 1;
}

.comment-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.comment-name {
  color: rgba(246, 241, 233, 0.72);
  font-size: 12px;
  letter-spacing: 0.03em;
}

.comment-time {
  color: rgba(246, 241, 233, 0.24);
  font-size: 9px;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.comment-text {
  margin: 7px 0 0;
  color: rgba(246, 241, 233, 0.62);
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.comment-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.comment-action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: rgba(246, 241, 233, 0.3);
  font-size: 10px;
  transition: color 180ms ease;
}

.comment-action:hover {
  color: rgba(246, 241, 233, 0.72);
}

.comment-action--danger:hover {
  color: #ff7a8f;
}

.comments-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--player-faint);
  font-size: 18px;
}

.comments-empty p {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.04em;
}

.panel-compose {
  padding: 16px 24px 22px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.panel-input {
  display: block;
  width: 100%;
  min-height: 58px;
  padding: 10px 13px;
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 12px;
  outline: none;
  background: rgba(255, 255, 255, 0.045);
  color: var(--player-ink);
  font: inherit;
  font-size: 12px;
  line-height: 1.6;
  resize: none;
}

.panel-input::placeholder {
  color: rgba(246, 241, 233, 0.26);
}

.panel-input:focus {
  border-color: rgba(91, 227, 141, 0.4);
}

.compose-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 9px;
}

.compose-count {
  color: var(--player-faint);
  font-size: 9px;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.06em;
}

.panel-send {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  background: var(--player-accent);
  color: #05140b;
  font-size: 17px;
  transition:
    transform 180ms ease,
    opacity 180ms ease;
}

.panel-send:hover:not(:disabled) {
  transform: scale(1.04);
}

.panel-send:disabled {
  cursor: not-allowed;
  opacity: 0.35;
}

@media (max-width: 600px) {
  .panel-head {
    padding: 22px 20px 16px;
  }

  .comments-list {
    padding: 20px;
  }

  .panel-compose {
    padding: 14px 18px 18px;
  }
}
</style>
