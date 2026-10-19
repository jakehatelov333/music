package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.model.vo.AlbumSuggestVO;
import cn.edu.seig.vibemusic.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 专辑联想服务层的参数处理与状态兜底。
 *
 * <p>SQL 本身的行为由 {@code AlbumSuggestSqlTest} 用真实 H2 覆盖，这里只测
 * 「服务层不该让什么请求打到数据库」以及「状态归一化」两件事。
 */
class AlbumSuggestServiceTest {

    private AlbumMapper albumMapper;
    private AlbumServiceImpl service;

    @BeforeEach
    void setUp() {
        albumMapper = mock(AlbumMapper.class);
        service = new AlbumServiceImpl();
        ReflectionTestUtils.setField(service, "albumMapper", albumMapper);
        when(albumMapper.suggestAlbumsForAdmin(anyString(), any(), anyInt())).thenReturn(new ArrayList<>());
    }

    /** 空关键词不能查全库：上传页一打开就发一次「列出所有专辑」既浪费又慢。 */
    @Test
    @DisplayName("空关键词直接返回空列表，不打数据库")
    void blankKeywordSkipsDatabase() {
        for (String keyword : new String[]{null, "", "   "}) {
            Result<List<AlbumSuggestVO>> result = service.suggestAlbums(keyword, 1L, 10);
            assertEquals(0, result.getCode());
            assertTrue(result.getData().isEmpty());
        }
        verify(albumMapper, never()).suggestAlbumsForAdmin(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("关键词首尾空白被 trim 后再查询")
    void trimsKeyword() {
        service.suggestAlbums("  太阳  ", 1L, 10);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(albumMapper).suggestAlbumsForAdmin(keyword.capture(), eq(1L), anyInt());
        assertEquals("太阳", keyword.getValue());
    }

    /** limit 为空/非正数时用默认值 10，超过上限夹到 20。 */
    @Test
    @DisplayName("limit 默认 10，上限 20")
    void clampsLimit() {
        service.suggestAlbums("太阳", null, null);
        verify(albumMapper).suggestAlbumsForAdmin(anyString(), isNull(), eq(10));

        service.suggestAlbums("太阳", null, 0);
        verify(albumMapper, org.mockito.Mockito.times(2)).suggestAlbumsForAdmin(anyString(), isNull(), eq(10));

        service.suggestAlbums("太阳", null, -5);
        verify(albumMapper, org.mockito.Mockito.times(3)).suggestAlbumsForAdmin(anyString(), isNull(), eq(10));

        service.suggestAlbums("太阳", null, 999);
        verify(albumMapper).suggestAlbumsForAdmin(anyString(), isNull(), eq(20));

        service.suggestAlbums("太阳", null, 15);
        verify(albumMapper).suggestAlbumsForAdmin(anyString(), isNull(), eq(15));
    }

    /**
     * 历史行可能没有状态值（迁移前写入），必须按「启用」处理。
     *
     * <p>与全项目 {@code ArtistStatusEnum.isActive} 的口径一致：把 null 当停用会让
     * 老数据在管理端集体显示「已停用」，管理员会以为这些歌手出了什么问题。
     */
    @Test
    @DisplayName("状态为 null/空白的历史行按 ACTIVE 处理，不误标成停用")
    void normalizesMissingStatusToActive() {
        List<AlbumSuggestVO> rows = new ArrayList<>();
        rows.add(album(1L, "太阳之子", null));
        rows.add(album(2L, "我很忙", ""));
        rows.add(album(3L, "魔杰座", "DISABLED"));
        rows.add(album(4L, "七里香", "active"));
        when(albumMapper.suggestAlbumsForAdmin(anyString(), any(), anyInt())).thenReturn(rows);

        Result<List<AlbumSuggestVO>> result = service.suggestAlbums("x", null, 10);

        assertEquals("ACTIVE", result.getData().get(0).getArtistStatus(), "null 应按启用处理");
        assertEquals("ACTIVE", result.getData().get(1).getArtistStatus(), "空白应按启用处理");
        assertEquals("DISABLED", result.getData().get(2).getArtistStatus());
        assertEquals("ACTIVE", result.getData().get(3).getArtistStatus(), "小写应被归一化");
    }

    private static AlbumSuggestVO album(Long id, String name, String artistStatus) {
        AlbumSuggestVO vo = new AlbumSuggestVO();
        vo.setAlbumId(id);
        vo.setAlbumName(name);
        vo.setArtistStatus(artistStatus);
        return vo;
    }
}
