package cn.edu.seig.vibemusic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 媒体对象引用的只读核对。
 *
 * <p>判定"某对象是否还被引用"是删除媒体文件的唯一前置条件，因此这里刻意只读、只查全量，
 * <b>不过滤任何可见性</b>：停用歌手、草稿专辑、私有歌单、非自定义的默认封面都算有效引用。
 * 少算一个引用就可能删掉别人还在用的文件，多算一个引用最多是文件多留一会儿。
 */
@Mapper
public interface MediaReferenceMapper {

    /**
     * 汇总 7 类引用，返回引用总行数。
     *
     * <p>匹配方式：{@code RIGHT(列, CHAR_LENGTH(键)) = 键}，即"URL 以该对象键结尾"。
     * 不用"完整 URL 相等"，因为历史数据里同一对象的主机名既有 {@code localhost} 也有
     * {@code 127.0.0.1}（实测两列并存），按当前 {@code minio.endpoint} 拼出的 URL 会与另一种
     * 主机名的行对不上，引用数被误判为 0 → 删掉仍被引用的对象。也不用 {@code LIKE}：对象键里
     * 实测含空格与 {@code +}，且 {@code _} 是 LIKE 通配符，需要转义才正确。
     *
     * <p>刻意不加 {@code COLLATE utf8mb4_bin}：比较用列自身的排序规则（大小写不敏感）只会
     * <b>多算</b>引用（更保守、不删），不会少算。而"两个对象仅大小写不同"在生成键上不可能发生——
     * 键形如 {@code 目录/UUID-原文件名}，UUID 已保证唯一性，键的差异必然出现在 UUID 段而不只是文件名大小写。
     *
     * <p>已知边界（刻意接受）：若另一目录下恰好存在以本键为后缀的对象（如 {@code other/<key>}），
     * 也会被计入引用。方向是<b>多算 → 漏删</b>，宁可文件多留一会儿，也不能删掉别人还在用的文件；
     * 且键含 UUID，真实数据里几乎不可能出现这种后缀撞车。空键的防护在
     * {@code MediaReferenceService}，mapper 层不重复（空串在 RIGHT(x,0) 下反而全列匹配）。
     */
    @Select("""
            SELECT
                (SELECT COUNT(*) FROM tb_song     WHERE cover_url   IS NOT NULL AND RIGHT(cover_url,   CHAR_LENGTH(#{objectKey})) = #{objectKey})
              + (SELECT COUNT(*) FROM tb_song     WHERE audio_url   IS NOT NULL AND RIGHT(audio_url,   CHAR_LENGTH(#{objectKey})) = #{objectKey})
              + (SELECT COUNT(*) FROM tb_album    WHERE cover_url   IS NOT NULL AND RIGHT(cover_url,   CHAR_LENGTH(#{objectKey})) = #{objectKey})
              + (SELECT COUNT(*) FROM tb_playlist WHERE cover_url   IS NOT NULL AND RIGHT(cover_url,   CHAR_LENGTH(#{objectKey})) = #{objectKey})
              + (SELECT COUNT(*) FROM tb_artist   WHERE avatar      IS NOT NULL AND RIGHT(avatar,      CHAR_LENGTH(#{objectKey})) = #{objectKey})
              + (SELECT COUNT(*) FROM tb_user     WHERE user_avatar IS NOT NULL AND RIGHT(user_avatar, CHAR_LENGTH(#{objectKey})) = #{objectKey})
              + (SELECT COUNT(*) FROM tb_banner   WHERE banner_url  IS NOT NULL AND RIGHT(banner_url,  CHAR_LENGTH(#{objectKey})) = #{objectKey})
            """)
    long countReferences(@Param("objectKey") String objectKey);
}
