package com.campus.archive.service;

import com.campus.archive.TestEnv;
import com.campus.archive.config.AppContext;
import com.campus.archive.dto.ExportResult;
import com.campus.archive.dto.MaterialQuery;
import com.campus.archive.dto.Result;
import com.campus.archive.dto.UploadOptions;
import com.campus.archive.entity.Material;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 备份导出 → 跨设备导入的完整往返。
 * 每个用例使用独立的临时目录，互不依赖执行顺序。
 */
@DisplayName("服务层：备份与迁移")
class BackupServiceTest {

    private static AppContext source;
    private static AppContext target;
    private static Path staging;

    @BeforeAll
    static void setUp() throws Exception {
        source = TestEnv.newContext("backup-src");
        target = TestEnv.newContext("backup-dst");
        staging = Files.createTempDirectory("archive-backup-src-");

        source.materialService().upload(List.of(
                TestEnv.sample(staging, "大学英语六级成绩报告单.pdf", "cet6 分数 520"),
                TestEnv.sample(staging, "挑战杯竞赛获奖证书.png", "award image"),
                TestEnv.sample(staging, "志愿服务证明.pdf", "volunteer hours")), new UploadOptions());
    }

    @AfterAll
    static void tearDown() {
        TestEnv.deleteRecursively(staging);
    }

    private static Path freshDir(String tag) throws IOException {
        return Files.createTempDirectory("archive-backup-" + tag + "-");
    }

    private static List<String> listEntries(Path zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) names.add(e.getName());
        }
        return names;
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    @Test
    @DisplayName("A. 全量备份生成 zip，含 manifest.json 与全部材料")
    void exportProducesArchive() throws Exception {
        Path dir = freshDir("a");
        Result<ExportResult> r = source.backupService().exportAll(dir, false);
        assertTrue(r.isSuccess(), r.getMessage());

        ExportResult result = r.getData();
        assertTrue(Files.exists(result.packagePath()));
        assertEquals(3, result.fileCount(), "3 份材料（manifest 不计入）");
        assertEquals(64, result.checksum().length());

        List<String> entries = listEntries(result.packagePath());
        assertTrue(entries.contains("manifest.json"));
        assertEquals(4, entries.size(), "实际条目：" + entries);
    }

    @Test
    @DisplayName("B. 把备份包导入另一个档案，材料被还原（对应 AC-10）")
    void importRestoresIntoFreshArchive() throws Exception {
        Path dir = freshDir("b");
        Path pkg = source.backupService().exportAll(dir, false).getData().packagePath();

        assertEquals(0L, toLong(target.searchService().stats().get("total")), "导入前目标档案应为空");

        Result<String> r = target.backupService().importFrom(pkg);
        assertTrue(r.isSuccess(), r.getMessage());
        assertTrue(r.getData().contains("新增材料 3 个"), r.getData());

        assertEquals(3L, toLong(target.searchService().stats().get("total")));

        Material imported = target.searchService()
                .search(new MaterialQuery().setKeyword("六级"), 1, 5).items().get(0);
        assertTrue(Files.exists(target.fileStore().resolve(imported.getStoredPath())),
                "导入后原始文件应真实落盘");
    }

    @Test
    @DisplayName("C. 导入非备份包时明确报错，且不污染既有数据")
    void importingWrongKindIsRejected() throws Exception {
        long before = toLong(target.searchService().stats().get("total"));

        Path dir = freshDir("c");
        Path bogus = dir.resolve("not-a-backup.zip");
        Files.writeString(bogus, "this is not a zip at all");

        Result<String> r = target.backupService().importFrom(bogus);
        assertFalse(r.isSuccess());
        assertEquals("E500", r.getCode());
        assertEquals(before, toLong(target.searchService().stats().get("total")), "失败不得改动既有数据");
    }

    @Test
    @DisplayName("D. 私密备份包也能正确导出与导入，内容可解密还原")
    void privateBackupRoundTrip() throws Exception {
        AppContext priv = TestEnv.newContext("backup-priv");
        Path staging2 = Files.createTempDirectory("archive-backup-priv-src-");
        String secret = "私密获奖材料正文，应当以密文形式进入备份包";

        priv.securityService().initKey("backupKey123".toCharArray());
        priv.materialService().upload(List.of(
                TestEnv.sample(staging2, "国家奖学金获奖证明.pdf", secret)), new UploadOptions());
        Material m = priv.searchService().search(new MaterialQuery().setKeyword("奖学金"), 1, 5).items().get(0);
        assertTrue(priv.securityService().markPrivate(m.getId()).isSuccess());

        Path dir = freshDir("d");
        priv.securityService().lock();
        Result<ExportResult> locked = priv.backupService().exportAll(dir, true);
        assertFalse(locked.isSuccess());
        assertEquals("E202", locked.getCode(), "未解锁时不允许把私密材料写进备份包");

        priv.securityService().unlock("backupKey123".toCharArray());
        Result<ExportResult> ok = priv.backupService().exportAll(dir, true);
        assertTrue(ok.isSuccess(), ok.getMessage());
        assertEquals(1, ok.getData().fileCount(), "1 份私密材料");

        // 导出的私密材料是解密后的明文内容，因此导入后可被直接读取
        AppContext restored = TestEnv.newContext("backup-priv-dst");
        Result<String> imported = restored.backupService().importFrom(ok.getData().packagePath());
        assertTrue(imported.isSuccess(), imported.getMessage());
        Material copy = restored.searchService().search(new MaterialQuery().setKeyword("奖学金"), 1, 5).items().get(0);
        assertEquals(secret, Files.readString(restored.fileStore().resolve(copy.getStoredPath())));

        TestEnv.deleteRecursively(staging2);
    }
}
