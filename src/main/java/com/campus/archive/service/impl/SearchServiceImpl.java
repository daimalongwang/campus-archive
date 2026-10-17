package com.campus.archive.service.impl;

import com.campus.archive.config.Repositories;
import com.campus.archive.dto.MaterialQuery;
import com.campus.archive.dto.Page;
import com.campus.archive.entity.Material;
import com.campus.archive.service.SearchService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索与统计实现（对应需求 FR-10 ~ FR-13）。
 * 组合筛选条件由 {@link MaterialQuery} 承载，排序字段经白名单转换后拼入 SQL，杜绝注入。
 */
public class SearchServiceImpl implements SearchService {

    private final Repositories repos;

    public SearchServiceImpl(Repositories repos) {
        this.repos = repos;
    }

    @Override
    public Page<Material> search(MaterialQuery query, int page, int size) {
        MaterialQuery q = query == null ? new MaterialQuery() : query;
        int p = Math.max(1, page);
        int s = Math.max(1, size);
        int offset = (p - 1) * s;
        String orderBy = q.safeSortColumn();

        List<Material> items = repos.query(d -> d.material().search(q, orderBy, s, offset));
        int total = repos.query(d -> d.material().countSearch(q));
        return new Page<>(items, total, p, s);
    }

    @Override
    public List<Material> recent(int limit) {
        return repos.query(d -> d.material().listRecent(Math.max(1, limit)));
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", repos.query(d -> d.material().countActive()));
        out.put("privateCount", repos.query(d -> d.material().countPrivate()));
        out.put("trashed", repos.query(d -> d.material().listTrashed().size()));
        out.put("byCategory", repos.query(d -> d.material().countByCategory()));
        return out;
    }
}
