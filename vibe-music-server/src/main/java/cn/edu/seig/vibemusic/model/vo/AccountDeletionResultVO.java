package cn.edu.seig.vibemusic.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 账号注销结果。
 *
 * <p>用于区分两种「都已注销」但外部清理状态不同的结局：
 * <ul>
 *     <li>{@code accountDeleted=true, cleanupPending=false}：账号与外部数据全部清理完成；</li>
 *     <li>{@code accountDeleted=true, cleanupPending=true}：账号已注销，但有外部资源（Redis token / MinIO 头像）
 *         清理失败，已落库等待后台重试 —— 这<b>不是</b>注销失败，也<b>不能</b>对外报成完全成功。</li>
 * </ul>
 *
 * <p>{@code pendingItems} 的取值为 {@code "REDIS_TOKEN"} 或 {@code "MINIO_AVATAR"}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountDeletionResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 账号（DB 中的用户及其级联数据）是否已删除。 */
    private boolean accountDeleted;

    /** 是否存在尚未完成、等待后台重试的外部资源清理。 */
    private boolean cleanupPending;

    /** 待重试的外部资源类型列表。 */
    private List<String> pendingItems;
}
