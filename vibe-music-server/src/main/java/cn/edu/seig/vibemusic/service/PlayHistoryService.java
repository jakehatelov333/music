package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.mapper.PlayHistoryMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.dto.PlayHistoryQueryDTO;
import cn.edu.seig.vibemusic.model.dto.PlayHistoryRecordDTO;
import cn.edu.seig.vibemusic.model.vo.PlayHistoryVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 播放历史（「最近播放」）。
 *
 * <p><b>身份只有一个来源：</b>所有方法内部都走
 * {@link PlaylistAccessService#requireUserId()}，即从 JWT + ThreadLocal 拿当前登录用户，
 * 游客直接 401。<b>没有任何方法接受 userId 参数</b> —— 这是「不能读写别人历史」的结构性保证，
 * 而不是靠调用方记得过滤。
 *
 * <p><b>游客策略：</b>访客不写库。播放器仍可正常播放，只是不上报；
 * 访客的「播放列表」是浏览器 localStorage 里的队列（原有行为，未改动）。
 * 因此同一台机器换人使用不会出现历史混在一起。
 *
 * <p><b>有效播放的判定在前端</b>（{@code utils/playHistory.ts}）：必须真正播到阈值才算一次。
 * 本服务不重复实现音频判定，只保证写入的原子性、去重与边界校验。
 * 这样「页面刚加载 / 误点击 / 播放器初始化」都不会产生记录。
 *
 * <p><b>删除歌曲后的历史：</b>{@code tb_play_history.song_id} 是
 * {@code ON DELETE CASCADE}，歌曲删除时历史一并消失；读查询仍然 {@code INNER JOIN tb_song}，
 * 双保险确保「已删除歌曲的历史」永远不会返回给前端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayHistoryService {

    /** 「最近播放」接口一次最多返回多少条，同时也是 limit 参数上限。 */
    private static final int RECENT_MAX = 50;

    private final PlayHistoryMapper playHistories;
    private final SongMapper songs;
    private final SongArtistService songArtists;

    /**
     * 记录一次有效播放。同一首歌重复播放只更新同一行（唯一键 + 累加）。
     *
     * <p><b>为什么不能用 affected-rows 判断「是否首次插入」：</b>
     * MySQL Connector/J 默认是 found-rows 语义（{@code useAffectedRows=false}），
     * {@code INSERT ... ON DUPLICATE KEY UPDATE id = id} 在「命中但没改动」时返回 1，
     * 与真正插入无法区分 —— 生产库上会导致计数永远不累加，而 H2 返回 0 让测试全绿。
     * 这个 bug 已经在真实 MySQL 上复现过，因此这里改成：
     * <ol>
     *     <li>先裸 INSERT，靠唯一键冲突（{@link DuplicateKeyException}）识别「已有行」；</li>
     *     <li>冲突时走带幂等条件的 UPDATE 累加；</li>
     *     <li>UPDATE 返回 0 时再用 {@code exists} 区分「被幂等键拦下」与「行已不存在」。</li>
     * </ol>
     *
     * @return true 表示本次真的累加了播放次数；false 表示被幂等键拦下（重复请求）
     */
    @Transactional
    public boolean recordPlay(PlayHistoryRecordDTO input) {
        Long userId = PlaylistAccessService.requireUserId();
        if (input == null || input.getSongId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "歌曲 id 不能为空");
        }
        Long songId = input.getSongId();
        if (songId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "歌曲 id 不合法");
        }
        // 歌曲不存在直接 404：不在历史表里留下指向空气的行。
        // 注意这里不能只靠 FK —— 报错会是 500/409，语义远不如明确的 404。
        if (songs.selectById(songId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "歌曲不存在");
        }
        String playId = normalizePlayId(input.getPlayId());
        Integer position = input.getPositionSeconds();
        LocalDateTime now = LocalDateTime.now();
        try {
            playHistories.insertNew(userId, songId, now, position, playId);
            return true;
        } catch (DuplicateKeyException alreadyRecorded) {
            // 已有这首歌的历史行（或并发请求抢先插入了）——走累加分支。
            return accumulate(userId, songId, now, position, playId);
        }
    }

    /**
     * 累加已有行的播放次数。
     *
     * <p>并发下可能有两个请求同时进入这里（一方插入、另一方也判定为冲突），
     * 因此 UPDATE 仍要带幂等条件；返回 0 时再确认行是否真的存在。
     */
    private boolean accumulate(Long userId, Long songId, LocalDateTime now,
                               Integer position, String playId) {
        if (playHistories.incrementPlay(userId, songId, now, position, playId) == 1) {
            return true;
        }
        // 没更新到：要么是同一 playId 的重复上报（正常幂等），
        // 要么行在两次语句之间被删掉了（删歌 / 清空历史）。后者重试一次插入。
        if (playHistories.exists(userId, songId) == 0) {
            try {
                playHistories.insertNew(userId, songId, now, position, playId);
                return true;
            } catch (DuplicateKeyException raced) {
                // 并发插入，视作已记录（另一个请求已经写好了同一行）
                return false;
            }
        }
        return false;
    }

    /**
     * 分页查询当前用户的播放历史。
     *
     * <p>空结果返回 {@code total=0, items=[]} 而不是 null —— 前端据此展示空状态，
     * 不需要区分「没有历史」和「接口没返回」。
     */
    public PageResult<PlayHistoryVO> page(PlayHistoryQueryDTO query) {
        Long userId = PlaylistAccessService.requireUserId();
        if (query == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求内容不能为空");
        }
        int pageNum = query.getPageNum() == null ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null ? 20 : query.getPageSize();
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数无效");
        }
        String songName = blankToNull(query.getSongName());
        IPage<PlayHistoryVO> result = playHistories.pageByUser(
                new Page<>(pageNum, pageSize), userId, songName);
        List<PlayHistoryVO> records = result.getRecords();
        // 结构化歌手走独立批量查询装配，主查询保持零额外 JOIN（分页 COUNT 不受影响）。
        songArtists.attachArtists(records);
        return new PageResult<>(result.getTotal(), records);
    }

    /** 最近播放的歌曲（「刚刚听过」）。limit 越界一律夹到合法区间，不报错。 */
    public List<PlayHistoryVO> recent(Integer limit) {
        Long userId = PlaylistAccessService.requireUserId();
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, RECENT_MAX);
        List<PlayHistoryVO> records = playHistories.recentByUser(userId, size);
        songArtists.attachArtists(records);
        return records;
    }

    /**
     * 删除当前用户的一条历史记录。
     *
     * <p>「记录不存在」与「记录不属于你」统一 404：如果前者 404、后者 403，
     * 就等于提供了一个「这个 id 是否存在」的探测接口。
     */
    @Transactional
    public void delete(Long historyId) {
        Long userId = PlaylistAccessService.requireUserId();
        long id = PlaylistAccessService.positiveId(historyId);
        if (playHistories.deleteOwned(userId, id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "播放记录不存在");
        }
    }

    /**
     * 清空当前用户的全部播放历史。
     *
     * @return 实际删除的条数（前端提示「已清空 N 条」）
     */
    @Transactional
    public long clear() {
        Long userId = PlaylistAccessService.requireUserId();
        return playHistories.clearByUser(userId);
    }

    /** 幂等键规范化：空串按 null 处理（等价于「客户端不需要幂等」），超长截断到列宽。 */
    private static String normalizePlayId(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
