package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    @PostMapping
    public Result addComment(@RequestBody BlogComments comment) {
        return blogCommentsService.addComment(comment);
    }

    @GetMapping
    public Result queryByCursor(@RequestParam("blogId") Long blogId,
                                @RequestParam(value = "lastId", required = false) Long lastId,
                                @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return blogCommentsService.queryCommentsByCursor(blogId, lastId, size);
    }

    @GetMapping("/hot")
    public Result queryHotComments(@RequestParam("blogId") Long blogId) {
        return blogCommentsService.queryHotComments(blogId);
    }
}
