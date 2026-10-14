<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UserStore } from '@/stores/modules/user'
import AuthTabs from '@/components/Auth/AuthTabs.vue'
import { getAllArtists } from '@/api/system'
import {
  getAiProfile,
  getMemoryOverview,
  createMemoryPreference,
  updateMemoryPreference,
  deleteMemoryPreference,
  setPersonalization,
  clearAutoMemory,
  getMemoryStyles,
  parseTaste,
} from '@/api/memory'
import type {
  AiMusicProfile,
  MemoryOverview,
  MemoryPreference,
  MemoryPreferenceCategory,
} from '@/api/memory'

defineOptions({ name: 'AiMemory' })

const userStore = UserStore()

const loading = ref(false)
const errorText = ref('')
const authVisible = ref(false)

const profile = ref<AiMusicProfile | null>(null)
const overview = ref<MemoryOverview | null>(null)
const styleOptions = ref<string[]>([])
const artistOptions = ref<{ artistId: number; artistName: string }[]>([])

const personalizationEnabled = ref(true)
const savingPersonalization = ref(false)
const clearing = ref(false)
const saving = ref(false)

const selectedArtistId = ref<number | null>(null)
const selectedStyle = ref<string>('')
const noteDraft = ref('')
const excludeArtistId = ref<number | null>(null)
const excludeStyle = ref<string>('')
const excludeNoteDraft = ref('')

const noteEdit = ref<{
  visible: boolean
  id: number | null
  category: MemoryPreferenceCategory
  value: string
}>({ visible: false, id: null, category: 'PREFERENCE_NOTE', value: '' })

let controller: AbortController | null = null

/* ---------------- 模块一：系统推断的音乐画像（只读） ---------------- */

const topArtists = computed(() => parseTaste(profile.value?.topArtists))
const topStyles = computed(() => parseTaste(profile.value?.topStyles))
const profileSummary = computed(() => (profile.value?.prefSummary || '').trim())
const hasProfile = computed(
  () => topArtists.value.length > 0 || topStyles.value.length > 0 || !!profileSummary.value
)

const memorySources = computed(() => [
  { label: '收藏歌曲', count: profile.value?.favSongCount ?? 0 },
  { label: '创建歌单', count: profile.value?.ownPlaylistCount ?? 0 },
  { label: '收藏歌单', count: profile.value?.favPlaylistCount ?? 0 },
])

