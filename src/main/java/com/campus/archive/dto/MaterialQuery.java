package com.campus.archive.dto;

import com.campus.archive.entity.FileType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 材料查询条件对象（对应需求 FR-11 组合筛选）。
 * 用可变 POJO 而非 record，便于界面逐项设置筛选条件。
 */
public class MaterialQuery {

    private String keyword;
    /** 按分类子树筛选；null 表示不按分类过滤（例如全库搜索）。 */
    private List<String> categoryIds;
    private FileType fileType;
    private Boolean onlyPrivate;
    private LocalDateTime createdFrom;
    private LocalDateTime createdTo;
    /** 排序字段；为 null 表示用默认排序（普通视图按上传时间，回收站视图按删除时间）。 */
    private String sortBy;
    private boolean desc = true;
    private boolean includeTrashed = false;
    /** true 表示"只看回收站"；优先级高于 includeTrashed。 */
    private boolean onlyTrashed = false;

    public String getKeyword() { return keyword; }
    public MaterialQuery setKeyword(String keyword) { this.keyword = keyword; return this; }

    public List<String> getCategoryIds() { return categoryIds; }

    /** 按一棵子树筛选：传入该分类及其全部后代的 ID，父分类才能"看到"子分类里的材料。 */
    public MaterialQuery setCategoryIds(List<String> categoryIds) {
        this.categoryIds = (categoryIds == null || categoryIds.isEmpty()) ? null : List.copyOf(categoryIds);
        return this;
    }

    /** 只筛某一个分类本身（不含子分类）。 */
    public MaterialQuery setCategoryId(String categoryId) {
        return setCategoryIds(categoryId == null || categoryId.isBlank() ? null : List.of(categoryId));
    }

    public FileType getFileType() { return fileType; }
    public MaterialQuery setFileType(FileType fileType) { this.fileType = fileType; return this; }

    public Boolean getOnlyPrivate() { return onlyPrivate; }
    public MaterialQuery setOnlyPrivate(Boolean onlyPrivate) { this.onlyPrivate = onlyPrivate; return this; }

    public LocalDateTime getCreatedFrom() { return createdFrom; }
    public MaterialQuery setCreatedFrom(LocalDateTime createdFrom) { this.createdFrom = createdFrom; return this; }

    public LocalDateTime getCreatedTo() { return createdTo; }
    public MaterialQuery setCreatedTo(LocalDateTime createdTo) { this.createdTo = createdTo; return this; }

    public String getSortBy() { return sortBy; }
    public MaterialQuery setSortBy(String sortBy) { this.sortBy = sortBy; return this; }

    public boolean isDesc() { return desc; }
    public MaterialQuery setDesc(boolean desc) { this.desc = desc; return this; }

    public boolean isIncludeTrashed() { return includeTrashed; }
    public MaterialQuery setIncludeTrashed(boolean includeTrashed) { this.includeTrashed = includeTrashed; return this; }

    public boolean isOnlyTrashed() { return onlyTrashed; }
    public MaterialQuery setOnlyTrashed(boolean onlyTrashed) { this.onlyTrashed = onlyTrashed; return this; }

    /** 派生 getter，供 MyBatis OGNL 以 q.keywordLike 形式引用。 */
    public String getKeywordLike() {
        return keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
    }

    /**
     * 排序字段白名单，防止 SQL 注入。
     * 未显式指定时按视图取默认值：回收站看"最近删了什么"，普通档案看"最近放进来了什么"。
     */
    public String safeSortColumn() {
        String key = sortBy != null ? sortBy : (onlyTrashed ? "deleted_at" : "created_at");
        return switch (key) {
            case "used_at" -> "m.used_at";
            case "deleted_at" -> "m.deleted_at";
            case "file_name" -> "m.file_name";
            default -> "m.created_at";
        };
    }
}
