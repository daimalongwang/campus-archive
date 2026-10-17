package com.campus.archive.entity;

import java.time.LocalDateTime;

/**
 * 材料：系统核心聚合根，对应 material 表。
 * 详细设计与《第 4 周详细设计文档》4.4.1 节字段表一一对应。
 */
public class Material {

    private String id;
    private String fileName;      // 用户可见显示名
    private String storedPath;    // 相对 files/ 的存储路径
    private FileType fileType;
    private long fileSize;
    private String contentHash;   // SHA-256，用于重复检测
    private String categoryId;
    private boolean isPrivate;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;

    // 展示用关联字段（不落库）
    private String categoryName;

    public Material() {
    }

    public Material(String fileName, String storedPath, FileType fileType, long fileSize, String contentHash) {
        this.fileName = fileName;
        this.storedPath = storedPath;
        this.fileType = fileType;
        this.fileSize = fileSize;
        this.contentHash = contentHash;
    }

    /** 是否在回收站中。 */
    public boolean isTrashed() {
        return deletedAt != null;
    }

    public String sizeText() {
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        return String.format("%.1f MB", fileSize / 1024.0 / 1024.0);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }

    public FileType getFileType() { return fileType; }
    public void setFileType(FileType fileType) { this.fileType = fileType; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public boolean getIsPrivate() { return isPrivate; }
    public void setIsPrivate(boolean isPrivate) { this.isPrivate = isPrivate; }

    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }


    @Override
    public String toString() {
        return fileName;
    }
}
