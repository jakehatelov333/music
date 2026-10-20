package cn.edu.seig.vibemusic.service.artist;

/**
 * QQ 音乐歌手搜索结果的一条命中。
 *
 * <p>搜索接口不返回 {@code has_photo}，但返回的 {@code pic} 字段本身就有信息量：
 * 实测有照片的歌手一定带 {@code pic}，没有照片的则是空串。因此这里用
 * {@code seq}（从 {@code pic} 尾部抽出的序号）是否存在来间接判断"对方有没有头像" ——
 * 只要 mid 合法就能拼出地址，但拼得出不等于图存在，CDN 会返回 404。
 *
 * @param singerMid 歌手 mid，形如 {@code 0025NhlN2yWrP4}；用于查详情与拼头像地址
 * @param name      歌手名（QQ 侧展示名，可能与库里的写法不同，例如 {@code G.E.M.邓紫棋}）
 * @param seq       头像序号段（搜索结果 {@code pic} 尾部 {@code _11} 里的 {@code 11}）；
 *                  为 {@code null} 表示搜索接口没给出头像
 */
public record QqArtistHit(String singerMid, String name, String seq) {

    /** mid 是否可用于后续调用（详情/头像都依赖它）。 */
    public boolean usable() {
        return QqArtistAvatarUrl.isValidSingerMid(singerMid) && name != null && !name.isBlank();
    }

    /**
     * 搜索接口是否给出了头像线索。
     *
     * <p>只是"线索"：真正确认有图要等详情接口的 {@code has_photo}，
     * 或者干脆让 CDN 说话（下载失败就不设头像）。
     */
    public boolean hasAvatarHint() {
        return usable() && seq != null && !seq.isBlank();
    }

    /** 该命中的头像地址（默认尺寸）；没有头像线索或 mid 不合法时返回 {@code null}。 */
    public String avatarUrl() {
        return hasAvatarHint()
                ? QqArtistAvatarUrl.of(singerMid, QqArtistAvatarUrl.DEFAULT_SIZE, seq)
                : null;
    }

    /** 各尺寸头像地址；没有头像线索时返回空 map。 */
    public java.util.Map<String, String> avatarUrls() {
        return hasAvatarHint() ? QqArtistAvatarUrl.allSizes(singerMid, seq) : java.util.Map.of();
    }
}
