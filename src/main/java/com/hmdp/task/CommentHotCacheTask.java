package com.hmdp.task;

import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Component
public class CommentHotCacheTask {

    @Resource
    private IBlogService blogService;
    @Resource
    private IBlogCommentsService blogCommentsService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Scheduled(cron = "0 */3 * * * ?")
    public void refreshHotCommentsCache() {
        List<Blog> hotBlogs = blogService.lambdaQuery().orderByDesc(Blog::getLiked).last("limit 10").list();
        for (Blog blog : hotBlogs) {
            String key = RedisConstants.BLOG_HOT_COMMENTS_KEY + blog.getId();
            stringRedisTemplate.delete(key);
            List<BlogComments> comments = blogCommentsService.lambdaQuery()
                    .eq(BlogComments::getBlogId, blog.getId())
                    .orderByDesc(BlogComments::getLiked)
                    .last("limit 100")
                    .list();
            for (BlogComments comment : comments) {
                stringRedisTemplate.opsForZSet().add(key, comment.getId().toString(), comment.getLiked() == null ? 0 : comment.getLiked());
            }
        }
    }
}
