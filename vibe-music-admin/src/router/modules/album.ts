export default {
  path: "/album",
  redirect: "/album/index",
  meta: {
    icon: "ri:album-fill",
    title: "专辑管理",
    rank: 4
  },
  children: [
    {
      path: "/album/index",
      name: "AlbumManagement",
      component: () => import("@/views/album/index.vue"),
      meta: {
        title: "专辑管理"
      }
    },
    {
      path: "/album-upload/index",
      name: "AlbumUpload",
      component: () => import("@/views/album-upload/index.vue"),
      meta: { title: "上传专辑", showLink: false }
    }
  ]
} satisfies RouteConfigsTable;
