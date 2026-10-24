package cn.edu.seig.vibemusic.service.agent.trace;

import cn.edu.seig.vibemusic.model.dto.AiCostTierRule;
import cn.edu.seig.vibemusic.model.vo.AiCostVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 费用估算。
 *
 * <p><b>这是估算，不是供应商账单。</b>价格必须显式配置；未配置时返回
 * {@code configured=false, amount=null}，界面显示"未配置价格"——
 * <b>绝不用 0 或猜测值填充</b>，那会把"不知道"伪装成"免费"。
 *
 * <p><b>为什么支持分档：</b>qwen-flash 的单价取决于<b>单次请求的输入 Token 总量</b>，
 * 命中某档后该次请求的输入、输出 Token 都按这一档结算。只配一个均价会把 128K
 * 以上的请求算少，而且总览无法解释这个偏差。因此这里按每次模型调用选择档位，
 * 再逐次累加；旧版单一单价仍保留为兼容兜底。
 *
 * <p>价格只覆盖标准实时推理原价，不含 Batch 半价、上下文缓存折扣、免费额度或活动优惠。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.cost")
public class AiCostCalculator {

    private String currency = "CNY";
    private String priceVersion;
    private String sourceUrl;
    private String sourceUpdatedAt;

    /**
     * 旧版单一单价。仅在 {@link #tiers} 为空时生效，避免已有部署升级后突然变成未配置。
     */
    private BigDecimal inputPricePerMillion;
    private BigDecimal outputPricePerMillion;

    /**
     * 按单次请求输入 Token 分档。数组按 max-input-tokens 升序匹配，所有边界均为闭区间。
     */
    private List<Tier> tiers = new ArrayList<>();

    /**
     * 模型调用用量。{@code calls} 表示这些 Token 汇总值覆盖的调用次数；
     * 单次调用使用 {@link #one(Long, Long, boolean)}。
     */
    public record Usage(long calls, Long inputTokens, Long outputTokens, boolean tokenAvailable) {
        public static Usage one(Long inputTokens, Long outputTokens, boolean tokenAvailable) {
            return new Usage(1, inputTokens, outputTokens, tokenAvailable);
        }
    }

    /**
     * 已由 SQL 按单次调用选好档位并聚合的用量行。
     *
     * <p>{@code inputTokens} / {@code outputTokens} 已经是该档所有调用的 Token 总和，
     * {@code calls} 仅用于统计调用次数，不能再拿来乘费用。
     */
    public record TierUsage(int tierIndex, long calls, Long inputTokens, Long outputTokens,
                            boolean tokenAvailable) {
    }

    @Data
    public static class Tier {
        private Long maxInputTokens;
        private BigDecimal inputPricePerMillion;
        private BigDecimal outputPricePerMillion;
    }

    /** 单次模型调用的费用估算。 */
    public AiCostVO estimate(Long inputTokens, Long outputTokens, boolean tokenAvailable) {
        return estimateUsages(List.of(Usage.one(inputTokens, outputTokens, tokenAvailable)));
    }

    /**
     * 多次模型调用的费用估算。
     *
     * <p>每次调用独立选档，不能先合并 Token 再选档：那样会把多次小请求错误地
     * 推到更高档位，与供应商按单次请求计费的规则不一致。
     */
    public AiCostVO estimateUsages(List<Usage> usages) {
        List<Usage> safeUsages = usages == null ? List.of() : usages;
        List<TierUsage> groups = new ArrayList<>();
        for (Usage usage : safeUsages) {
            if (usage == null || usage.calls() <= 0) continue;
            int tierIndex = usage.tokenAvailable() && usage.inputTokens() != null
                    ? tierIndexFor(usage.inputTokens()) : -1;
            groups.add(new TierUsage(tierIndex, usage.calls(), usage.inputTokens(),
                    usage.outputTokens(), usage.tokenAvailable()));
        }
        return estimateGroups(groups);
    }

