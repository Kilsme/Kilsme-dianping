package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Kilsme
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {
    Result addComment(BlogComments comment);
    Result queryCommentsByCursor(Long blogId, Long lastId, Integer size);
    Result queryHotComments(Long blogId);
}
