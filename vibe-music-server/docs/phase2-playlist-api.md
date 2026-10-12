# Personal playlist API contract

Authentication uses the existing Authorization header. No userId is accepted from clients.

All responses use the existing Result wrapper (`code`, `message`, `data`).

* `GET /playlist/mine?pageNum=1&pageSize=20&title=...`: data `{total,items: PlaylistVO[]}`; pageNum/pageSize required.
* `POST /playlist/mine`: required header `Idempotency-Key` (16–64 ASCII letters, digits, hyphens or underscores; UUID recommended). JSON `{title, introduction?, style?, isPublic?: false, songIds?: []}`. Returns PlaylistDetailVO. Reuse the same key when retrying the same creation; new intent gets a new key. Key reuse with different normalized content returns 409. Deleted creation replay returns 410.
* `PUT /playlist/mine/{id}`: JSON `{title, introduction?, style?, isPublic?: false}`; full metadata replacement, returns PlaylistDetailVO.
* `DELETE /playlist/mine/{id}`: returns success. Repeating delete succeeds if already absent. Existing other users' or platform playlists return 404.
* `POST /playlist/mine/{id}/songs`: JSON `{songIds:[1,2]}`; 1–100 positive IDs; duplicates are harmless; missing song aborts the entire operation; returns PlaylistDetailVO.
* `DELETE /playlist/mine/{id}/songs/{songId}`: idempotent removal; returns PlaylistDetailVO.
* Existing `GET /playlist/getPlaylistDetail/{id}`: public/platform playlists visible to everyone; private playlists visible only to their authenticated owner; otherwise 404.

New PlaylistVO fields: `ownerUserId` (null for platform), `isPublic`, `introduction`, `style`, `songCount`. Detail adds `ownerUserId`, `isPublic`, `editable`, `style` to existing fields. PlaylistVO entries returned by `/mine` always belong to the current user; details expose `editable=true` only to that owner.

Title is required, trimmed, at most 255 chars. Introduction max 2,000 chars, style max 64 chars. Personal playlists default private. Existing platform playlists stay public. No personal cover upload is in this batch.

The existing admin playlist list/count/edit/delete endpoints now operate on platform playlists only. Service-level role checks reject direct reuse by normal-user or Agent paths. Admin cover cleanup runs after the database transaction commits so a rollback cannot delete a still-referenced cover. Personal playlist deletion relies on existing cascading foreign keys for bindings, comments and favorites; creation receipts remain available for retry detection.

Integration needs: ROLE_USER allow `/playlist/mine` and `/playlist/mine/**`; stop excluding detail from LoginInterceptor so optional supplied authentication is verified. Keep anonymous detail allowed via existing PathConstant. Public list/search/recommendations/tools only return public playlists. A database migration must precede rollout.

Implementation: `tb_playlist_binding` already has composite primary key `(playlist_id,song_id)` and cascading FKs. New create-request ledger has `(user_id, request_key)` unique key and retains IDs after playlist deletion to prevent accidental recreation on retry.

Backend integration: `cn.edu.seig.vibemusic.security.PlaylistAccessService` provides `assertReadable(Long)` and `lockReadable(Long)` returning Playlist. Call lockReadable inside the caller's transaction. It reads verified ThreadLocal identity, never request-supplied user IDs. Favorite playlist responses must not use a stale response cache: visibility can change after the response is cached. Public list response cache was removed for this reason. Recommendation routes should also use optional verified authentication if personalized recommendations are desired. PlaylistMapper detail now takes `(playlistId, viewerId)` so access filtering happens in the same SQL statement as content retrieval.

Verification passed in the consolidated Maven run: `cn.edu.seig.vibemusic.security.PlaylistBusinessTest` (isolated H2 MySQL mode, actual MyBatis SQL / Spring transactions). It covers private discovery/search/tool filtering, ownership of all writes, create retries and key/body conflicts, rollback after a binding failure, concurrent create/add, delete cascades, and deleted-resource replay. `PublicResponseIsolationTest` was updated to supply verified ThreadLocal identity and the mapper's viewer argument.

Migration: `sql/phase2_personal_playlists.sql` is a repeatable MySQL migration, not an automatically executed startup script. It adds nullable ownership, public visibility defaults preserving platform behavior, owner/visibility indexes, and the durable creation request table. The existing composite binding primary key and cascading foreign keys were verified. Existing data is not deleted or deduplicated. Local development migration was applied and replayed successfully on 2026-10-12; see `phase2-business-foundation.md` for backup, rollout and verification details.
