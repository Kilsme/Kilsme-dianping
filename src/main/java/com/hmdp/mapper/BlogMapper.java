package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.dto.BlogLikeCountDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author Kilsme
 * @since 2021-12-22
 */
public interface BlogMapper extends BaseMapper<Blog> {

    @Insert({
            "<script>",
            "INSERT INTO tb_blog_like_count (blog_id, liked) VALUES ",
            "<foreach collection='counts' item='item' separator=','>",
            "(#{item.blogId}, #{item.liked})",
            "</foreach>",
            "ON DUPLICATE KEY UPDATE liked = VALUES(liked)",
            "</script>"
    })
    int upsertLikeCounts(@Param("counts") List<BlogLikeCountDTO> counts);

    @Update({
            "<script>",
            "UPDATE tb_blog",
            "SET liked = CASE id",
            "<foreach collection='counts' item='item'>",
            "WHEN #{item.blogId} THEN #{item.liked}",
            "</foreach>",
            "END",
            "WHERE id IN",
            "<foreach collection='counts' item='item' open='(' separator=',' close=')'>",
            "#{item.blogId}",
            "</foreach>",
            "</script>"
    })
    int updateBlogLikedBatch(@Param("counts") List<BlogLikeCountDTO> counts);
}
