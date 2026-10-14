package cn.edu.seig.vibemusic.model.vo;

/**
 * {@code POST /ai/memory/clear-auto} 的结果。
 *
 * @param profileCleared    是否删除了自动画像行（可能本就不存在）
 * @param summariesCleared  被清空滚动摘要的会话数
 */
public record ClearAutoResultVO(boolean profileCleared, int summariesCleared) {
}
