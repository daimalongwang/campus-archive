package com.campus.archive.pattern;

import com.campus.archive.dto.ExportItem;
import com.campus.archive.dto.ExportRequest;
import com.campus.archive.dto.ExportResult;
import com.campus.archive.entity.Material;
import com.campus.archive.pattern.export.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设计模式 ② 模板方法模式的验证。
 * 重点验证：骨架固定（final）、骨架顺序不变、子类只填空、钩子确实被调用。
 */
@DisplayName("设计模式② 模板方法模式：导出与备份流程")
class ExportTemplateTest {

    /** 记录步骤执行顺序的测试替身，用来证明父类骨架真的按固定顺序跑了各步骤。 */
    private static final List<String> TRACE = new ArrayList<>();

    /** 一个最小的具体实现，只回答三个抽象问题。 */
    private static class StubExporter extends AbstractArchiveExporter {
        private final List<ExportItem> items;
        private boolean afterCalled = false;

        StubExporter(ExportContext ctx, List<ExportItem> items) {
            super(ctx);
            this.items = items;
        }

        @Override
        protected List<ExportItem> collect(ExportRequest request) {
            TRACE.add("collect");
            return items;
        }

        @Override
        protected String emptyMessage() {
            return "没有可导出的条目";
        }

        @Override
        protected String packageKind() {
            return "stub";
        }

        @Override
        protected void validate(ExportRequest request) {
            TRACE.add("validate");
            super.validate(request);
        }

        @Override
        protected Map<String, Object> extraManifest(ExportRequest request) {
            TRACE.add("extraManifest");
            return Map.of("custom", "yes");
        }

        @Override
        protected void afterExport(ExportResult result, ExportRequest request) {
            TRACE.add("afterExport");
            afterCalled = true;
        }

        @Override
        public String describe() {
            return "测试用导出器";
        }
    }

    private ExportContext context(Path sourceFile, boolean unlocked) {
        return new ExportContext(
                id -> null,
                query -> List.of(),
                () -> unlocked,
                item -> Files.readAllBytes(sourceFile));
    }

    private Path tempDir() throws IOException {
        return Files.createTempDirectory("archive-export-test-");
    }

    private ExportItem item(String name, Path file, boolean privateFlag) throws IOException {
        return new ExportItem("m-1", name, file.toString(), Files.size(file), privateFlag);
    }

    @Test
    @DisplayName("模板方法 export 声明为 final，子类无法改动流程骨架")
    void skeletonIsFinal() throws Exception {
        var m = AbstractArchiveExporter.class.getMethod("export", ExportRequest.class);
        assertTrue(Modifier.isFinal(m.getModifiers()), "export 必须是 final，否则子类可以绕过骨架");

        // 各步骤必须是 protected 且非 public，保证只能由父类骨架调用
        var collect = AbstractArchiveExporter.class.getDeclaredMethod("collect", ExportRequest.class);
        assertTrue(Modifier.isProtected(collect.getModifiers()));
        assertTrue(Modifier.isAbstract(collect.getModifiers()));
    }

    @Test
    @DisplayName("导出按 validate → collect → … → extraManifest → afterExport 的固定顺序执行")
    void stepsRunInFixedOrder() throws IOException {
        TRACE.clear();
        Path dir = tempDir();
        Path src = dir.resolve("a.txt");
        Files.writeString(src, "hello", StandardCharsets.UTF_8);

        StubExporter exporter = new StubExporter(context(src, true), List.of(item("成绩单.txt", src, false)));
        ExportResult r = exporter.export(ExportRequest.ofFullBackup(dir, false));

        assertEquals(List.of("validate", "collect", "extraManifest", "afterExport"), TRACE);
        assertTrue(exporter.afterCalled);
        assertEquals(1, r.fileCount());
        assertTrue(Files.exists(r.packagePath()));
        assertEquals(64, r.checksum().length(), "应写入 SHA-256 校验和");
    }

