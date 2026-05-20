package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

   @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result selectTypeLists() {
        //进行查询缓存
        String key= "shopList";
        String shopList = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopList)){
            //缓存中没有数据进行查询数据库
            List<ShopType> typeList = query().orderByAsc("sort").list();
            //写入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
            return Result.ok(typeList);
        }
        //缓存存在
        return Result.ok(JSONUtil.toList(shopList, ShopType.class));
    }
}
