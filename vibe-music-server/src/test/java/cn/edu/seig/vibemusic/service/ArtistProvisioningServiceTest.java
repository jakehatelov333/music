package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.model.dto.ArtistAddDTO;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.result.Result;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传链路里「歌手名 → 歌手 id」的解析与按需创建（方案 A：确认后创建）。
 *
 * <p>这份测试钉住的是两件曾经出错的事：
 * <ol>
 *     <li><b>多歌手不能被压成一位</b>：元数据 {@code 银临、Tacke竹桑} 必须拆成两个歌手位，
 *         而不是把整串当成一位歌手（历史上被直接建成过伪歌手记录）；</li>
 *     <li><b>库里没有的名字不能被静默丢弃</b>：要么出现在 {@code missingNames} 里等管理员确认，
 *         要么在同名歧义时阻断提交，绝不悄悄退化成「只有专辑主艺人」。</li>
 * </ol>
 *
 * <p>用内存库替身而不是 H2：这里的判定逻辑与 SQL 无关，只与「匹配到几条」有关，
 * 内存替身能把「0 条 / 1 条 / ≥2 条」三种情况表达得比夹具更直白。
 */
class ArtistProvisioningServiceTest {

    private final List<Artist> store = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(200);

    private IArtistService artistService;
    private ArtistProvisioningService service;

    @BeforeEach
    void setUp() {
        store.clear();
        sequence.set(200);

        // LambdaQueryWrapper 靠 TableInfo 把 Artist::getArtistName 映射成列名；
        // 该缓存在 Spring 启动时才建立，纯单测里必须手动初始化一次。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Artist.class);

        ArtistMapper artistMapper = mock(ArtistMapper.class);
        when(artistMapper.selectList(any())).thenAnswer(invocation -> matching(invocation.getArgument(0)));

        artistService = mock(IArtistService.class);
        when(artistService.addArtist(any(ArtistAddDTO.class))).thenAnswer(invocation -> {
            ArtistAddDTO dto = invocation.getArgument(0);
            boolean taken = store.stream()
                    .anyMatch(item -> item.getArtistName().equals(dto.getArtistName())
                            && ArtistStatusEnum.isActive(item.getStatus()));
            if (taken) {
                return Result.error("歌手已存在");
            }
            Artist artist = new Artist();
            artist.setArtistId(sequence.incrementAndGet());
            artist.setArtistName(dto.getArtistName());
            artist.setStatus(ArtistStatusEnum.ACTIVE.getStatus());
            store.add(artist);
            return Result.success("新增成功");
        });

        service = new ArtistProvisioningService(new ArtistNameParser(artistMapper), artistService);
    }

    // ------------------------------------------------------------------ 解析

    @Test
    void resolveSplitsCooperationIntoOrderedSlotsAndListsMissingNames() {
        store.add(artist(143, "银临"));

        ArtistProvisioningService.Resolution resolution = service.resolve("银临、Tacke竹桑");

        assertEquals(Arrays.asList(143L, null), resolution.slots().stream()
                .map(ArtistProvisioningService.ArtistSlot::artistId).toList());
        assertEquals(List.of("银临", "Tacke竹桑"), resolution.slots().stream()
                .map(ArtistProvisioningService.ArtistSlot::artistName).toList());
        assertEquals(List.of("Tacke竹桑"), resolution.missingNames());
        assertTrue(resolution.ambiguousNames().isEmpty());
        assertTrue(resolution.requiresConfirmation());
        assertFalse(resolution.isBlocked());
    }

    @Test
    void resolveKeepsExactMatchAsSingleArtistEvenWhenNameContainsComma() {
        store.add(artist(3, "接个吻，开一枪"));

        ArtistProvisioningService.Resolution resolution = service.resolve("接个吻，开一枪");

        assertEquals(1, resolution.slots().size());
        assertEquals(3L, resolution.slots().get(0).artistId());
        assertTrue(resolution.missingNames().isEmpty());
        assertFalse(resolution.requiresConfirmation());
    }

    @Test
    void resolveTreatsUnknownSingleNameAsCreatable() {
        ArtistProvisioningService.Resolution resolution = service.resolve("张三");

        assertEquals(1, resolution.slots().size());
        assertNull(resolution.slots().get(0).artistId());
        assertEquals(List.of("张三"), resolution.missingNames());
    }

