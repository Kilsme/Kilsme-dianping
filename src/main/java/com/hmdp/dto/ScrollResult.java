package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;//上次查询的最小时间戳，下一次查询时需要使用
    private Integer offset;//偏移量，下一次查询时需要使用
}
