package com.ainexus.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 分页结果封装。
 */
@Data
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    private long total;
    private int page;
    private int size;

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        return new PageResult<>(records, total, page, size);
    }
}
