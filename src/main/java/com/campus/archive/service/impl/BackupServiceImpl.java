package com.campus.archive.service.impl;

import com.campus.archive.config.Repositories;
import com.campus.archive.dto.ExportRequest;
import com.campus.archive.dto.ExportResult;
import com.campus.archive.dto.Result;
import com.campus.archive.entity.FileType;
import com.campus.archive.entity.Material;
import com.campus.archive.infra.CryptoUtil;
import com.campus.archive.infra.FileStore;
import com.campus.archive.infra.MiniJson;
import com.campus.archive.pattern.export.ArchiveExporter;
import com.campus.archive.pattern.export.ExportException;
import com.campus.archive.service.BackupService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 备份与迁移实现。
 *
 * <p>导出委托给模板方法模式下的全量备份导出器（模式 ②）。
 * 导入的顺序是"先落文件、再写元数据、失败清理"，以保证任一步失败都不会留下半成品
 * （对应《需求分析报告》"导入等操作失败时不得造成部分数据静默丢失"与 AC-10）。</p>
 */
public class BackupServiceImpl implements BackupService {

    private static final String FALLBACK_CATEGORY_ID = "cat-7";

    private final Repositories repos;
    private final FileStore fileStore;
    private final ArchiveExporter backupExporter;

    public BackupServiceImpl(Repositories repos, FileStore fileStore, ArchiveExporter backupExporter) {
        this.repos = repos;
        this.fileStore = fileStore;
        this.backupExporter = backupExporter;
    }

    @Override
    public Result<ExportResult> exportAll(Path targetDir, boolean includePrivate) {
        try {
            ExportResult result = backupExporter.export(ExportRequest.ofFullBackup(targetDir, includePrivate));
            return Result.ok(result);
        } catch (ExportException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @Override
    public Result<String> importFrom(Path packagePath) {
        if (packagePath == null || !Files.exists(packagePath)) {
            return Result.fail("E500", "备份文件不存在");
        }

        Map<String, byte[]> payloads = new LinkedHashMap<>();
        String manifestJson;
        try {
            manifestJson = readArchive(packagePath, payloads);
        } catch (IOException e) {
            return Result.fail("E500", "备份文件无法识别，请确认文件来源");
        }
        if (manifestJson == null) {
            return Result.fail("E500", "备份文件无法识别，请确认文件来源");
        }

        Map<String, Object> manifest;
        try {
            manifest = MiniJson.parseObject(manifestJson);
        } catch (RuntimeException e) {
            return Result.fail("E500", "备份清单已损坏，无法导入");
        }
        if (!"backup".equals(String.valueOf(manifest.get("kind")))) {
            return Result.fail("E500", "该文件不是档案备份包（可能是材料导出包）");
        }

        // ---- 第一步：把文件落到磁盘，并准备好待插入的元数据 ----
        List<Path> written = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        try {
            for (Map.Entry<String, byte[]> e : payloads.entrySet()) {
                String displayName = e.getKey();
                // 旧版本备份包可能含 schedule.json，日程功能已移除，忽略该条目
                if ("schedule.json".equals(displayName)) continue;
                byte[] content = e.getValue();

                String storedPath = fileStore.save(new ByteArrayInputStream(content), displayName);
                written.add(fileStore.resolve(storedPath));

                Material m = new Material(displayName, storedPath, FileType.of(displayName),
                        content.length, CryptoUtil.sha256Hex(content));
                m.setId(UUID.randomUUID().toString());
                m.setCategoryId(FALLBACK_CATEGORY_ID);
                m.setIsPrivate(false);
                m.setCreatedAt(LocalDateTime.now());
                materials.add(m);
            }
        } catch (IOException | RuntimeException ex) {
            cleanup(written);
            return Result.fail("E500", "备份内容写入失败：" + ex.getMessage());
        }

        // ---- 第二步：单事务写入元数据；失败则回滚并清理已落盘文件 ----
        try {
            repos.tx(d -> {
                for (Material m : materials) d.material().insert(m);
                return null;
            });
        } catch (RuntimeException ex) {
            cleanup(written);
            return Result.fail("E501", "导入失败，已回滚，原有数据未受影响");
        }

        return Result.ok("导入完成：新增材料 " + materials.size() + " 个");
    }

    /** 读取压缩包，返回 manifest 内容并把其余条目放入 payloads。 */
    private String readArchive(Path packagePath, Map<String, byte[]> payloads) throws IOException {
        String manifest = null;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(packagePath), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] data = zis.readAllBytes();
                if ("manifest.json".equals(entry.getName())) {
                    manifest = new String(data, StandardCharsets.UTF_8);
                } else {
                    payloads.put(entry.getName(), data);
                }
            }
        }
        return manifest;
    }

    private void cleanup(List<Path> written) {
        for (Path p : written) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 清理失败只影响磁盘占用，不影响数据一致性
            }
        }
    }
}
