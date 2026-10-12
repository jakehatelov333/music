export const MenuData = [
  {
    title: '',
    children: [{ title: '推荐', icon: 'ri:home-smile-2-line', router: '/' }],
  },
  {
    title: '发现',
    children: [
      { title: '曲库', icon: 'ri:music-2-line', router: '/library' },
      {
        title: '歌手',
        icon: 'ri:mic-line',
        router: '/artist',
      },
      {
        title: '专辑',
        icon: 'ri:album-line',
        router: '/album',
      },
      {
        title: '歌单',
        icon: 'ri:play-list-line',
        router: '/playlist',
      },
    ],
  },
  {
    title: '我的',
    children: [
      { title: '喜欢', icon: 'ri:heart-line', router: '/like' },
      {
        title: '我的歌单',
        icon: 'ri:play-list-add-line',
        router: '/my-playlists',
      },
      {
        title: '个人中心',
        icon: 'mi:user',
        router: '/user',
      },
    ],
  },
]
