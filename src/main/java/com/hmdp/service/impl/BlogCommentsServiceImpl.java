package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SensitiveWordFilter;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;
    @Resource
    private UserServiceImpl userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result addComment(BlogComments comment) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        comment.setUserId(user.getId());
        comment.setParentId(comment.getParentId() == null ? 0L : comment.getParentId());
        comment.setAnswerId(comment.getAnswerId() == null ? 0L : comment.getAnswerId());
        comment.setStatus(false);
        comment.setContent(sensitiveWordFilter.filter(comment.getContent()));
        save(comment);
        stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_HOT_COMMENTS_KEY + comment.getBlogId(),
                comment.getId().toString(),
                comment.getLiked() == null ? 0 : comment.getLiked());
        return Result.ok(comment.getId());
    }

    @Override
    public Result queryCommentsByCursor(Long blogId, Long lastId, Integer size) {
        long cursor = lastId == null ? Long.MAX_VALUE : lastId;
        int pageSize = size == null ? 10 : size;
        List<BlogComments> roots = query().eq("blog_id", blogId)
                .eq("parent_id", 0)
                .lt("id", cursor)
                .orderByDesc("id")
                .last("limit " + pageSize)
                .list();
        if (roots.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        roots.forEach(this::fillCommentUser);
        for (BlogComments root : roots) {
            List<BlogComments> replies = query().eq("parent_id", root.getId())
                    .orderByAsc("id")
                    .last("limit 20")
                    .list();
            replies.forEach(this::fillCommentUser);
            root.setReplies(replies);
        }
        Map<String, Object> result = new HashMap<>(2);
        result.put("list", roots);
        result.put("nextLastId", roots.get(roots.size() - 1).getId());
        return Result.ok(result);
    }

    @Override
    public Result queryHotComments(Long blogId) {
        String key = RedisConstants.BLOG_HOT_COMMENTS_KEY + blogId;
        Set<String> ids = stringRedisTemplate.opsForZSet().reverseRange(key, 0, 99);
        if (ids != null && !ids.isEmpty()) {
            List<Long> commentIds = ids.stream().map(Long::valueOf).collect(Collectors.toList());
            Map<Long, Integer> orderMap = new HashMap<>(commentIds.size());
            for (int i = 0; i < commentIds.size(); i++) {
                orderMap.put(commentIds.get(i), i);
            }
            List<BlogComments> comments = query().in("id", commentIds).list();
            comments.sort(Comparator.comparingInt(c -> orderMap.getOrDefault(c.getId(), Integer.MAX_VALUE)));
            comments.forEach(this::fillCommentUser);
            return Result.ok(comments);
        }
        List<BlogComments> comments = query().eq("blog_id", blogId).orderByDesc("liked").last("limit 100").list();
        for (BlogComments comment : comments) {
            fillCommentUser(comment);
            stringRedisTemplate.opsForZSet().add(key, comment.getId().toString(), comment.getLiked() == null ? 0 : comment.getLiked());
        }
        return Result.ok(comments);
    }

    private void fillCommentUser(BlogComments comment) {
        User user = userService.getById(comment.getUserId());
        if (user == null) {
            return;
        }
        comment.setUserName(user.getNickName());
        comment.setUserIcon(user.getIcon());
    }
}
