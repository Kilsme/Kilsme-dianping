package com.hmdp.task;

import com.hmdp.service.IBlogService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class BlogLikeFlushTask {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IBlogService blogService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 */3 * * * ?")
    public void flushLikeCountToDb() {
        Set<String> dirtyBlogIds = stringRedisTemplate.opsForSet().members(RedisConstants.BLOG_LIKE_DIRTY_KEY);
        if (dirtyBlogIds == null || dirtyBlogIds.isEmpty()) {
            return;
        }
        for (String blogId : dirtyBlogIds) {
            Object count = stringRedisTemplate.opsForHash().get(RedisConstants.BLOG_LIKE_COUNT_KEY, blogId);
            if (count == null) {
                continue;
            }
            Set<String> members = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + blogId, 0, -1);
            if (members != null && !members.isEmpty()) {
                List<Object[]> batchArgs = new ArrayList<>();
                for (String userId : members) {
                    batchArgs.add(new Object[]{Long.valueOf(blogId), Long.valueOf(userId)});
                }
                jdbcTemplate.batchUpdate("INSERT IGNORE INTO tb_blog_like(blog_id, user_id) VALUES(?,?)", batchArgs);
                String placeholders = String.join(",", java.util.Collections.nCopies(members.size(), "?"));
                List<Object> params = new ArrayList<>();
                params.add(Long.valueOf(blogId));
                params.addAll(members.stream().map(Long::valueOf).collect(java.util.stream.Collectors.toList()));
                jdbcTemplate.update("DELETE FROM tb_blog_like WHERE blog_id = ? AND user_id NOT IN (" + placeholders + ")", params.toArray());
            } else {
                jdbcTemplate.update("DELETE FROM tb_blog_like WHERE blog_id = ?", Long.valueOf(blogId));
            }
            blogService.lambdaUpdate()
                    .eq(com.hmdp.entity.Blog::getId, Long.valueOf(blogId))
                    .set(com.hmdp.entity.Blog::getLiked, Integer.parseInt(count.toString()))
                    .update();
            stringRedisTemplate.opsForSet().remove(RedisConstants.BLOG_LIKE_DIRTY_KEY, blogId);
        }
    }
}