    @Test
    void resolveTreatsDisabledArtistNameAsCreatable() {
        Artist disabled = artist(144, "旧名字");
        disabled.setStatus(ArtistStatusEnum.DISABLED.getStatus());
        store.add(disabled);

        ArtistProvisioningService.Resolution resolution = service.resolve("旧名字");

        // 停用（软删除）记录不算占用：名字可以重新启用，所以这里按「可创建」处理。
        assertEquals(List.of("旧名字"), resolution.missingNames());
        assertTrue(resolution.ambiguousNames().isEmpty());
    }

    @Test
    void resolveBlocksAmbiguousNamesInsteadOfGuessing() {
        store.add(artist(11, "同名"));
        store.add(artist(12, "同名"));

        ArtistProvisioningService.Resolution resolution = service.resolve("同名");

        assertEquals(List.of("同名"), resolution.ambiguousNames());
        assertTrue(resolution.missingNames().isEmpty());
        assertTrue(resolution.isBlocked());
        assertFalse(resolution.requiresConfirmation());
    }

    // ------------------------------------------------------------------ 创建

    @Test
    void createMissingReusesExistingArtistsAndCreatesOnlyTheRest() {
        store.add(artist(143, "银临"));

        Map<String, Long> created = service.createMissing(List.of("银临", "Tacke竹桑", "Tacke竹桑"));

        assertEquals(143L, created.get("银临"));
        assertNotNull(created.get("Tacke竹桑"));
        assertEquals(2, created.size(), "重复的名字只创建一次");
        verify(artistService, times(1)).addArtist(any(ArtistAddDTO.class));
    }

    @Test
    void resolveOrCreateIdCreatesTheMissingName() {
        Long id = service.resolveOrCreateId("新歌手");

        assertNotNull(id);
        assertEquals("新歌手", store.stream()
                .filter(item -> item.getArtistId().equals(id))
                .findFirst()
                .orElseThrow()
                .getArtistName());
    }

    @Test
    void resolveOrCreateIdRejectsAmbiguousNameWithoutCreating() {
        store.add(artist(11, "同名"));
        store.add(artist(12, "同名"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveOrCreateId("同名"));

        assertTrue(error.getMessage().contains("同名"));
        verify(artistService, never()).addArtist(any(ArtistAddDTO.class));
    }

    @Test
    void createMissingWithNothingToCreateDoesNotTouchTheDatabase() {
        assertTrue(service.createMissing(List.of()).isEmpty());
        assertTrue(service.createMissing(null).isEmpty());
        verify(artistService, never()).addArtist(any(ArtistAddDTO.class));
    }

    // ------------------------------------------------------------------ 替身

    /**
     * 用「查询条件里出现过的字符串」在内存库里做等值过滤，等价于真实 SQL 的
     * {@code WHERE name = ? AND status = ?}：出现 {@code ACTIVE} 参数时同时按状态过滤。
     *
     * <p>注意必须先取一次 SQL 片段：MyBatis-Plus 的参数是<b>懒注册</b>的，不生成 SQL
     * 时 {@code paramNameValuePairs} 还是空 map。
     */
    private List<Artist> matching(Object wrapperArgument) {
        if (!(wrapperArgument instanceof AbstractWrapper<?, ?, ?> wrapper)) {
            return List.of();
        }
        wrapper.getSqlSegment();
        Set<String> values = wrapper.getParamNameValuePairs().values().stream()
                .filter(String.class::isInstance)
                .map(String::valueOf)
                .collect(Collectors.toSet());
        boolean activeOnly = values.contains(ArtistStatusEnum.ACTIVE.getStatus());
        return store.stream()
                .filter(item -> values.contains(item.getArtistName()))
                .filter(item -> !activeOnly || ArtistStatusEnum.isActive(item.getStatus()))
                .toList();
    }

    private static Artist artist(long id, String name) {
        Artist artist = new Artist();
        artist.setArtistId(id);
        artist.setArtistName(name);
        artist.setStatus(ArtistStatusEnum.ACTIVE.getStatus());
        return artist;
    }
}