    /**
     * 估算已经按单次调用分好档的聚合结果。
     *
     * <p>{@code tierIndex < 0} 表示无法计价：Token 不可获取，或输入超过已配置最高档。
     * 这些调用只增加 {@code unpricedCalls}，绝不按 0 元计入。
     */
    public AiCostVO estimateGroups(List<TierUsage> groups) {
        AiCostVO cost = baseCost();
        List<AiCostTierRule> rules = rules();
        if (rules.isEmpty()) {
            cost.setConfigured(false);
            cost.setAmount(null);
            cost.setNote("未配置模型单价（ai.cost.*），不提供费用估算");
            return cost;
        }

        List<TierUsage> safeGroups = groups == null ? List.of() : groups;
        long pricedCalls = 0;
        long unavailableCalls = 0;
        long overLimitCalls = 0;
        BigDecimal amount = BigDecimal.ZERO;

        for (TierUsage group : safeGroups) {
            if (group == null || group.calls() <= 0) continue;
            if (!group.tokenAvailable() || group.inputTokens() == null) {
                unavailableCalls += group.calls();
                continue;
            }
            AiCostTierRule rule = ruleAt(rules, group.tierIndex());
            if (rule == null) {
                overLimitCalls += group.calls();
                continue;
            }
            // group 中的 Token 已由 SQL 按档汇总，这里只计算一次；calls 仅用于计数。
            amount = amount.add(callAmount(rule, group.inputTokens(), group.outputTokens()));
            pricedCalls += group.calls();
        }

        cost.setConfigured(true);
        cost.setPricedCalls(pricedCalls);
        cost.setUnpricedCalls(unavailableCalls + overLimitCalls);
        cost.setTokenAvailable(pricedCalls > 0 || unavailableCalls == 0);

        if (pricedCalls == 0) {
            cost.setAmount(null);
            cost.setNote(noPricedCallNote(unavailableCalls, overLimitCalls));
            return cost;
        }

        cost.setAmount(amount.setScale(6, RoundingMode.HALF_UP));
        cost.setTier(rules.size() == 1 && rules.get(0).getMaxInputTokens() == Long.MAX_VALUE
                ? "单一配置单价" : "按单次请求输入 Token 分档");
        cost.setNote(note(unavailableCalls, overLimitCalls));
        return cost;
    }

    /** 单次输入 Token 命中的档位序号；没有命中时返回 -1。 */
    public int tierIndexFor(long inputTokens) {
        AiCostTierRule rule = match(rules(), inputTokens);
        return rule == null ? -1 : rule.getTierIndex();
    }

    /**
     * 转成 Mapper 可用的规则列表。规则只由服务端配置生成，不接收任何前端输入。
     */
    public List<AiCostTierRule> rules() {
        List<Tier> valid = tiers == null ? List.of() : tiers.stream()
                .filter(tier -> tier != null && tier.getMaxInputTokens() != null
                        && tier.getMaxInputTokens() > 0
                        && tier.getInputPricePerMillion() != null
                        && tier.getOutputPricePerMillion() != null)
                .sorted(Comparator.comparing(Tier::getMaxInputTokens))
                .toList();

        List<AiCostTierRule> rules = new ArrayList<>();
        if (!valid.isEmpty()) {
            long previousMax = -1;
            for (int i = 0; i < valid.size(); i++) {
                Tier tier = valid.get(i);
                // 重复或倒序边界无法表达供应商的档位；排序后仍重复时只保留第一档。
                if (tier.getMaxInputTokens() <= previousMax) continue;
                rules.add(rule(i, previousMax, tier.getMaxInputTokens(),
                        tier.getInputPricePerMillion(), tier.getOutputPricePerMillion()));
                previousMax = tier.getMaxInputTokens();
            }
            return rules;
        }

        if (inputPricePerMillion != null || outputPricePerMillion != null) {
            BigDecimal input = inputPricePerMillion == null ? BigDecimal.ZERO : inputPricePerMillion;
            BigDecimal output = outputPricePerMillion == null ? BigDecimal.ZERO : outputPricePerMillion;
            return List.of(rule(0, -1, Long.MAX_VALUE, input, output));
        }
        return List.of();
    }

