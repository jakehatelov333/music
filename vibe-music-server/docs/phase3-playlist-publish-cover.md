# Playlist publication, covers and admin management (phase 3)

Adds a publication state to playlists, a persisted default cover with an owner-uploaded override,
and the admin-side management surface. It builds on phase 2; nothing in phase 2 is rewritten.

## 1. Publication state and visibility are independent

`tb_playlist.status` is `DRAFT` or `PUBLISHED`. It is a separate dimension from `tb_playlist.is_public`:

| status | is_public | 普通用户可见 |
|---|---|---|
| `DRAFT` | either | 否 |
| `PUBLISHED` | `true` | 是 |
| `PUBLISHED` | `false` | 否（仅所有者与管理员） |

Access is enforced in SQL and in `PlaylistAccessService`, never by hiding UI:

* `PlaylistMapper.getPlaylistDetailById` filters `owner_user_id = viewer OR (is_public = 1 AND status = 'PUBLISHED')`.
* Public list, recommendations, favourites list, global search and the Agent's `searchPlaylists` tool all add the same predicate.
* `PlaylistAccessService.requireReadable` returns the playlist to its owner unconditionally, and to everybody else only when published **and** public. A `NULL` status can only come from a pre-migration row and is treated as published.

Personal (user-owned) playlists are always created as `PUBLISHED`; their exposure is decided by `is_public` alone.

## 2. Covers

`tb_playlist.cover_is_custom` records provenance:

* `FALSE` — `cover_url` was derived from a song cover (default cover).
* `TRUE` — the owner uploaded it; automatic defaults must never overwrite it.

Default cover rule: the cover of the playlist's first song that has one, matching the detail query order
(`ORDER BY pb.song_id`). It is stored in `cover_url` at creation and after adding songs to a playlist that
has no cover yet. `applyDefaultCover` is skipped when `cover_is_custom = TRUE` or `cover_url` is already set,
so a default cover is stable and a custom cover is permanent.

Replacing a cover deletes the previous object only when `cover_is_custom` was `TRUE` — a default cover is a
shared song image and must survive. Deletion is registered as an `afterCommit` synchronisation so a rollback
cannot remove a still-referenced file.

### Personal playlist cover endpoints

* `PATCH /playlist/mine/{id}/cover`, multipart field `cover`: owner only. Rejects an empty file, anything over
  5 MB and any content type that is not `image/*`. Returns `PlaylistDetailVO`. Sets `cover_is_custom = true`.
* `DELETE /playlist/mine/{id}/cover`: owner only. Re-derives the default cover, sets `cover_is_custom = false`,
  and removes the previously uploaded object after commit.

Both are covered by the existing `ROLE_USER` → `/playlist/` permission prefix; no interceptor change was needed.

## 3. Admin playlist management

`AdminPlaylistQueryDTO` is deliberately separate from `PlaylistDTO` so the public endpoint cannot be used to
filter on publication state or source.

**The admin list covers user-owned playlists too.** Restricting it to `owner_user_id IS NULL` made
administrators unable to find a playlist a user had created, so the list now returns both and reports the
source via `ownerUserId` / `ownerName`. Writes are split accordingly:

| 操作 | 平台歌单 | 用户歌单 |
|---|---|---|
| 查看详情 / 歌曲列表 | ✅ | ✅（只读） |
| 编辑基本信息、发布、撤回、增删歌曲、上传封面 | ✅ | ❌ 404 |
| 删除 | ✅ | ✅（内容治理；级联清理绑定/收藏/评论） |

* `POST /admin/getAllPlaylists` — filters: `title` (LIKE), `style` (LIKE, because styles can be custom),
  `status`, `source` (`PLATFORM` / `USER`). An unrecognised `status` or `source` is rejected with 400 rather
  than silently returning an empty page. Returns `AdminPlaylistVO`, which adds `songCount`, `status`,
  `coverIsCustom`, `ownerUserId` and `ownerName`.
