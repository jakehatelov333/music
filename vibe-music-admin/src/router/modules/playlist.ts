export default {
  path: "/playlist",
  redirect: "/playlist/index",
  meta: {
    icon: "ri:play-list-fill",
    title: "歌单管理",
    rank: 5
  },
  children: [
    {
      path: "/playlist/index",
      name: "playlistManagement",
      component: () => import("@/views/playlist/index.vue"),
      meta: {
        title: "歌单管理"
      }
    }
  ]
} satisfies RouteConfigsTable;
