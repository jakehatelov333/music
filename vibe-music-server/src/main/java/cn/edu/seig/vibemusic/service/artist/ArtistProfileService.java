package cn.edu.seig.vibemusic.service.artist;

import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.vo.ArtistCandidateVO;
import cn.edu.seig.vibemusic.model.vo.ArtistProfileVO;
import cn.edu.seig.vibemusic.service.cover.CoverDownloader;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
/**
 * 歌手资料检索与自动填充。
 *
 * <h3>职责边界</h3>
 * 这里只做「查」和「转换」，<b>不写库</b>。填充后的结果由管理员在前端确认、可逐项修改，
 * 再走已有的新增歌手接口落库。这样"自动填充"永远不会产生没人看过的数据。
 *
 * <h3>为什么搜索与详情分两个接口</h3>
 * 实测响应体积差距很大：一次搜索（8 条候选）约 1.5 KB，只含名字和头像；
 * 而一位歌手的完整详情（含 wiki 全文）约 12–30 KB，周杰伦那条单是 wiki 就 22350 字符。
 * 合成一个接口的话，每次搜索都要为 8 个候选各拉一次详情，纯属浪费。
 */
@Slf4j
@Service
public class ArtistProfileService {

    /** 单次搜索最多返回多少个候选。实测一个关键词通常只有 1–2 位歌手，8 已很宽松。 */
    public static final int MAX_CANDIDATES = 8;

    @Autowired
    private QqArtistCatalog catalog;
    @Autowired
    private ArtistCandidateRanker ranker;
    @Autowired
    private ArtistMapper artistMapper;
    @Autowired
    private CoverDownloader downloader;

