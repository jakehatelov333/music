package cn.edu.seig.vibemusic.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

@Data
public class ArtistUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌手 id
     */
    private Long artistId;

    /**
     * 歌手姓名
     */
    private String artistName;

    /**
     * 歌手性别：0-男，1-女
     */
    private Integer gender;


    /**
     * 歌手出生日期
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birth;

    /**
     * 歌手所处地区
     */
    private String area;

    /**
     * 歌手简介
     */
    private String introduction;

    /**
     * 歌手状态：ACTIVE-启用，DISABLED-停用（软删除）。
     * 管理端编辑框可直接启停；null 表示本次不修改状态。
     */
    private String status;

}
