package com.campus.archive.service.impl;

import com.campus.archive.config.Repositories;
import com.campus.archive.dto.*;
import com.campus.archive.entity.Category;
import com.campus.archive.entity.FileType;
import com.campus.archive.entity.Material;
import com.campus.archive.entity.TrashRecord;
import com.campus.archive.infra.CryptoUtil;
import com.campus.archive.infra.FileStore;
import com.campus.archive.service.MaterialService;
import com.campus.archive.service.PrivateSecurityService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 材料管理模块实现。
 */
public class MaterialServiceImpl implements MaterialService {

    /** 单文件上限 200 MB（详细设计 5.7 节）。 */
    public static final long MAX_FILE_SIZE = 200L * 1024 * 1024;
    /** 兜底分类：其他。 */
    private static final String FALLBACK_CATEGORY_ID = "cat-7";

    private final Repositories repos;
    private final FileStore fileStore;
    private final PrivateSecurityService security;

    public MaterialServiceImpl(Repositories repos, FileStore fileStore, PrivateSecurityService security) {
        this.repos = repos;
        this.fileStore = fileStore;
        this.security = security;
    }

    /* ============================ 上传 ============================ */

    @Override
    public Result<UploadSummary> upload(List<File> files, UploadOptions options) {
        UploadOptions opt = options == null ? new UploadOptions() : options;
        UploadSummary summary = new UploadSummary();
        if (files == null || files.isEmpty()) {
            return Result.fail("E100", "没有选择任何文件");
        }

        for (File f : files) {
            String display = f.getName();
            if (!f.exists() || !f.isFile()) {
                summary.fail(display, "E102", "文件可能已被移动或删除，请重新选择");
                continue;
            }
            if (f.length() > MAX_FILE_SIZE) {
                summary.fail(display, "E101", "文件超过 200 MB，无法上传");
                continue;
            }
            try {
                FileType type = FileType.of(display);
                String hash = CryptoUtil.sha256Hex(f.toPath());

                // 三级去重的二级判定：文件名+大小已在界面上过滤，这里按内容哈希确认
                String duplicateId = repos.query(d -> d.material().findDuplicateId(hash, f.length()));
                if (duplicateId != null) {
                    summary.warnDuplicate(display);
                }

                String categoryId = resolveCategory(opt);
                String finalName = display;

                String storedPath;
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    storedPath = fileStore.save(in, display);
                }

                Material m = new Material(finalName, storedPath, type, f.length(), hash);
                m.setId(UUID.randomUUID().toString());
                m.setCategoryId(categoryId);
                m.setIsPrivate(false);
                m.setCreatedAt(LocalDateTime.now());

                repos.tx(d -> {
                    d.material().insert(m);
                    d.config().put("last.category", categoryId);
                    return null;
                });

                summary.success(display);
            } catch (IOException e) {
                summary.fail(display, "E102", "文件读取失败：" + e.getMessage());
            } catch (RuntimeException e) {
                // 单文件失败不中断整批（对应 AC-02）
                summary.fail(display, "E501", "保存失败，请重试");
            }
        }
        return Result.ok(summary);
    }

    private String resolveCategory(UploadOptions opt) {
        if (opt.getCategoryId() != null && !opt.getCategoryId().isBlank()) {
            return opt.getCategoryId();
        }
        return FALLBACK_CATEGORY_ID;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }

    /* ============================ 编辑 ============================ */

    @Override
    public Result<Void> rename(String id, String newName) {
        if (newName == null || newName.isBlank()) {
            return Result.fail("E300", "名称不能为空");
        }
        repos.query(d -> d.material().updateName(id, newName.trim()));
        return Result.ok();
    }

    @Override
    public Result<Void> moveToCategory(String id, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return Result.fail("E300", "请选择分类");
        }
        repos.query(d -> d.material().updateCategory(id, categoryId));
        return Result.ok();
    }

    /* ============================ 删除 / 恢复 ============================ */

    @Override
    public Result<Void> softDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Result.fail("E400", "请先选择材料");
        repos.tx(d -> {
            LocalDateTime now = LocalDateTime.now();
            for (String id : ids) {
                Material m = d.material().findById(id);
                if (m == null || m.isTrashed()) continue;
                d.material().markDeleted(id, now);
                TrashRecord tr = new TrashRecord();
                tr.setId(UUID.randomUUID().toString());
                tr.setMaterialId(id);
                tr.setDeletedAt(now);
                tr.setOriginCategoryId(m.getCategoryId());
                d.material().insertTrash(tr);
            }
            return null;
        });
        return Result.ok();
    }

    @Override
    public Result<Void> restore(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Result.fail("E400", "请先选择材料");
        repos.tx(d -> {
            for (String id : ids) {
                TrashRecord tr = d.material().findTrash(id);
                if (tr != null && tr.getOriginCategoryId() != null) {
                    d.material().updateCategory(id, tr.getOriginCategoryId());
                }
                d.material().markDeleted(id, null);
                d.material().deleteTrash(id);
            }
            return null;
        });
        return Result.ok();
    }

    @Override
    public Result<Void> purge(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Result.fail("E400", "请先选择材料");
        List<String> paths = new ArrayList<>();
        List<String> vaultPaths = new ArrayList<>();

        repos.tx(d -> {
            for (String id : ids) {
                Material m = d.material().findById(id);
                if (m == null) continue;
                var vault = d.security().findVaultByMaterial(id);
                if (vault != null) vaultPaths.add(vault.getEncryptedPath());
                paths.add(m.getStoredPath());

                d.material().deleteVaultEntry(id);
                d.material().deleteTrash(id);
                d.material().deleteByIds(List.of(id));
            }
            return null;
        });

        // 先删库、后删文件：文件删除失败只记日志，不影响用户感知（详细设计 9.4 节）
        for (String p : paths) {
            try {
                fileStore.delete(p);
            } catch (IOException e) {
                System.err.println("[材料] 物理文件删除失败：" + p);
            }
        }
        for (String p : vaultPaths) {
            try {
                fileStore.deleteEncrypted(p);
            } catch (IOException e) {
                System.err.println("[材料] 密文删除失败：" + p);
            }
        }
        return Result.ok();
    }

    @Override
    public Material findById(String id) {
        return repos.query(d -> d.material().findById(id));
    }

    @Override
    public List<Material> listTrashed() {
        return repos.query(d -> d.material().listTrashed());
    }

    /* ============================ 文件取出 ============================ */

    @Override
    public Result<Path> materialize(String id) {
        Material m = findById(id);
        if (m == null) return Result.fail("E102", "材料不存在");

        repos.query(d -> d.material().touchUsed(id, LocalDateTime.now()));

        if (!m.getIsPrivate()) {
            Path p = fileStore.resolve(m.getStoredPath());
            if (!Files.exists(p)) return Result.fail("E102", "文件可能已被移动或删除，请重新选择");
            return Result.ok(p);
        }

        Result<byte[]> plain = security.readVault(id);
        if (!plain.isSuccess()) return Result.fail(plain.getCode(), plain.getMessage());
        try {
            String suffix = extensionOf(m.getFileName());
            return Result.ok(fileStore.writeTemp(plain.getData(), suffix.isEmpty() ? ".bin" : suffix));
        } catch (IOException e) {
            return Result.fail("E102", "解密后的文件写入失败");
        }
    }

    @Override
    public Result<Void> touchUsed(String id) {
        repos.query(d -> d.material().touchUsed(id, LocalDateTime.now()));
        return Result.ok();
    }

}
