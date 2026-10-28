package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.mapper.FeedbackMapper;
import cn.edu.seig.vibemusic.model.dto.FeedbackDTO;
import cn.edu.seig.vibemusic.model.entity.Feedback;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IFeedbackService;
import cn.edu.seig.vibemusic.util.SortWhitelistUtil;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import cn.edu.seig.vibemusic.util.TypeConversionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
@CacheConfig(cacheNames = "feedbackCache")
public class FeedbackServiceImpl extends ServiceImpl<FeedbackMapper, Feedback> implements IFeedbackService {

    @Autowired
    private FeedbackMapper feedbackMapper;

    /**
     * 分页查询反馈信息
     *
     * @param feedbackDTO 反馈信息查询条件
     * @return 反馈信息分页结果
     */
    @Override
    // 排序参数必须并入缓存 key，否则同一筛选条件的不同排序会命中同一条缓存，表现为「排序不生效」。
    // v3 → v4：新增查看时间字段，升版本号让旧缓存自然失效。
    @Cacheable(key = "'getFeedbackList-v4' + #feedbackDTO.pageNum + #feedbackDTO.pageSize + #feedbackDTO.keyword + #feedbackDTO.sortField + #feedbackDTO.order + #feedbackDTO.isRead")
    public Result<PageResult<Feedback>> getAllFeedbacks(FeedbackDTO feedbackDTO) {
        // 分页查询
        Page<Feedback> page = new Page<>(feedbackDTO.getPageNum(), feedbackDTO.getPageSize());
        QueryWrapper<Feedback> queryWrapper = new QueryWrapper<>();
        if (feedbackDTO.getKeyword() != null) {
            queryWrapper.like("feedback", feedbackDTO.getKeyword());
        }
        if (feedbackDTO.getIsRead() != null) {
            queryWrapper.eq("is_read", feedbackDTO.getIsRead());
        }
        // 排序：仅接受白名单字段，未命中/缺省时按反馈时间倒序（历史行为）
        Map<String, String> sortWhitelist = Map.of(
                "feedbackId", "id",
                "userId", "user_id",
                "createTime", "create_time",
                "readTime", "read_time"
        );
        Map.Entry<String, Boolean> sort = SortWhitelistUtil.toColumnAndDirection(
                feedbackDTO.getSortField(), feedbackDTO.getOrder(), sortWhitelist);
        if (sort == null) {
            queryWrapper.orderByDesc("create_time");
        } else if ("read_time".equals(sort.getKey())) {
            // 未查看记录没有查看时间，升序/降序都固定排在最后
            queryWrapper.orderByAsc("read_time IS NULL")
                    .orderBy(true, sort.getValue(), "read_time");
        } else if (sort.getValue()) {
            queryWrapper.orderByAsc(sort.getKey());
        } else {
            queryWrapper.orderByDesc(sort.getKey());
        }

        IPage<Feedback> feedbackPage = feedbackMapper.selectPage(page, queryWrapper);
        if (feedbackPage.getRecords().size() == 0) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        return Result.success(new PageResult<>(feedbackPage.getTotal(), feedbackPage.getRecords()));
    }

    /**
     * 删除反馈信息
     *
     * @param feedbackId 反馈信息id
     * @return 反馈信息删除结果
     */
    @Override
    @CacheEvict(cacheNames = "feedbackCache", allEntries = true)
    public Result deleteFeedback(Long feedbackId) {
        if (feedbackMapper.deleteById(feedbackId) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 批量删除反馈信息
     *
     * @param feedbackIds 反馈信息id列表
     * @return 反馈信息批量删除结果
     */
    @Override
    @CacheEvict(cacheNames = "feedbackCache", allEntries = true)
    public Result deleteFeedbacks(List<Long> feedbackIds) {
        if (feedbackMapper.deleteByIds(feedbackIds) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 批量标记反馈为已查看（单条与多条共用，重复标记幂等）
     *
     * @param feedbackIds 反馈信息id列表
     * @return 标记结果
     */
    @Override
    @CacheEvict(cacheNames = "feedbackCache", allEntries = true)
    public Result markFeedbacksRead(List<Long> feedbackIds) {
        if (feedbackIds == null) {
            return Result.error("请选择需要标记的反馈");
        }
        List<Long> validIds = feedbackIds.stream().filter(Objects::nonNull).distinct().toList();
        if (validIds.isEmpty()) {
            return Result.error("请选择需要标记的反馈");
        }

        LambdaUpdateWrapper<Feedback> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(Feedback::getFeedbackId, validIds)
                .eq(Feedback::getIsRead, 0)
                .set(Feedback::getIsRead, 1)
                // 只更新未查看记录，重复标记不会覆盖首次查看时间
                .set(Feedback::getReadTime, LocalDateTime.now());
        feedbackMapper.update(null, updateWrapper);

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 添加反馈信息
     *
     * @param content 反馈信息内容
     * @return 反馈信息添加结果
     */
    @Override
    @CacheEvict(cacheNames = "feedbackCache", allEntries = true)
    public Result addFeedback(String content) {
        Map<String, Object> map = ThreadLocalUtil.get();
        Object userIdObj = map.get(JwtClaimsConstant.USER_ID);
        Long userId = TypeConversionUtil.toLong(userIdObj);

        Feedback feedback = new Feedback();
        feedback.setUserId(userId).setFeedback(content).setCreateTime(LocalDateTime.now());

        if (feedbackMapper.insert(feedback) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

}
