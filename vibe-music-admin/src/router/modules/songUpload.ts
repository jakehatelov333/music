export default {
  path: "/song-upload",
  redirect: "/song-upload/index",
  meta: {
    icon: "ri:upload-cloud-2-line",
    title: "歌曲上传",
    rank: 3.1
  },
  children: [
    {
      path: "/song-upload/index",
      name: "SongUpload",
      component: () => import("@/views/song-upload/index.vue"),
      meta: {
        title: "歌曲上传"
      }
    }
  ]
} satisfies RouteConfigsTable;
