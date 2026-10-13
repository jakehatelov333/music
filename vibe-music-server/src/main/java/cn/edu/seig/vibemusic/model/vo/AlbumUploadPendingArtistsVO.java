package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 专辑上传提交时「歌手需要人工确认」的清单（{@code code = 409} 时的 data）。
 *
 * <p>背景：音频标签里的歌手名经常拼错或写成未入库的新人。上传提交不会静默丢弃这些名字，
 * 也不会未经确认就建歌手，而是先把清单返回给管理员，确认后再创建并继续上传。
 */
@Data
public class AlbumUploadPendingArtistsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 库中不存在、管理员确认后将被创建的歌手名（去重、保序）。 */
    private List<String> missingArtists;

    /** 同名存在多条启用记录的名字：必须先到歌手管理处理，否则本次上传会被拒绝。 */
    private List<String> ambiguousArtists;

    /** 本次提交涉及的歌曲数，仅用于前端提示。 */
    private Integer songCount;
}
