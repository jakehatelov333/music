package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.ArtistAddDTO;
import cn.edu.seig.vibemusic.model.vo.ArtistParsingVO;
import cn.edu.seig.vibemusic.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 上传链路里「歌手名 → 歌手 id」的<b>唯一写入口</b>：解析 + 按需创建。
 *
 * <p><b>与 {@link ArtistNameParser} 的分工</b>：解析器只读、绝不写库（只回答「这段文本是几位歌手」）；
 * 本类才允许写库（把库里不存在的名字建成歌手）。两者共用同一套分隔符与匹配规则，
 * 杜绝「解析时按一套规则、创建时按另一套规则」的漂移。
 *
 * <p><b>为什么要「先列清单、再创建」</b>：专辑上传的歌手名来自音频标签，拼写错误很常见。
 * 无脑创建会污染歌手表（历史教训：元数据 {@code 银临/Aki阿杰} 被直接建成一条伪歌手记录）；
 * 直接拒绝又会让整张专辑上传失败。所以本类只负责<b>算出</b>「库里没有的名字」，
 * 由上层先返回给管理员确认，确认后才调用 {@link #createMissing} 落库。
 *
 * <p>本类<b>不开启事务</b>：调用方（上传提交）已处于事务中，创建歌手应当与歌曲入库同生共死。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtistProvisioningService {

    private final ArtistNameParser artistNameParser;
    private final IArtistService artistService;

    /**
     * 一个歌手位。
     *
     * @param artistId   库里已有的歌手 id；为 {@code null} 表示待创建
     * @param artistName 待创建时的名字（已匹配时可为 {@code null}）
     */
    public record ArtistSlot(Long artistId, String artistName) {
    }

    /**
     * 一段歌手文本的解析结论。
     *
     * @param slots          有序歌手位，顺序即展示顺序（第一个为主艺人）
     * @param missingNames   库中不存在、确认后可创建的名字（去重、保序）
     * @param ambiguousNames 同名存在多条启用记录的名字：既不能自动匹配，也不能创建
     */
    public record Resolution(List<ArtistSlot> slots, List<String> missingNames, List<String> ambiguousNames) {

        /** 是否需要管理员确认（存在待创建的歌手）。 */
        public boolean requiresConfirmation() {
            return !missingNames.isEmpty();
        }

        /** 是否必须阻断提交（存在同名歧义，必须人工到歌手管理处理）。 */
        public boolean isBlocked() {
            return !ambiguousNames.isEmpty();
        }
    }

    /**
     * 只读解析：把一段歌手文本拆成「已匹配 id」+「可创建」+「同名歧义」。<b>不写库。</b>
     */
    public Resolution resolve(String rawName) {
        List<ArtistSlot> slots = new ArrayList<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        LinkedHashSet<String> ambiguous = new LinkedHashSet<>();

        ArtistParsingVO parsing = artistNameParser.parse(rawName);
        for (ArtistParsingVO.Candidate candidate : safeCandidates(parsing)) {
            if (candidate.isMatched() && candidate.getArtistId() != null) {
                slots.add(new ArtistSlot(candidate.getArtistId(), candidate.getArtistName()));
                continue;
            }
            String name = candidate.getArtistName() == null ? null : candidate.getArtistName().trim();
            if (!StringUtils.hasText(name)) {
                continue;
            }
            List<Long> hits = artistNameParser.findActiveExactIds(name);
            if (hits.size() == 1) {
                // 解析器判为未匹配、这里却唯一命中：并发新增了同名歌手，按「已存在」处理即可。
                slots.add(new ArtistSlot(hits.get(0), name));
            } else if (hits.isEmpty()) {
                missing.add(name);
                slots.add(new ArtistSlot(null, name));
            } else {
                ambiguous.add(name);
                slots.add(new ArtistSlot(null, name));
            }
        }
        return new Resolution(List.copyOf(slots), List.copyOf(missing), List.copyOf(ambiguous));
    }

    /**
     * 批量创建缺失歌手，返回 {@code name → artistId}。
     *
     * <p>创建前会再查一次：已存在（唯一命中）的直接复用而不重复建；同名歧义抛异常。
     * 任一步失败都抛异常，由调用方事务整体回滚，不会留下「建了歌手却没入库歌曲」的残留。
     */
    public Map<String, Long> createMissing(Collection<String> names) {
        Map<String, Long> created = new LinkedHashMap<>();
        if (names == null || names.isEmpty()) {
            return created;
        }
        for (String raw : names) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String name = raw.trim();
            if (created.containsKey(name)) {
                continue;
            }
            created.put(name, resolveOrCreateId(name));
        }
        return created;
    }

    /**
     * 单个名字：库里唯一命中直接返回 id；不存在则创建；同名歧义或创建失败抛异常。
     */
    public Long resolveOrCreateId(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            throw new IllegalArgumentException("歌手名不能为空");
        }
        String name = rawName.trim();
        List<Long> hits = artistNameParser.findActiveExactIds(name);
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (hits.size() > 1) {
            throw new IllegalStateException("歌手「" + name + "」存在同名多条记录，请先到歌手管理处理");
        }
        return createOne(name);
    }

    private Long createOne(String name) {
        ArtistAddDTO addDTO = new ArtistAddDTO();
        addDTO.setArtistName(name);
        Result<?> addResult = artistService.addArtist(addDTO);
        List<Long> hits = artistNameParser.findActiveExactIds(name);
        if (hits.size() == 1) {
            // 正常路径，以及「并发下已被别人抢先建好」的竞态路径，都收敛到这里。
            if (addResult.getCode() != 0) {
                log.info("歌手「{}」在提交过程中已被其他请求创建，直接复用", name);
            }
            return hits.get(0);
        }
        if (hits.size() > 1) {
            throw new IllegalStateException("歌手「" + name + "」存在同名多条记录，请先到歌手管理处理");
        }
        throw new IllegalStateException("歌手「" + name + "」创建失败："
                + (StringUtils.hasText(addResult.getMessage()) ? addResult.getMessage() : "未知原因"));
    }

    private static List<ArtistParsingVO.Candidate> safeCandidates(ArtistParsingVO parsing) {
        return parsing == null || parsing.getCandidates() == null ? List.of() : parsing.getCandidates();
    }
}