    private AiCostVO baseCost() {
        AiCostVO cost = new AiCostVO();
        cost.setCurrency(currency);
        cost.setPriceVersion(priceVersion == null || priceVersion.isBlank()
                ? defaultPriceVersion() : priceVersion);
        cost.setSourceUrl(sourceUrl);
        cost.setSourceUpdatedAt(sourceUpdatedAt);
        List<AiCostTierRule> rules = rules();
        cost.setTiers(rules.stream().map(rule -> {
            AiCostVO.TierInfo tier = new AiCostVO.TierInfo();
            tier.setMinInputTokens(rule.getMinInputTokens());
            tier.setMaxInputTokens(rule.getMaxInputTokens());
            tier.setInputPricePerMillion(rule.getInputPricePerMillion());
            tier.setOutputPricePerMillion(rule.getOutputPricePerMillion());
            return tier;
        }).toList());
        if (rules.size() == 1 && rules.get(0).getMaxInputTokens() == Long.MAX_VALUE) {
            cost.setInputPricePerMillion(rules.get(0).getInputPricePerMillion());
            cost.setOutputPricePerMillion(rules.get(0).getOutputPricePerMillion());
        }
        return cost;
    }

    private String defaultPriceVersion() {
        List<AiCostTierRule> rules = rules();
        if (rules.isEmpty()) return null;
        StringBuilder value = new StringBuilder();
        for (AiCostTierRule rule : rules) {
            if (!value.isEmpty()) value.append('|');
            value.append(rule.getMaxInputTokens()).append(':')
                    .append(rule.getInputPricePerMillion()).append('/')
                    .append(rule.getOutputPricePerMillion());
        }
        return value.toString();
    }

    private static AiCostTierRule rule(int index, long minInputTokens, long maxInputTokens,
                                       BigDecimal inputPrice, BigDecimal outputPrice) {
        AiCostTierRule rule = new AiCostTierRule();
        rule.setTierIndex(index);
        rule.setMinInputTokens(minInputTokens);
        rule.setMaxInputTokens(maxInputTokens);
        rule.setInputPricePerMillion(inputPrice);
        rule.setOutputPricePerMillion(outputPrice);
        return rule;
    }

    private static AiCostTierRule match(List<AiCostTierRule> rules, long inputTokens) {
        for (AiCostTierRule rule : rules) {
            if (inputTokens > rule.getMinInputTokens() && inputTokens <= rule.getMaxInputTokens()) {
                return rule;
            }
        }
        return null;
    }

    private static AiCostTierRule ruleAt(List<AiCostTierRule> rules, int tierIndex) {
        if (tierIndex < 0) return null;
        for (AiCostTierRule rule : rules) {
            if (rule.getTierIndex() == tierIndex) return rule;
        }
        return null;
    }

    private static BigDecimal callAmount(AiCostTierRule rule, Long inputTokens, Long outputTokens) {
        BigDecimal amount = BigDecimal.ZERO;
        if (inputTokens != null) {
            amount = amount.add(rule.getInputPricePerMillion()
                    .multiply(BigDecimal.valueOf(inputTokens))
                    .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP));
        }
        if (outputTokens != null) {
            amount = amount.add(rule.getOutputPricePerMillion()
                    .multiply(BigDecimal.valueOf(outputTokens))
                    .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP));
        }
        return amount;
    }

    private static String noPricedCallNote(long unavailableCalls, long overLimitCalls) {
        if (unavailableCalls > 0 && overLimitCalls > 0) {
            return "Token 不可获取，且有调用超出已配置最高档，无法估算费用";
        }
        if (unavailableCalls > 0) return "Token 用量不可获取，无法估算费用";
        if (overLimitCalls > 0) return "输入 Token 超出已配置最高档，未估算费用";
        return "没有可估算的模型调用";
    }

    private static String note(long unavailableCalls, long overLimitCalls) {
        StringBuilder note = new StringBuilder("按配置的 qwen-flash 标准实时原价分档估算，非供应商账单");
        if (unavailableCalls > 0) {
            note.append("；").append(unavailableCalls).append(" 次调用缺少供应商 Token，未计入");
        }
        if (overLimitCalls > 0) {
            note.append("；").append(overLimitCalls).append(" 次调用超出最高档，未计入");
        }
        return note.toString();
    }
}
