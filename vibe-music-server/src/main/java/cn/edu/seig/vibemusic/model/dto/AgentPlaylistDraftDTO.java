package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户在确认卡片上修改歌单内容时提交的草稿。
 * 与 {@code PersonalPlaylistCreateDTO} 分开，是因为这里还必须带乐观锁版本号。
 */
@Data
public class AgentPlaylistDraftDTO {

    @NotBlank(message = "歌单名称不能为空")
    @Size(max = 255, message = "歌单名称不能超过 255 字")
    private String title;

    @Size(max = 2000, message = "简介不能超过 2000 字")
    private String introduction;

    @Size(max = 64, message = "风格不能超过 64 字")
    private String style;

    private Boolean isPublic = false;

    @Size(max = 100, message = "单个歌单最多 100 首歌")
    private List<Long> songIds = List.of();

    /** 客户端看到的版本号；与库中不一致说明预览已被其他操作更新，服务端会拒绝。 */
    @NotNull(message = "缺少版本号")
    private Integer version;
}