    @Test
    @DisplayName("钩子 extraManifest 的返回值会写进 manifest.json")
    void hookOutputLandsInManifest() throws IOException {
        TRACE.clear();
        Path dir = tempDir();
        Path src = dir.resolve("b.txt");
        Files.writeString(src, "x", StandardCharsets.UTF_8);

        ExportResult r = new StubExporter(context(src, true), List.of(item("b.txt", src, false)))
                .export(ExportRequest.ofFullBackup(dir, false));

        String manifest = readEntry(r.packagePath(), "manifest.json");
        assertTrue(manifest.contains("\"kind\": \"stub\""));
        assertTrue(manifest.contains("\"custom\": \"yes\""));
        assertTrue(manifest.contains("b.txt"));
    }

    @Test
    @DisplayName("收集为空时抛 E400，且不会生成空压缩包")
    void emptyCollectionRaisesE400() throws IOException {
        Path dir = tempDir();
        StubExporter exporter = new StubExporter(context(dir.resolve("none"), true), List.of());
        ExportException e = assertThrows(ExportException.class,
                () -> exporter.export(ExportRequest.ofFullBackup(dir, false)));
        assertEquals("E400", e.getCode());
        assertTrue(e.getMessage().contains("没有可导出的条目"));
    }

    @Test
    @DisplayName("两个具体子类共用同一骨架，差异只在 collect 与钩子")
    void concreteExportersOnlyFillInSteps() throws IOException {
        Path dir = tempDir();
        Material m = new Material("证书.pdf", dir.resolve("cert.pdf").toString(), null, 10, "hash");
        m.setId("m-1");

        ExportContext ctx = new ExportContext(
                id -> "m-1".equals(id) ? m : null,
                query -> List.of(m),
                () -> true,
                i -> "content".getBytes(StandardCharsets.UTF_8));

        ExportResult selResult = new SelectionExportExporter(ctx)
                .export(ExportRequest.ofSelection(List.of("m-1"), dir));
        assertEquals(1, selResult.fileCount());
        assertTrue(readEntry(selResult.packagePath(), "manifest.json").contains("\"kind\": \"selection\""));

        ExportResult backupResult = new FullBackupExporter(ctx)
                .export(ExportRequest.ofFullBackup(dir, false));
        assertEquals(1, backupResult.fileCount());
        String backupManifest = readEntry(backupResult.packagePath(), "manifest.json");
        assertTrue(backupManifest.contains("\"kind\": \"backup\""));
    }

    @Test
    @DisplayName("含私密材料且私密空间未解锁时抛 E202，不产生任何输出文件")
    void lockedVaultBlocksPrivateExport() throws IOException {
        Path dir = tempDir();
        Path src = dir.resolve("secret.pdf");
        Files.writeString(src, "secret", StandardCharsets.UTF_8);

        Material privateMaterial = new Material("secret.pdf", src.toString(), null, 6, "h");
        privateMaterial.setId("m-1");
        privateMaterial.setIsPrivate(true);

        SelectionExportExporter exporter = new SelectionExportExporter(new ExportContext(
                id -> privateMaterial,
                query -> List.of(),
                () -> false,                       // 私密空间锁定
                i -> Files.readAllBytes(src)));

        ExportException e = assertThrows(ExportException.class,
                () -> exporter.export(ExportRequest.ofSelection(List.of("m-1"), dir)));
        assertEquals("E202", e.getCode());
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".zip")), "校验失败时不应写出压缩包");
        }
    }

    @Test
    @DisplayName("单个文件读不出来时只跳过该条目，其余文件照常导出")
    void brokenItemIsSkippedNotFatal() throws IOException {
        Path dir = tempDir();
        Path good = dir.resolve("good.txt");
        Files.writeString(good, "ok", StandardCharsets.UTF_8);

        List<ExportItem> items = List.of(
                new ExportItem("m-1", "good.txt", good.toString(), 2, false),
                new ExportItem("m-2", "missing.txt", dir.resolve("missing.txt").toString(), 5, false));

        StubExporter exporter = new StubExporter(new ExportContext(
                id -> null, q -> List.of(), () -> true,
                i -> Files.readAllBytes(Path.of(i.storedPath()))), items);

        ExportResult r = exporter.export(ExportRequest.ofFullBackup(dir, false));
        assertEquals(1, r.fileCount());
        assertTrue(r.skipped().contains("missing.txt"));
    }

    private static String readEntry(Path zip, String name) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (name.equals(e.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
