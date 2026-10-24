package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 费用估算。
 *
 * <p><b>这是估算，不是账单。</b>单价来自配置（{@code ai.cost.*}），
 * {@code configured=false} 时 {@code amount} 为 null —— 界面必须显示"未配置价格"，
 * 绝不能用 0 或猜测值填充。{@code priceVersion} 记录本次估算所用的价格配置版本，
 * 便于事后核对"这个数字是按哪套单价算出来的"。
 */
@Data
public class AiCostVO {

    private boolean configured;
    private BigDecimal amount;
    private String currency;
    private String priceVersion;
    /** 单一单价模式的输入单价；分档模式下见 {@code tiers}。 */
    private BigDecimal inputPricePerMillion;
    /** 单一单价模式的输出单价；分档模式下见 {@code tiers}。 */
    private BigDecimal outputPricePerMillion;
    /** 当前启用的价格档位，按输入 Token 上界升序。 */
    private java.util.List<TierInfo> tiers;
    /** 估算方式说明，例如「按单次请求输入 Token 分档」。 */
    private String tier;
    /** 实际成功计价的模型调用次数。 */
    private long pricedCalls;
    /** 因 Token 缺失或超出最高档而未计价的模型调用次数。 */
    private long unpricedCalls;
    /** 参与估算的 Token 数；Token 不可获取时为 0 并置 tokenAvailable=false。 */
    private boolean tokenAvailable;
    private String note;
    /** 官方价格页面，便于管理员复核。 */
    private String sourceUrl;
    /** 官方价格页面的最近更新时间。 */
    private String sourceUpdatedAt;

    @Data
    public static class TierInfo {
        private long minInputTokens;
        private long maxInputTokens;
        private BigDecimal inputPricePerMillion;
        private BigDecimal outputPricePerMillion;
    }
}