const formatTime = (value: string | null | undefined) => {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

/* ---------------- 模块二：手动偏好 ---------------- */

const preferences = computed<MemoryPreference[]>(() => overview.value?.preferences ?? [])

const prefsOf = (category: MemoryPreferenceCategory) =>
  preferences.value.filter((item) => item.category === category)

const favoriteArtists = computed(() => prefsOf('FAVORITE_ARTIST'))
const favoriteStyles = computed(() => prefsOf('FAVORITE_STYLE'))
const preferenceNotes = computed(() => prefsOf('PREFERENCE_NOTE'))
const excludeArtists = computed(() => prefsOf('EXCLUDE_ARTIST'))
const excludeStyles = computed(() => prefsOf('EXCLUDE_STYLE'))
const excludeNotes = computed(() => prefsOf('EXCLUDE_NOTE'))

const hasManualPreferences = computed(() => preferences.value.length > 0)

/* ---------------- 模块三：最终生效偏好 ---------------- */

const conflicts = computed(() => overview.value?.conflicts ?? [])

const effectiveGroups = computed(() => {
  const eff = overview.value?.effective
  if (!eff) return []
  return [
    { key: 'likeArtists', label: '喜欢的歌手', mode: '优先推荐', items: eff.likeArtists },
    { key: 'likeStyles', label: '喜欢的风格', mode: '优先推荐', items: eff.likeStyles },
    { key: 'preferenceNotes', label: '偏好要求', mode: '优先推荐', items: eff.preferenceNotes },
    { key: 'excludeArtists', label: '排除的歌手', mode: '优先排除', items: eff.excludeArtists },
    { key: 'excludeStyles', label: '排除的风格', mode: '优先排除', items: eff.excludeStyles },
    { key: 'excludeNotes', label: '排除条件', mode: '优先排除', items: eff.excludeNotes },
  ].filter((group) => group.items.length > 0)
})

/* ---------------- 数据加载 ---------------- */

const describeError = (error: any, fallback: string) =>
  error?.response?.data?.message || error?.message || fallback

const loadProfile = async (signal: AbortSignal) => {
  try {
    const res = await getAiProfile(signal)
    if (signal.aborted) return
    profile.value = res ?? null
  } catch (error: any) {
    if (signal.aborted) return
    profile.value = null
  }
}

const loadOverview = async (signal: AbortSignal) => {
  try {
    const res = await getMemoryOverview(signal)
    if (signal.aborted) return
    if (res.code === 0 && res.data) {
      overview.value = res.data
      personalizationEnabled.value = res.data.personalizationEnabled
    } else {
      errorText.value = res.message || '偏好数据加载失败'
    }
  } catch (error: any) {
    if (signal.aborted) return
    errorText.value = describeError(error, '偏好数据加载失败，请稍后重试')
  }
}

const loadStyles = async (signal: AbortSignal) => {
  try {
    const res = await getMemoryStyles(signal)
    if (signal.aborted) return
    if (res.code === 0 && Array.isArray(res.data)) styleOptions.value = res.data
  } catch {
    /* 风格列表失败不阻塞页面，选择框会退化为空 */
  }
}

const loadArtists = async () => {
  try {
    const res = await getAllArtists({
      pageNum: 1,
      pageSize: 500,
      name: null,
      gender: null,
      area: null,
    })
    if (res.code === 0 && res.data?.items) {
      artistOptions.value = res.data.items
        .filter((item: any) => item?.artistId && item?.artistName)
        .map((item: any) => ({ artistId: item.artistId, artistName: item.artistName }))
    }
  } catch {
    /* 歌手列表失败不阻塞页面 */
  }
}

const loadAll = async () => {
  if (!userStore.isLoggedIn) return
  controller?.abort()
  controller = new AbortController()
  const signal = controller.signal
  loading.value = true
  errorText.value = ''
  await Promise.allSettled([
    loadProfile(signal),
    loadOverview(signal),
    loadStyles(signal),
    loadArtists(),
  ])
  if (!signal.aborted) loading.value = false
}

const reload = () => loadAll()

/* ---------------- 模块二：增改删 ---------------- */

const findDuplicate = (category: MemoryPreferenceCategory, value: string) =>
  preferences.value.some(
    (item) => item.category === category && item.targetValue === value
  )

const addPreference = async (
  category: MemoryPreferenceCategory,
  body: { targetId?: number | null; targetValue?: string | null },
  successText: string
): Promise<boolean> => {
  saving.value = true
  try {
    const res = await createMemoryPreference({ category, ...body })
    if (res.code === 0) {
      ElMessage.success(successText)
      await loadOverview(new AbortController().signal)
      return true
    }
    ElMessage.error(res.message || '添加失败')
    return false
  } catch (error: any) {
    ElMessage.error(describeError(error, '添加失败'))
    return false
  } finally {
    saving.value = false
  }
}

const addFavoriteArtist = async () => {
  const id = selectedArtistId.value
  if (!id) {
    ElMessage.warning('请先选择喜欢的歌手')
    return
  }
  const name = artistOptions.value.find((a) => a.artistId === id)?.artistName ?? ''
  if (name && findDuplicate('FAVORITE_ARTIST', name)) {
    ElMessage.warning('该歌手已在喜欢列表中')
    return
  }
  if (await addPreference('FAVORITE_ARTIST', { targetId: id, targetValue: null }, '已添加喜欢的歌手')) {
    selectedArtistId.value = null
  }
}

const addFavoriteStyle = async () => {
  const value = selectedStyle.value.trim()
  if (!value) {
    ElMessage.warning('请先选择喜欢的音乐风格')
    return
  }
  if (findDuplicate('FAVORITE_STYLE', value)) {
    ElMessage.warning('该风格已在喜欢列表中')
    return
  }
  if (await addPreference('FAVORITE_STYLE', { targetId: null, targetValue: value }, '已添加喜欢的风格')) {
    selectedStyle.value = ''
  }
}

const addPreferenceNote = async () => {
  const value = noteDraft.value.trim()
  if (!value) {
    ElMessage.warning('请输入偏好要求')
    return
  }
  if (value.length > 200) {
    ElMessage.warning('偏好要求不能超过 200 字')
    return
  }
  if (findDuplicate('PREFERENCE_NOTE', value)) {
    ElMessage.warning('该偏好要求已存在')
    return
  }
  if (await addPreference('PREFERENCE_NOTE', { targetId: null, targetValue: value }, '已添加偏好要求')) {
    noteDraft.value = ''
  }
}

const addExcludeArtist = async () => {
  const id = excludeArtistId.value
  if (!id) {
    ElMessage.warning('请先选择要排除的歌手')
    return
  }
  const name = artistOptions.value.find((a) => a.artistId === id)?.artistName ?? ''
  if (name && findDuplicate('EXCLUDE_ARTIST', name)) {
    ElMessage.warning('该歌手已在排除列表中')
    return
  }
  if (await addPreference('EXCLUDE_ARTIST', { targetId: id, targetValue: null }, '已添加排除歌手')) {
    excludeArtistId.value = null
  }
}

const addExcludeStyle = async () => {
  const value = excludeStyle.value.trim()
  if (!value) {
    ElMessage.warning('请先选择要排除的音乐风格')
    return
  }
  if (findDuplicate('EXCLUDE_STYLE', value)) {
    ElMessage.warning('该风格已在排除列表中')
    return
  }
  if (await addPreference('EXCLUDE_STYLE', { targetId: null, targetValue: value }, '已添加排除风格')) {
    excludeStyle.value = ''
  }
}

const addExcludeNote = async () => {
  const value = excludeNoteDraft.value.trim()
  if (!value) {
    ElMessage.warning('请输入排除条件')
    return
  }
  if (value.length > 200) {
    ElMessage.warning('排除条件不能超过 200 字')
    return
  }
  if (findDuplicate('EXCLUDE_NOTE', value)) {
    ElMessage.warning('该排除条件已存在')
    return
  }
  if (await addPreference('EXCLUDE_NOTE', { targetId: null, targetValue: value }, '已添加排除条件')) {
    excludeNoteDraft.value = ''
  }
}

const openNoteEdit = (item: MemoryPreference) => {
  noteEdit.value = {
    visible: true,
    id: item.id,
    category: item.category,
    value: item.targetValue,
  }
}

const submitNoteEdit = async () => {
  const { id, value } = noteEdit.value
  if (id == null) return
  const trimmed = value.trim()
  if (!trimmed) {
    ElMessage.warning('内容不能为空')
    return
  }
  if (trimmed.length > 200) {
    ElMessage.warning('内容不能超过 200 字')
    return
  }
  saving.value = true
  try {
    const res = await updateMemoryPreference(id, { targetId: null, targetValue: trimmed })
    if (res.code === 0) {
      ElMessage.success('已更新')
      noteEdit.value.visible = false
      await loadOverview(new AbortController().signal)
    } else {
      ElMessage.error(res.message || '更新失败')
    }
  } catch (error: any) {
    ElMessage.error(describeError(error, '更新失败'))
  } finally {
    saving.value = false
  }
}

const removePreference = async (item: MemoryPreference, label: string) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${item.targetValue}」这条${label}吗？删除后 AI 将不再参考它。`,
      '删除确认',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    const res = await deleteMemoryPreference(item.id)
    if (res.code === 0) {
      ElMessage.success('已删除')
      await loadOverview(new AbortController().signal)
    } else {
      ElMessage.error(res.message || '删除失败')
    }
  } catch (error: any) {
    ElMessage.error(describeError(error, '删除失败'))
  } finally {
    saving.value = false
  }
}

/* ---------------- 模块五：记忆控制 ---------------- */

const onTogglePersonalization = async (value: boolean) => {
  if (savingPersonalization.value) return
  const previous = personalizationEnabled.value
  savingPersonalization.value = true
  personalizationEnabled.value = value
  try {
    const res = await setPersonalization(value)
    if (res.code === 0) {
      personalizationEnabled.value = res.data ?? value
      ElMessage.success(
        personalizationEnabled.value ? '已开启个性化推荐' : '已关闭个性化推荐'
      )
      await loadOverview(new AbortController().signal)
    } else {
      personalizationEnabled.value = previous
      ElMessage.error(res.message || '设置失败')
    }
  } catch (error: any) {
    personalizationEnabled.value = previous
    ElMessage.error(describeError(error, '设置失败'))
  } finally {
    savingPersonalization.value = false
  }
}

const handleClearAutoMemory = async () => {
  try {
    await ElMessageBox.confirm(
      '将清除 AI 自动生成的音乐画像与会话摘要。你手动指定的偏好不受影响；清除后，画像会在你继续收藏歌曲、创建歌单时逐步重新生成。是否继续？',
      '清除自动生成的记忆',
      { confirmButtonText: '确认清除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  clearing.value = true
  try {
    const res = await clearAutoMemory()
    if (res.code === 0) {
      const cleared = res.data?.summariesCleared ?? 0
      ElMessage.success(`已清除自动生成的记忆（会话摘要 ${cleared} 条）`)
      await loadAll()
    } else {
      ElMessage.error(res.message || '清除失败')
    }
  } catch (error: any) {
    ElMessage.error(describeError(error, '清除失败'))
  } finally {
    clearing.value = false
  }
}

onMounted(() => {
  if (!userStore.isLoggedIn) {
    authVisible.value = true
    return
  }
  loadAll()
})

onBeforeUnmount(() => {
  controller?.abort()
})
</script>

<template>
  <div class="memory-container">
    <div class="page-header">
      <h2 class="page-title">AI 记忆与偏好</h2>
      <p class="page-subtitle">
        查看 AI 记住的关于你的音乐信息，纠正不准确的偏好，让推荐更符合你的喜好。
      </p>
    </div>

    <el-alert
      v-if="errorText"
      class="error-alert"
      type="error"
      :closable="false"
      show-icon
      :title="errorText"
    >
      <template #default>
        <el-button size="small" text type="primary" @click="reload">重新加载</el-button>
      </template>
    </el-alert>

    <div v-if="loading" class="loading-block">
      <el-skeleton :rows="8" animated />
    </div>

    <template v-else>
      <!-- 模块一：系统推断的音乐画像 -->
      <section class="card">
        <div class="card-head">
          <div class="card-head-left">
            <span class="card-icon card-icon--auto"><icon-ri:brain-line /></span>
            <div>
              <div class="card-title">AI 根据你的音乐行为形成的画像</div>
              <div class="card-desc">
                根据你的收藏、歌单等音乐数据，由系统自动推断得出，你无法直接修改。
              </div>
            </div>
          </div>
          <span class="updated-at">最后更新：{{ formatTime(profile?.updatedAt) }}</span>
        </div>

        <div v-if="hasProfile" class="profile-body">
          <div class="profile-col">
            <div class="field-label">喜欢的歌手</div>
            <div v-if="topArtists.length" class="chip-list">
              <span v-for="item in topArtists" :key="item.name" class="chip">
                {{ item.name }}<span class="chip-count">{{ item.count }}</span>
              </span>
            </div>
            <div v-else class="field-empty">暂无数据</div>
          </div>

          <div class="profile-col">
            <div class="field-label">偏好的音乐风格</div>
            <div v-if="topStyles.length" class="chip-list">
              <span v-for="item in topStyles" :key="item.name" class="chip chip--style">
                {{ item.name }}<span class="chip-count">{{ item.count }}</span>
              </span>
            </div>
            <div v-else class="field-empty">暂无数据</div>
          </div>

          <div class="profile-col">
            <div class="field-label">偏好概述</div>
            <p v-if="profileSummary" class="summary-text">{{ profileSummary }}</p>
            <div v-else class="field-empty">暂无概述</div>
          </div>
        </div>

        <div v-else class="empty-block">
          目前还没有足够的音乐数据形成偏好画像。随着你收藏歌曲、创建歌单，AI 将逐步了解你的音乐喜好。
        </div>

        <div class="source-row">
          <span class="source-label">记忆来源：</span>
          <span v-for="source in memorySources" :key="source.label" class="source-item">
            <icon-ri:database-2-line />{{ source.label }}
            <b>{{ source.count }}</b>
          </span>
        </div>
      </section>

      <!-- 模块二：你手动指定的偏好 -->
      <section class="card">
        <div class="card-head">
          <div class="card-head-left">
            <span class="card-icon card-icon--manual"><icon-ri:user-star-line /></span>
            <div>
              <div class="card-title">告诉 AI 你真正喜欢什么</div>
              <div class="card-desc">
                这些偏好由你主动指定，会长期保留，并在推荐时优先于系统推断。
              </div>
            </div>
          </div>
        </div>

        <!-- 喜欢的歌手 -->
        <div class="pref-block">
          <div class="field-label">喜欢的歌手</div>
          <div class="chip-list chip-list--editable">
            <span v-for="item in favoriteArtists" :key="item.id" class="chip chip--closable">
              {{ item.targetValue }}
              <icon-ri:close-line class="chip-close" @click="removePreference(item, '喜欢的歌手')" />
            </span>
            <span v-if="!favoriteArtists.length" class="field-empty">还没有添加</span>
          </div>
          <div class="add-row">
            <el-select
              v-model="selectedArtistId"
              class="add-select"
              placeholder="选择歌手"
              filterable
              clearable
              :disabled="saving"
            >
              <el-option
                v-for="artist in artistOptions"
                :key="artist.artistId"
                :label="artist.artistName"
                :value="artist.artistId"
              />
            </el-select>
            <el-button type="primary" :loading="saving" @click="addFavoriteArtist">添加</el-button>
          </div>
        </div>

        <!-- 喜欢的音乐风格 -->
        <div class="pref-block">
          <div class="field-label">喜欢的音乐风格</div>
          <div class="chip-list chip-list--editable">
            <span v-for="item in favoriteStyles" :key="item.id" class="chip chip--closable">
              {{ item.targetValue }}
              <icon-ri:close-line class="chip-close" @click="removePreference(item, '喜欢的风格')" />
            </span>
            <span v-if="!favoriteStyles.length" class="field-empty">还没有添加</span>
          </div>
          <div class="add-row">
            <el-select
              v-model="selectedStyle"
              class="add-select"
              placeholder="选择音乐风格"
              filterable
              clearable
              :disabled="saving"
            >
              <el-option v-for="style in styleOptions" :key="style" :label="style" :value="style" />
            </el-select>
            <el-button type="primary" :loading="saving" @click="addFavoriteStyle">添加</el-button>
          </div>
        </div>

        <!-- 偏好要求 -->
        <div class="pref-block">
          <div class="field-label">偏好要求</div>
          <ul class="note-list">
            <li v-for="item in preferenceNotes" :key="item.id" class="note-item">
              <span class="note-text">{{ item.targetValue }}</span>
              <span class="note-actions">
                <icon-ri:edit-line class="note-action" @click="openNoteEdit(item)" />
                <icon-ri:delete-bin-line
                  class="note-action note-action--danger"
                  @click="removePreference(item, '偏好要求')"
                />
              </span>
            </li>
            <li v-if="!preferenceNotes.length" class="field-empty">还没有添加</li>
          </ul>
          <div class="add-row add-row--note">
            <el-input
              v-model="noteDraft"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="例如：更喜欢旋律优美的华语歌曲"
              :disabled="saving"
            />
            <el-button type="primary" :loading="saving" @click="addPreferenceNote">添加</el-button>
          </div>
        </div>

        <!-- 排除条件 -->
        <div class="pref-block">
          <div class="field-label">排除条件</div>
          <ul class="note-list">
            <li v-for="item in excludeNotes" :key="item.id" class="note-item">
              <span class="note-text">{{ item.targetValue }}</span>
              <span class="note-actions">
                <icon-ri:edit-line class="note-action" @click="openNoteEdit(item)" />
                <icon-ri:delete-bin-line
                  class="note-action note-action--danger"
                  @click="removePreference(item, '排除条件')"
                />
              </span>
            </li>
            <li v-if="!excludeNotes.length" class="field-empty">还没有添加</li>
          </ul>

          <div class="exclude-grid">
            <div class="exclude-cell">
              <div class="exclude-label">排除歌手</div>
              <div class="chip-list chip-list--editable">
                <span v-for="item in excludeArtists" :key="item.id" class="chip chip--closable chip--exclude">
                  {{ item.targetValue }}
                  <icon-ri:close-line class="chip-close" @click="removePreference(item, '排除歌手')" />
                </span>
                <span v-if="!excludeArtists.length" class="field-empty">还没有添加</span>
              </div>
              <div class="add-row">
                <el-select
                  v-model="excludeArtistId"
                  class="add-select"
                  placeholder="选择要排除的歌手"
                  filterable
                  clearable
                  :disabled="saving"
                >
                  <el-option
                    v-for="artist in artistOptions"
                    :key="artist.artistId"
                    :label="artist.artistName"
                    :value="artist.artistId"
                  />
                </el-select>
                <el-button :loading="saving" @click="addExcludeArtist">添加</el-button>
              </div>
            </div>

            <div class="exclude-cell">
              <div class="exclude-label">排除风格</div>
              <div class="chip-list chip-list--editable">
                <span v-for="item in excludeStyles" :key="item.id" class="chip chip--closable chip--exclude">
                  {{ item.targetValue }}
                  <icon-ri:close-line class="chip-close" @click="removePreference(item, '排除风格')" />
                </span>
                <span v-if="!excludeStyles.length" class="field-empty">还没有添加</span>
              </div>
              <div class="add-row">
                <el-select
                  v-model="excludeStyle"
                  class="add-select"
                  placeholder="选择要排除的风格"
                  filterable
                  clearable
                  :disabled="saving"
                >
                  <el-option v-for="style in styleOptions" :key="style" :label="style" :value="style" />
                </el-select>
                <el-button :loading="saving" @click="addExcludeStyle">添加</el-button>
              </div>
            </div>
          </div>

          <div class="add-row add-row--note">
            <el-input
              v-model="excludeNoteDraft"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="例如：减少过于吵闹的歌曲"
              :disabled="saving"
            />
            <el-button type="primary" :loading="saving" @click="addExcludeNote">添加排除条件</el-button>
          </div>
        </div>

        <div v-if="!hasManualPreferences" class="empty-block">
          还没有手动指定任何偏好。添加后，AI 会优先参考它们进行推荐。
        </div>
      </section>

      <!-- 模块三：最终生效的偏好 -->
      <section class="card">
        <div class="card-head">
          <div class="card-head-left">
            <span class="card-icon card-icon--effective"><icon-ri:equalizer-line /></span>
            <div>
              <div class="card-title">AI 将如何为你推荐音乐</div>
              <div class="card-desc">
                手动偏好与系统画像合并后，实际会生效的偏好。「优先考虑」是排序倾向，并不是强制约束。
              </div>
            </div>
          </div>
        </div>

        <div v-if="conflicts.length" class="conflict-list">
          <div v-for="(conflict, index) in conflicts" :key="index" class="conflict-item">
            <icon-ri:error-warning-line />
            <span>{{ conflict.message }}</span>
          </div>
        </div>

        <div v-if="effectiveGroups.length" class="effective-body">
          <div v-for="group in effectiveGroups" :key="group.key" class="effective-row">
            <div class="effective-label">
              <span>{{ group.label }}</span>
              <span class="effective-mode" :class="{ 'effective-mode--exclude': group.mode === '优先排除' }">
                {{ group.mode }}
              </span>
            </div>
            <div class="chip-list">
              <span v-for="(item, index) in group.items" :key="index" class="chip chip--effective">
                {{ item.value }}
                <span class="source-tag" :class="item.source === 'MANUAL' ? 'source-tag--manual' : 'source-tag--auto'">
                  {{ item.source === 'MANUAL' ? '来自你的设置' : '系统推断' }}
                </span>
              </span>
            </div>
          </div>
        </div>

        <div v-else class="empty-block">
          当前没有生效的偏好。你可以添加手动偏好，或让 AI 通过你的音乐行为形成画像。
        </div>
      </section>

      <!-- 模块四：记忆说明与来源 -->
      <section class="card">
        <div class="card-head">
          <div class="card-head-left">
            <span class="card-icon card-icon--source"><icon-ri:information-line /></span>
            <div>
              <div class="card-title">AI 的记忆从哪里来</div>
              <div class="card-desc">了解 AI 记住的信息分别来自哪里，以及它们各自的作用。</div>
            </div>
          </div>
        </div>

        <div class="explain-grid">
          <div class="explain-item">
            <icon-ri:bar-chart-box-line class="explain-icon" />
            <div class="explain-title">音乐画像</div>
            <p class="explain-text">
              由系统根据你收藏的歌曲、创建的歌单自动聚合推断，会随音乐数据变化惰性刷新。它只反映你的音乐行为，不会被手动偏好覆盖。
            </p>
          </div>
          <div class="explain-item">
            <icon-ri:user-star-line class="explain-icon" />
            <div class="explain-title">用户偏好</div>
            <p class="explain-text">
              你在本页主动指定的喜好与排除条件，长期有效，独立于自动画像保存，也不会被自动刷新覆盖。
            </p>
          </div>
          <div class="explain-item">
            <icon-ri:chat-history-line class="explain-icon" />
            <div class="explain-title">会话摘要</div>
            <p class="explain-text">
              AI 对话中近期上下文的压缩摘要，用于让连续对话更连贯。它属于会话记忆，不是永久的音乐偏好，也不会被当作口味画像。
            </p>
          </div>
        </div>

        <div class="explain-note">
          说明：播放历史分析等能力尚未实现，不作为当前的记忆来源；AI 不会把未实现的能力伪装成已有功能。
        </div>
      </section>

      <!-- 模块五：记忆控制 -->
      <section class="card">
        <div class="card-head">
          <div class="card-head-left">
            <span class="card-icon card-icon--control"><icon-ri:settings-3-line /></span>
            <div>
              <div class="card-title">记忆控制</div>
              <div class="card-desc">管理 AI 使用你个人信息的范围。</div>
            </div>
          </div>
        </div>

        <div class="control-row">
          <div class="control-info">
            <div class="control-title">个性化推荐</div>
            <p class="control-desc">
              开启后，AI 会参考你的音乐画像与手动偏好进行推荐；关闭后不再注入这些个性化信息（会话记忆仍会保留，用于保证对话连贯）。
            </p>
          </div>
          <el-switch
            :model-value="personalizationEnabled"
            :loading="savingPersonalization"
            active-text="开启"
            inactive-text="关闭"
            @change="onTogglePersonalization"
          />
        </div>

        <div class="control-row">
          <div class="control-info">
            <div class="control-title">清除自动生成的记忆</div>
            <p class="control-desc">
              清除 AI 自动生成的音乐画像与会话摘要。手动偏好不受影响；清除后，画像会在你继续收藏歌曲、创建歌单时逐步重新生成。
            </p>
          </div>
          <el-button type="danger" plain :loading="clearing" @click="handleClearAutoMemory">
            清除
          </el-button>
        </div>

        <div class="control-row">
          <div class="control-info">
            <div class="control-title">删除手动偏好</div>
            <p class="control-desc">
              在「告诉 AI 你真正喜欢什么」区域，逐条删除不再需要的偏好，删除前会再次确认。
            </p>
          </div>
        </div>
      </section>
    </template>

    <!-- 编辑偏好要求 / 排除条件 -->
    <el-dialog v-model="noteEdit.visible" title="编辑" width="480px">
      <el-input
        v-model="noteEdit.value"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        :placeholder="noteEdit.category === 'PREFERENCE_NOTE' ? '输入偏好要求' : '输入排除条件'"
      />
      <template #footer>
        <el-button @click="noteEdit.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitNoteEdit">保存</el-button>
      </template>
    </el-dialog>

    <AuthTabs v-model="authVisible" />
  </div>
</template>

<style scoped>
.memory-container {
  max-width: 1000px;
  margin: 30px auto;
  padding: 30px 40px 15px;
  background-color: var(--el-bg-color);
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.page-header {
  margin-bottom: 24px;
}

.page-title {
  margin: 0 0 8px 0;
  font-size: 20px;
  font-weight: normal;
  color: var(--el-text-color-primary);
}

.page-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.error-alert {
  margin-bottom: 24px;
  border-radius: 8px;
}

.loading-block {
  padding: 12px 0;
}

.card {
  margin-bottom: 24px;
  padding: 20px;
  border-radius: 12px;
  background-color: var(--el-fill-color-blank);
  box-shadow: 0 0 0 1px var(--el-border-color-light) inset;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.card-head-left {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  font-size: 18px;
  flex-shrink: 0;
}

.card-icon--auto {
  color: #2a68fa;
  background-color: rgba(42, 104, 250, 0.12);
}

.card-icon--manual {
  color: #7c5cff;
  background-color: rgba(124, 92, 255, 0.12);
}

.card-icon--effective {
  color: #31c27c;
  background-color: rgba(49, 194, 124, 0.14);
}

.card-icon--source {
  color: #e6a23c;
  background-color: rgba(230, 162, 60, 0.14);
}

.card-icon--control {
  color: var(--el-text-color-regular);
  background-color: var(--el-fill-color);
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.card-desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.updated-at {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.profile-body {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.profile-col {
  min-width: 0;
}

.field-label {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.field-empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.summary-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  background-color: var(--el-fill-color);
}

.chip--style {
  color: #2a68fa;
  background-color: rgba(42, 104, 250, 0.1);
}

.chip--exclude {
  color: var(--el-color-danger);
  background-color: rgba(245, 108, 108, 0.1);
}

.chip--effective {
  gap: 6px;
}

.chip-count {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.chip--closable {
  padding-right: 6px;
}

.chip-close {
  cursor: pointer;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  transition: color 0.2s;
}

.chip-close:hover {
  color: var(--el-color-danger);
}

.source-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.source-label {
  color: var(--el-text-color-regular);
}

.source-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.source-item b {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.empty-block {
  padding: 16px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  background-color: var(--el-fill-color-lighter);
}

.pref-block {
  padding: 14px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

.pref-block:first-of-type {
  border-top: none;
  padding-top: 0;
}

.add-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
}

.add-row--note {
  align-items: flex-end;
}

.add-select {
  width: 240px;
}

.note-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.note-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 8px;
  border-radius: 8px;
  background-color: var(--el-fill-color-lighter);
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.note-text {
  word-break: break-word;
}

.note-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.note-action {
  cursor: pointer;
  font-size: 15px;
  color: var(--el-text-color-secondary);
  transition: color 0.2s;
}

.note-action:hover {
  color: var(--el-color-primary);
}

.note-action--danger:hover {
  color: var(--el-color-danger);
}

.exclude-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
  margin-top: 12px;
}

.exclude-label {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.conflict-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.conflict-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--el-color-warning);
  background-color: rgba(230, 162, 60, 0.1);
}

.effective-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.effective-row {
  display: flex;
  gap: 16px;
}

.effective-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 120px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.effective-mode {
  font-size: 11px;
  color: #31c27c;
}

.effective-mode--exclude {
  color: var(--el-color-danger);
}

.source-tag {
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 11px;
}

.source-tag--manual {
  color: #7c5cff;
  background-color: rgba(124, 92, 255, 0.14);
}

.source-tag--auto {
  color: var(--el-text-color-secondary);
  background-color: var(--el-fill-color-dark);
}

.explain-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.explain-item {
  padding: 14px;
  border-radius: 8px;
  background-color: var(--el-fill-color-lighter);
}

.explain-icon {
  font-size: 18px;
  color: #2a68fa;
}

.explain-title {
  margin: 8px 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.explain-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.explain-note {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.control-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

.control-row:first-of-type {
  border-top: none;
  padding-top: 0;
}

.control-info {
  flex: 1;
  min-width: 0;
}

.control-title {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.control-desc {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

:deep(.el-input__wrapper),
:deep(.el-textarea__inner) {
  border-radius: 8px;
}

:deep(.el-select__wrapper) {
  border-radius: 8px;
}

:deep(.el-dialog) {
  border-radius: 12px;
}
</style>