* `GET /admin/getPlaylistDetail/{id}` — any playlist, including drafts and private ones.
* `POST /admin/addPlaylist` — always creates a `DRAFT`, always public, and accepts an initial `songIds` list
  (≤100) so a playlist is never created as an unusable empty record. A missing song aborts the whole insert.
* `PUT /admin/updatePlaylist` — title, introduction, style. Publication state and visibility are not changed here.
* `PATCH /admin/publishPlaylist/{id}` — sets `PUBLISHED` and `is_public = TRUE`.
* `PATCH /admin/unpublishPlaylist/{id}` — back to `DRAFT`.
* `POST /admin/addPlaylistSongs/{id}` — `{songIds:[…]}`; duplicates are harmless, a missing song rejects the batch.
* `DELETE /admin/removePlaylistSong/{playlistId}/{songId}` — idempotent.
* Existing cover upload, single delete and batch delete are unchanged apart from marking the cover as custom.

All of these go through `PlaylistAccessService.requireAdmin()` / `lockPlatform()`, so a normal user token gets
403 and an anonymous request 401, independent of the UI.

### Platform visibility

Platform playlists are **always public**. Private visibility has no meaning for them — the draft state already
expresses "not live yet" — so the create/edit form no longer offers a choice, `addPlaylist` / `updatePlaylist`
force `is_public = TRUE`, and publishing sets it again. User-owned playlists keep their own visibility.

### Song search

The song picker searches by **song name and artist name** (and album). `SongAndArtistDTO.artistName` is a new
optional LIKE filter on `tb_artist.name`. Note that the `@Cacheable` key on `getAllSongsByArtist` must list
every filter field: a missing `artistName` made different searches share one cached page.

## 3b. Public playlist list personalisation

`GET /playlist/getAllPlaylists` is still reachable anonymously (it moved from `excludePathPatterns` into the
`LoginInterceptor` allow-list), but when a token is present the identity is verified so `optionalUserId()`
resolves. `PlaylistMapper.getPublicPlaylists` then:

* pushes the caller's own playlists to the end (`ORDER BY owned ASC, p.id DESC`), so a freshly created playlist
  no longer occupies the first page of discovery;
* returns `owned` / `favorite` flags that the client renders as 「我创建的」/「已收藏」 badges.

Anonymous callers get plain `id DESC` order and no flags.

## 4. Ordering

`tb_playlist_binding` has no position column and its primary key is `(playlist_id, song_id)`; both the user and
admin views therefore present songs ordered by `song_id`. Reordering would need a new column and is out of scope.

## 5. Migration

`sql/phase3_playlist_state_and_cover.sql` is repeatable and does not run automatically at startup:

1. Adds `status` with `DEFAULT 'PUBLISHED'`, so every existing row keeps its current discovery behaviour.
2. Adds `cover_is_custom` with `DEFAULT FALSE`.
3. Adds the `(status, is_public, id)` index.
4. Backfills `cover_url` for rows that have none, using the first bound song's cover. Guarded by
   `cover_url IS NULL OR cover_url = ''`, so replaying never overwrites a real cover.
5. Marks pre-existing **platform** covers as uploaded (`cover_is_custom = TRUE`), excluding any cover that is
   still referenced by `tb_song.cover_url`. This makes replacing a platform cover clean up the old object,
   while a song-derived cover is never deleted by a playlist operation.
6. Prints postflight status counts, rows still without a cover, and platform covers with their flag.

Verified locally on 2026-10-12: 18 playlists stayed `PUBLISHED`, and the one personal playlist without a cover
received the cover of its first bound song.

## 6. Verification

* `PlaylistBusinessTest` (H2 in MySQL mode, real MyBatis SQL and Spring transactions) covers draft discovery
  suppression, publishing a private playlist, the default cover fallback, custom-cover persistence across song
  additions, cover reset, and cover upload validation. 14 tests, all green.
* The full Maven suite (66 tests) passes.
* `scripts`-free HTTP integration run against a live backend on 2026-10-12: 41 assertions covering creation,
  publication, visibility, combined filters, song add/remove, permission failures, default cover, upload,
  persistence, reset and cross-user rejection — all passed.
