package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Kilsme
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserServiceImpl userService;
    @Resource
    private IFollowService followService;
    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //查询blog有关的用户
        queryBolgUser(blog);
        //查询blog是否被点赞
        isBoleLiked(blog);
        // 从 Redis 读取最新点赞数（Write-Behind 模式下 MySQL 数据可能滞后）
        String countKey = RedisConstants.BLOG_LIKE_COUNT_KEY + id;
        String likedCount = stringRedisTemplate.opsForValue().get(countKey);
        if (likedCount != null) {
            blog.setLiked(Integer.valueOf(likedCount));
        }
        return Result.ok(blog);
    }

    private void isBoleLiked(Blog blog) {
        //判断当前登录用户是否点赞
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //用户未登录，无法点赞
            return;
        }
        Long userId = user.getId();
        String key = "blog:liked:" + blog.getId();
        Double score= stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);//但是没有写入数据库中，只是在前端进行展示，当前用户是否存在redis中的set集合中，如果存在则说明当前用户已经点赞了，返回true，否则返回false
    }

    private void queryBolgUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBolgUser(blog);//根据博客查询用户
            //查询blog是否被点赞
            isBoleLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        String countKey = RedisConstants.BLOG_LIKE_COUNT_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 点赞：Redis 计数+1，ZSet 记录点赞用户，Set 标记为待同步脏数据
            stringRedisTemplate.opsForValue().increment(countKey, 1);
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            stringRedisTemplate.opsForSet().add(RedisConstants.BLOG_LIKE_CHANGED_KEY, id.toString());
        } else {
            // 取消点赞：Redis 计数-1，ZSet 移除点赞用户，Set 标记为待同步脏数据
            stringRedisTemplate.opsForValue().decrement(countKey, 1);
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            stringRedisTemplate.opsForSet().add(RedisConstants.BLOG_LIKE_CHANGED_KEY, id.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5的点赞用户
        String key = "blog:liked:" + id;
        //查询top5的点赞用户，按照时间戳从大到小排序
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //如果没有人点赞，返回空列表
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }
        //解析出用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //返回用户id和用户名称
        String idStr= StrUtil.join(",",ids);
        List<UserDTO> userList = userService.query().in("id",ids).last("order by field(id,"+idStr+")").list().stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        //返回结果
        return Result.ok(userList);
    }

    @Override
    public Result saveBolg(Blog blog) {
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //保存探店笔记
        boolean save = save(blog);
        if (!save) {
            return Result.fail("新增笔记失败");
        }
        //查询笔记作者的全部粉丝
        List<Follow> follows =followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有粉丝
        for(Follow follow:follows){
            //推送
            String key="feed:"+follow.getUserId();
             stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //返回id
        return Result.ok(blog.getId());

    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key="feed:"+userId;
        //查询收件箱 ZREVRANGEBYSCORE key Max Min limit offset count
        Set<ZSetOperations.TypedTuple   <String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //没有数据
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //根据id查询bolg
        List<Long>ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;//记录同一时间戳的数量
        for(ZSetOperations.TypedTuple<String> typedTuple:typedTuples){
            String value = typedTuple.getValue();//获取id
            ids.add(Long.valueOf(value));
            //获取分数时间戳
            long time=typedTuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else {
                minTime=time;
                os=1;
            }
        }
        String idStr= StrUtil.join(",",ids);//in语句的参数，按照id的顺序进行排序
        List<Blog> blogs = query().in("id",ids).last("order by field(id,"+idStr+")").list();
        //查询blog有关的用户
        blogs.forEach(blog -> {
            queryBolgUser(blog);
            //查询blog是否被点赞
            isBoleLiked(blog);
        });
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}


