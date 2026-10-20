package cn.edu.seig.vibemusic.service.artist;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * QQ 音乐歌手详情的解析结果。
 *
 * <p>字段来源分两处，刻意保留这个区分，因为它们的可信度不同：
 * <ul>
 *   <li>{@code basic_info} / {@code ex_info}：结构化字段，直接可用（名字、性别、生日、简介）；</li>
 *   <li>{@code wiki}：一段 XML 文本，需要从中抽 {@code <key>/<value>} 键值对。
 *       <b>国籍只能从这里取</b> —— {@code ex_info.area} 是 0~4 的分区码而不是国家
 *       （实测 area=0 里同时有中国、美国、新加坡；area=1 大部分为空）。</li>
 * </ul>
 *
 * @param singerMid    歌手 mid
 * @param name         QQ 侧歌手名
 * @param gender       映射后的性别：0-男，1-女，2-组合/乐队，{@code null} 表示未知
 * @param birth        生日；QQ 侧为空或格式异常时为 {@code null}
 * @param area         国籍/地区；取不到时为 {@code null}
 * @param introduction 简介；取不到或为空白时为 {@code null}
 * @param foreignName  外文名（仅用于展示，不落库）
 * @param seq          头像序号段
 * @param photoCount   QQ 侧该歌手的照片张数（{@code basic_info.has_photo}）。
 *                     注意这个字段名字像布尔值，实测是<b>张数</b>：周杰伦 11、邓紫棋 7、
 *                     冷门音乐人 3。{@code 0} 或 {@code null} 才表示没有照片。
 * @param wikiFields   wiki 里的全部键值对，原样保留供前端展示与后续扩展
 */
public record QqArtistProfile(String singerMid,
                              String name,
                              Integer gender,
                              LocalDate birth,
                              String area,
                              String introduction,
                              String foreignName,
                              String seq,
                              Integer photoCount,
                              Map<String, String> wikiFields) {

    /** QQ 的 {@code basic_info.type} → 本系统的 {@code gender}。 */
    public static final int TYPE_MALE = 0;
    public static final int TYPE_FEMALE = 1;
    public static final int TYPE_GROUP = 2;

    /**
     * 把 QQ 的 {@code type} 映射成本系统的 {@code gender}。
     *
     * <p>实测对照库里已有的 129 位歌手：22 位「组合/乐队」中 20 位在 QQ 侧是 {@code type=2}，
     * 没有一个反例；{@code type=3} 实测只出现 1 次（{@code Michael Cramtu}，一位资料极少的
     * 排箫演奏家），语义不明，因此按「未知」处理而不是猜一个值。
     *
     * @return 0/1/2，或 {@code null} 表示无法确定
     */
    public static Integer mapGender(Integer qqType) {
        if (qqType == null) {
            return null;
        }
        return switch (qqType) {
            case TYPE_MALE -> TYPE_MALE;
            case TYPE_FEMALE -> TYPE_FEMALE;
            case TYPE_GROUP -> TYPE_GROUP;
            default -> null;
        };
    }

    /** 是否至少有一个可填充的业务字段（用于前端判断「没搜到有效资料」）。 */
    public boolean hasAnyField() {
        return gender != null
                || birth != null
                || (area != null && !area.isBlank())
                || (introduction != null && !introduction.isBlank())
                || hasPhoto();
    }

    /**
     * QQ 侧是否确实有头像。
     *
     * <p>判据是 {@code has_photo > 0} 而不是"能拼出地址"：地址由 mid 唯一决定，
     * 只要 mid 合法就一定能拼出一个 URL，但对方可能根本没有照片 ——
     * 那时 CDN 会返回 404。所以"能不能拼地址"和"有没有图"是两件事，这里判后者。
     */
    public boolean hasPhoto() {
        return photoCount != null && photoCount > 0 && QqArtistAvatarUrl.isValidSingerMid(singerMid);
    }

    /** 该歌手的头像地址（默认尺寸）；无照片或 mid 不合法时返回 {@code null}。 */
    public String avatarUrl() {
        return hasPhoto() ? QqArtistAvatarUrl.of(singerMid, QqArtistAvatarUrl.DEFAULT_SIZE, seq) : null;
    }

    /** 各尺寸头像地址，供前端做尺寸切换；无照片时返回空 map。 */
    public Map<String, String> avatarUrls() {
        return hasPhoto() ? QqArtistAvatarUrl.allSizes(singerMid, seq) : Map.of();
    }

    /** wiki 里出现过但没有映射到业务字段的键，便于排查「为什么某字段没填上」。 */
    public List<String> wikiKeys() {
        return wikiFields == null ? List.of() : List.copyOf(wikiFields.keySet());
    }
}