    /**
     * 搜索歌手候选。
     *
     * <p>两段式：先按原样搜；原样搜不到且名字带尾部括号时，去掉括号再搜一次。
     * 实测这一步能把命中率从 122/129 提到 126/129 —— 库里 {@code IU (아이유)}、
     * {@code 孝敏 (효민)}、{@code 智妍 (지연)}、{@code DAISHI DANCE (ダイシ・ダンス)}
     * 都是靠它才搜到的。
     *
     * @param keyword 关键词；空白时返回空列表
     * @return 按匹配分降序的候选；未启用或搜不到时返回空列表
     */
    public List<ArtistCandidateVO> search(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        String trimmed = keyword.trim();
        List<QqArtistHit> hits = catalog.search(trimmed, MAX_CANDIDATES);
        if (hits.isEmpty() && ranker.hasTrailingBracket(trimmed)) {
            String stripped = ranker.stripTrailingBracket(trimmed);
            if (StringUtils.hasText(stripped)) {
                log.debug("歌手原名无结果，改用去括号后的名字重试：{} -> {}", trimmed, stripped);
                hits = catalog.search(stripped, MAX_CANDIDATES);
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        // 一次性查库里已存在的同名歌手，避免每个候选各查一次
        Map<String, Long> existing = loadExistingArtists(hits);

        List<ArtistCandidateVO> result = new ArrayList<>();
        for (ArtistCandidateRanker.Ranked<QqArtistHit> ranked : ranker.rank(trimmed, hits, QqArtistHit::name)) {
            QqArtistHit hit = ranked.hit();
            ArtistCandidateVO vo = new ArtistCandidateVO();
            vo.setSingerMid(hit.singerMid());
            vo.setArtistName(hit.name());
            vo.setAvatarUrls(hit.avatarUrls());
            vo.setScore(ranked.score());
            vo.setExactMatch(ranker.isSafeToPreselect(trimmed, hit.name()));
            Long existingId = existing.get(ranker.normalize(hit.name()));
            vo.setExistingInLibrary(existingId != null);
            vo.setExistingArtistId(existingId);
            result.add(vo);
        }
        return result;
    }

    /**
     * 取某位歌手的完整资料。
     *
     * @param singerMid QQ 音乐歌手 mid
     * @return 资料；mid 不合法、未启用或对方无此歌手时返回 {@code null}
     */
    public ArtistProfileVO detail(String singerMid) {
        QqArtistProfile profile = catalog.detail(singerMid);
        if (profile == null) {
            return null;
        }
        ArtistProfileVO vo = new ArtistProfileVO();
        vo.setSingerMid(profile.singerMid());
        vo.setArtistName(profile.name());
        vo.setGender(profile.gender());
        vo.setBirth(profile.birth());
        vo.setArea(profile.area());
        vo.setIntroduction(profile.introduction());
        vo.setForeignName(profile.foreignName());
        vo.setAvatarUrls(profile.avatarUrls());
        vo.setHasAvatar(profile.hasPhoto());
        vo.setExtraFields(profile.wikiFields() == null ? Map.of() : profile.wikiFields());
        vo.setFilledFields(filledFields(profile));

        Long existingId = findExistingArtistId(profile.name());
        vo.setExistingInLibrary(existingId != null);
        vo.setExistingArtistId(existingId);
        return vo;
    }

    /**
     * 下载头像并返回字节，供新增歌手时写入 MinIO。
     *
     * <p>复用 {@link CoverDownloader}：它的流式截断（超限即停）、Content-Type 校验、
     * 404 与超限的区分都是现成的，而且歌手头像与专辑封面同属一个 CDN、
     * 体积量级也接近（800px 实测 96–183 KB，封面是 171 KB）。
     *
     * @param singerMid mid
     * @param size      尺寸，会被夹到 300/500/800
     * @param seq       头像序号，可为 {@code null}
     * @return 下载结果；失败时 {@code error()} 有可读原因
     */
    public CoverDownloader.Result downloadAvatar(String singerMid, Integer size, String seq) {
        if (!QqArtistAvatarUrl.isValidSingerMid(singerMid)) {
            return new CoverDownloader.Result(null, null, "歌手标识不合法");
        }
        String url = QqArtistAvatarUrl.of(singerMid, QqArtistAvatarUrl.normalizeSize(size), seq);
        CoverDownloader.Result result = downloader.download(url);
        if (!result.success()) {
            // 把"该专辑没有封面"换成歌手的说法，否则管理员看到的是文不对题的提示
            String error = result.error() == null ? "头像下载失败" : result.error().replace("专辑", "歌手");
            return new CoverDownloader.Result(null, null, error);
        }
        return result;
    }

    /** 资料里实际有值的业务字段名，前端据此提示"填充了哪几项"。 */
    private List<String> filledFields(QqArtistProfile profile) {
        List<String> filled = new ArrayList<>();
        if (profile.gender() != null) {
            filled.add("gender");
        }
        if (profile.birth() != null) {
            filled.add("birth");
        }
        if (StringUtils.hasText(profile.area())) {
            filled.add("area");
        }
        if (StringUtils.hasText(profile.introduction())) {
            filled.add("introduction");
        }
        // 只有对方确实有照片才算"填充了头像"：地址能拼出来不代表图存在
        if (profile.hasPhoto()) {
            filled.add("avatar");
        }
        return filled;
    }

    /**
     * 批量查「这些候选里哪些已经在库里」。
     *
     * <p>一次查出全部启用中的歌手再在内存里比对：库里目前 130 位，
     * 一条 {@code SELECT id, name} 远比分候选逐个查更快，也不会因为
     * 归一化规则（去括号/去标点）在 SQL 里表达不出来而漏判。
     *
     * <p>刻意<b>不用</b> {@code QueryWrapper.select("id","name")}：那样查出来的实体
     * 只有这两个字段有值，其余字段为 null，而 MyBatis-Plus 在某些配置下会把
     * 结果集里的空行映射成 null 元素。用 {@code selectObjs} 只取名字反而更稳 ——
     * 这里确实只需要名字。
     */
    private Map<String, Long> loadExistingArtists(List<QqArtistHit> hits) {
        if (hits.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> byNormalized = loadActiveArtistNames();
        Map<String, Long> matched = new LinkedHashMap<>();
        for (QqArtistHit hit : hits) {
            String normalized = ranker.normalize(hit.name());
            Long id = byNormalized.get(normalized);
            if (id != null) {
                matched.put(normalized, id);
            }
        }
        return matched;
    }

    /**
     * 读出「归一化名字 → 歌手 id」的映射。
     *
     * <p>用 {@code selectList} 取完整实体而不是只 select 两列：本项目其余代码
     * 都是这么用的，字段映射行为已经验证过；为了省一点带宽去裁剪列，
     * 换来的是一类只在特定 MyBatis-Plus 版本下出现的空指针，不值得。
     */
    private Map<String, Long> loadActiveArtistNames() {
        List<Artist> artists = artistMapper.selectList(new QueryWrapper<Artist>()
                .eq("status", "ACTIVE"));
        Map<String, Long> byNormalized = new LinkedHashMap<>();
        for (Artist artist : artists) {
            if (artist == null || !StringUtils.hasText(artist.getArtistName())) {
                continue;
            }
            String key = ranker.normalize(artist.getArtistName());
            Long id = artist.getArtistId();
            if (key.isEmpty() || id == null) {
                continue;
            }
            // 归一化后撞名（如「周杰伦」与「周杰伦 」）时保留 id 较小的那条，
            // 保证同一份数据每次给出同一个答案
            byNormalized.merge(key, id, Math::min);
        }
        return byNormalized;
    }

    /** 按归一化名字查库里是否已有该歌手。 */
    private Long findExistingArtistId(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        return loadActiveArtistNames().get(ranker.normalize(name));
    }
}
