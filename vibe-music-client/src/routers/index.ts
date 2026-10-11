import {
  createRouter,
  createWebHistory,
  createWebHashHistory,
} from 'vue-router'
import Store from '@/stores'
import { UserStore } from '@/stores/modules/user'

const mode = import.meta.env.VITE_ROUTER_MODE

const routerMode = {
  hash: () => createWebHashHistory(),
  history: () => createWebHistory(),
}

const router = createRouter({
  history: routerMode[mode](),
  strict: false,
  scrollBehavior: () => ({ left: 0, top: 0 }),
  routes: [
    {
      path: '/landing',
      component: () => import('@/pages/landing/index.vue'),
      meta: { standalone: true },
    },
    {
      path: '/login',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { standalone: true },
    },
    {
      path: '/',
      component: () => import('@/pages/index.vue'),
    },
    {
      path: '/library',
      component: () => import('@/pages/library/index.vue'),
    },
    {
      path: '/artist',
      component: () => import('@/pages/artist/index.vue'),
    },
    {
      path: '/artist/:id',
      component: () => import('@/pages/artist/[id].vue'),
    },
    {
      path: '/album',
      component: () => import('@/pages/album/index.vue'),
    },
    {
      path: '/album/:id',
      component: () => import('@/pages/album/[id].vue'),
    },
    {
      path: '/playlist',
      component: () => import('@/pages/playlist/index.vue'),
    },
    {
      path: '/playlist/:id',
      component: () => import('@/pages/playlist/[id].vue'),
    },
    {
      path: '/like',
      component: () => import('@/pages/like/index.vue'),
    },
    {
      path: '/user',
      component: () => import('@/pages/user/index.vue'),
    },
  ],
})

const GUEST_MODE_KEY = 'vibe-music-guest-mode'

router.beforeEach((to) => {
  const userStore = UserStore(Store)
  const guestMode = sessionStorage.getItem(GUEST_MODE_KEY) === 'true'

  if (to.path === '/' && !userStore.isLoggedIn && !guestMode) {
    return '/login'
  }

  if (
    (to.path === '/landing' || to.path === '/login') &&
    userStore.isLoggedIn
  ) {
    return '/'
  }
})

export default router
