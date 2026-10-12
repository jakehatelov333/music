package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.enumeration.PlaylistStatusEnum;
import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PlaylistAccessService {
    private final PlaylistMapper playlists;

    public static Long optionalUserId() {
        Map<String, Object> claims = ThreadLocalUtil.get();
        return claims != null && RoleEnum.USER.getRole().equals(claims.get(JwtClaimsConstant.ROLE))
                ? CurrentUser.id() : null;
    }

    public static Long requireUserId() {
        Long id = optionalUserId();
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录用户账号");
        return id;
    }

    public Playlist assertReadable(Long id) {
        return requireReadable(playlists.selectById(positiveId(id)), optionalUserId());
    }

    /** Call inside the caller's transaction, preventing privacy changes until the write commits. */
    public Playlist lockReadable(Long id) {
        return requireReadable(playlists.lockById(positiveId(id)), optionalUserId());
    }

    public Playlist lockOwned(Long id) {
        Long ownerId = requireUserId();
        Playlist playlist = playlists.lockById(positiveId(id));
        if (playlist == null || !ownerId.equals(playlist.getOwnerUserId())) throw notFound();
        return playlist;
    }

    public static Playlist requireReadable(Playlist playlist, Long viewerId) {
        if (playlist == null) throw notFound();
        // The owner keeps full access to their own drafts and private playlists.
        if (viewerId != null && Objects.equals(viewerId, playlist.getOwnerUserId())) return playlist;
        // Publication and visibility are independent: a draft is never discoverable, and a published
        // playlist still has to be public. A null status can only come from legacy rows, treat as published.
        boolean published = !PlaylistStatusEnum.DRAFT.getStatus().equalsIgnoreCase(playlist.getStatus());
        if (published && Boolean.TRUE.equals(playlist.getIsPublic())) return playlist;
        throw notFound();
    }

    /** Admin-side read: platform playlists are visible regardless of publication state. */
    public Playlist lockPlatform(Long id) {
        requireAdmin();
        Playlist playlist = playlists.lockById(positiveId(id));
        if (playlist == null || playlist.getOwnerUserId() != null) throw notFound();
        return playlist;
    }

    public static long positiveId(Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资源 ID 不合法");
        return id;
    }

    public static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "歌单不存在或不可访问");
    }

    public static void requireAdmin() {
        Map<String, Object> claims = ThreadLocalUtil.get();
        if (claims == null || !RoleEnum.ADMIN.getRole().equals(claims.get(JwtClaimsConstant.ROLE)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
}
