package com.hmdp.job;

import com.hmdp.dto.BlogLikeCountDTO;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.utils.RedisConstants;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 点赞数据定时刷盘处理器（Write-Behind 模式）
 * 从 Redis Set "blog:like:changed" 中获取发生变更的 blogId，
 * 查询 Redis 中最新的点赞计数，批量写回 MySQL。
 */
@Slf4j
@Component
public class LikeSyncJobHandler {

    private static final int BATCH_SIZE = 100;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private BlogMapper blogMapper;

    /**
     * 定时从 Redis 同步点赞数据到 MySQL
     * 在 XXL-JOB Admin 中配置任务，JobHandler 名称为 likeSyncJob
     */
    @XxlJob("likeSyncJob")
    public void execute() {
        int totalSuccess = 0;
        int totalFail = 0;
        List<BlogLikeCountDTO> batch = new ArrayList<>(BATCH_SIZE);

        try {
            // 循环 SPOP 取出所有脏 blogId
            while (true) {
                String blogIdStr = stringRedisTemplate.opsForSet()
                        .pop(RedisConstants.BLOG_LIKE_CHANGED_KEY);
                if (blogIdStr == null) {
                    // Set 已为空，刷新剩余批次
                    if (!batch.isEmpty()) {
                        int flushed = flushBatch(batch);
                        totalSuccess += flushed;
                        totalFail += batch.size() - flushed;
                    }
                    break;
                }

                Long blogId = Long.valueOf(blogIdStr);
                // 从 Redis 读取最新点赞数
                String countStr = stringRedisTemplate.opsForValue()
                        .get(RedisConstants.BLOG_LIKE_COUNT_KEY + blogId);
                int liked = countStr != null ? Integer.parseInt(countStr) : 0;
                batch.add(new BlogLikeCountDTO(blogId, liked));

                if (batch.size() >= BATCH_SIZE) {
                    int flushed = flushBatch(batch);
                    totalSuccess += flushed;
                    totalFail += batch.size() - flushed;
                }
            }

            log.info("点赞数据同步完成, 成功={}, 失败={}", totalSuccess, totalFail);
        } catch (Exception e) {
            log.error("点赞数据同步任务异常, 已成功={}, 已失败={}", totalSuccess, totalFail, e);
        }
    }

    /**
     * 批量刷盘：写入 tb_blog_like_count 和更新 tb_blog.liked
     *
     * @param batch 当前批次的点赞数据
     * @return 成功更新的条数
     */
    private int flushBatch(List<BlogLikeCountDTO> batch) {
        try {
            blogMapper.upsertLikeCounts(batch);
            blogMapper.updateBlogLikedBatch(batch);
            int count = batch.size();
            batch.clear();
            return count;
        } catch (Exception e) {
            log.error("批量刷盘失败, 批次大小={}", batch.size(), e);
            batch.clear();
            return 0;
        }
    }
}
