package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1，进行校验手机号
        if (!RegexUtils.isPhoneInvalid(phone)) {
            //2,如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3，符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4，保存验证码到session
        //session.setAttribute("code", code);=>为了防止多台tomact导致session不共享问题，将验证码存放到redis中
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5,发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1,进行校验手机号 （因为是不同的请求所以要进行再次校验）
        String phone = loginForm.getPhone();
        if (!RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //2，校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        //改变：从redis中获取验证码并校验
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //3，不一致 return
            return Result.fail("验证码错误！");
        }
        //4，一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5，判断 用户是否存在
        if (user == null) {
            //6，不存在->进行创建新用户保存
            user = creatUserWithPhone(phone);
        }
        //7，保存信息到session中
        //改变=>保存到redis中
        //7.1随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将user对象转为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //7.3保存
        String s = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(s, userMap);
        //进行设置有效期token
        stringRedisTemplate.expire(s, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //进行返回token
        return Result.ok(token);
    }

    private User creatUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存用户
        save(user);
        return user;
    }
}
