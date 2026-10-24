package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 费用估算的单个分档规则。
 *
 * <p>区间语义与阿里云百炼一致：{@code minInputTokens < 输入 Token <= maxInputTokens}。
 * 这是服务端由配置生成并传给 Mapper 的只读参数，不来自请求体。
 */
@Data
public class AiCostTierRule {

    private int tierIndex;
    private long minInputTokens;
    private long maxInputTokens;
    private BigDecimal inputPricePerMillion;
    private BigDecimal outputPricePerMillion;
}
