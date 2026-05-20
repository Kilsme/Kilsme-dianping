package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Kilsme
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //关注还是取关
        if(isFollow){
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);//被关注用户id
            boolean save = save(follow);
            if(save){
                //把关注用户id放到redis中，集合key：follow:userId value：followUserId
                stringRedisTemplate.opsForSet().add("follows:"+userId,followUserId.toString());
            }
        }else {
            //取关，删除数据
            boolean remove = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if(remove){
                //把关注用户id从redis中删除
                stringRedisTemplate.opsForSet().remove("follows:"+userId,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
      //查询是否关注
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();
        //判断count是否大于0
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //求交集
        String key="follows:"+userId;
        String key2="follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect==null||intersect.isEmpty()){
            //没有交集，返回空
            return Result.ok();
        }
        List<Long> ids=intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户集合
        List<UserDTO> userList = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userList);
    }
}
