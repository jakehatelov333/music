<script setup lang="ts">
import { MenuData } from './data'
import { Icon } from '@iconify/vue'
import { useRoute, useRouter } from 'vue-router'
import { ref, watch } from 'vue'
import { UserStore } from '@/stores/modules/user'
import { ElMessage } from 'element-plus'
import AuthTabs from '@/components/Auth/AuthTabs.vue'
import { useFavoriteStore } from '@/stores/modules/favorite'

const props = withDefaults(defineProps<{ mobile?: boolean }>(), {
  mobile: false,
})
const route = useRoute()
const router = useRouter()
const emit = defineEmits<{
  navigate: [path: string]
}>()
const user = UserStore()
const favoriteStore = useFavoriteStore()
const authVisible = ref(false)

// 处理需要登录的路由
const handleProtectedRoute = (path: string) => {
  if (
    !user.isLoggedIn &&
    ['/like', '/user', '/my-playlists', '/play-history'].includes(path)
  ) {
    ElMessage.warning('请先登录')
    authVisible.value = true
    return false
  }
  return true
}

const navigateTo = (path: string) => {
  if (!handleProtectedRoute(path)) return
  emit('navigate', path)
  router.push(path)
}

const isActiveRoute = (path: string) =>
  route.path === path ||
  (path === '/my-playlists' && route.path.startsWith('/my-playlists/')) ||
  // 个人中心的子页面（AI 记忆与偏好、隐私与安全）也高亮「个人中心」
  (path === '/user' && route.path.startsWith('/user/'))

// 同步清理账号相关数据，已登录状态不变的换号也会触发。
watch(
  () => [user.isLoggedIn, user.userInfo?.userId, user.userInfo?.token],
  () => {
    favoriteStore.clearFavoritePlaylists()
    if (user.isLoggedIn && user.userInfo?.userId && user.userInfo?.token) {
      favoriteStore.getFavoritePlaylists()
    }
  },
  { immediate: true, flush: 'sync' }
)
</script>

<template>
  <aside
    class="app-sidebar w-64 h-full min-h-0 overflow-hidden border-r shadow-2xl shadow-blue-500/50"
    :class="props.mobile ? 'block' : 'hidden md:block'"
  >
    <nav
      class="flex flex-col p-4 space-y-4 flex-1 h-full box-border overflow-hidden"
    >
      <div
        v-for="(item, index) in MenuData"
        :key="index"
        class="w-full flex flex-col gap-1"
      >
        <h3 class="ml-4 text-sm font-semibold text-inactive">
          {{ item.title }}
        </h3>
        <div
          v-for="(item2, index2) in item.children"
          :key="index2"
          class="mx-2 rounded-lg transition text-lg duration-300 py-2 px-2 flex items-center gap-2 text-primary-foreground cursor-pointer"
          :class="{
            'bg-activeMenuBg': isActiveRoute(item2.router),
            'hover:bg-hoverMenuBg': !isActiveRoute(item2.router),
          }"
          @click="navigateTo(item2.router)"
        >
          <Icon :icon="item2.icon" />
          <span>{{ item2.title }}</span>
          <span
            class="!ml-auto text-xs text-primary-foreground bg-emphasis border-border p-1 rounded-lg"
          ></span>
        </div>
      </div>

      <!-- 收藏的歌单 -->
      <div class="w-full flex flex-col gap-1" v-if="user.isLoggedIn">
        <h3 class="ml-4 text-sm font-semibold text-inactive">
          收藏的歌单（{{ favoriteStore.favoritePlaylists.length }}）
        </h3>
        <el-scrollbar>
          <el-skeleton
            :loading="favoriteStore.loading"
            animated
            :count="3"
            v-if="favoriteStore.loading"
          >
            <template #template>
              <div class="flex items-center space-x-2 p-2 mx-2">
                <el-skeleton-item
                  variant="image"
                  style="width: 28px; height: 28px"
                />
                <el-skeleton-item variant="text" style="width: 130px" />
              </div>
            </template>
          </el-skeleton>

          <template v-else>
            <div
              v-for="item in favoriteStore.favoritePlaylists"
              :key="item.id"
              class="mx-2 my-1 rounded-lg transition text-sm duration-300 py-2 px-2 flex items-center gap-2 text-primary-foreground cursor-pointer"
              :class="{
                'bg-activeMenuBg': route.path === `/playlist/${item.id}`,
                'hover:bg-hoverMenuBg': route.path !== `/playlist/${item.id}`,
              }"
              @click="navigateTo(`/playlist/${item.id}`)"
            >
              <el-image
                lazy
                :src="item.coverImgUrl + '?param=50y50'"
                class="w-10 h-10 rounded-md flex-shrink-0"
                :alt="item.name"
              />
              <div class="flex-1 min-w-0">
                <span class="line-clamp-2 text-sm leading-normal">{{
                  item.name
                }}</span>
              </div>
            </div>
          </template>
        </el-scrollbar>
      </div>
    </nav>

    <!-- 登录对话框 -->
    <AuthTabs v-model="authVisible" />
  </aside>
</template>

<style scoped>
.app-sidebar {
  width: clamp(12rem, 15vw, 16rem);
}

@media (max-height: 760px) {
  .app-sidebar {
    width: clamp(11rem, 15vw, 14rem);
  }
}
</style>
