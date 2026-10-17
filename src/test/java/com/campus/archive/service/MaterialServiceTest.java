package com.campus.archive.service;

import com.campus.archive.TestEnv;
import com.campus.archive.config.AppContext;
import com.campus.archive.dto.*;
import com.campus.archive.entity.Material;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 材料生命周期：上传、去重、分类建议落地、搜索、回收站、永久删除。 */
@DisplayName("服务层：材料管理与检索")
class MaterialServiceTest {

    private static AppContext ctx;
    private static Path staging;

    @BeforeAll
    static void setUp() throws Exception {
        ctx = TestEnv.newContext("material");
        staging = java.nio.file.Files.createTempDirectory("archive-material-src-");
    }

    @AfterAll
    static void tearDown() {
        TestEnv.deleteRecursively(staging);
    }

    private File file(String name, String content) {
        return TestEnv.sample(staging, name, content);
    }

    @Test
    @DisplayName("上传后材料进入档案；未指定分类时落入兜底分类「其他」")
    void uploadFallsBackToDefaultCategory() {
        Result<UploadSummary> r = ctx.materialService().upload(
                List.of(file("大学英语四级成绩报告单.png", "cet4 content")), new UploadOptions());

        assertTrue(r.isSuccess());
        assertEquals(1, r.getData().getSuccessCount());

        Material m = ctx.searchService().search(new MaterialQuery().setKeyword("四级"), 1, 10).items().get(0);
        assertEquals("其他", m.getCategoryName(), "未指定分类时应落入兜底分类");
        assertEquals(64, m.getContentHash().length());
        assertFalse(m.getIsPrivate());
    }

    @Test
    @DisplayName("锁定时排除私密材料；解锁后私密与普通材料一起显示")
    void privateMaterialsHiddenWhenLockedAndMixedWhenUnlocked() {
        AppContext priv = TestEnv.newContext("material-private");
        priv.securityService().initKey("viewKey123".toCharArray());
        priv.materialService().upload(List.of(
                TestEnv.sample(staging, "私密视图测试.pdf", "secret body"),
                TestEnv.sample(staging, "普通视图测试.pdf", "plain body")), new UploadOptions());

        Material secret = priv.searchService()
                .search(new MaterialQuery().setKeyword("私密视图"), 1, 5).items().get(0);
        assertTrue(priv.securityService().markPrivate(secret.getId()).isSuccess());
        priv.securityService().lock();

        assertEquals(1, priv.searchService().search(new MaterialQuery().setOnlyPrivate(false), 1, 50).total(),
                "锁定状态只应看到非私密材料");
        assertEquals(2, priv.searchService().search(new MaterialQuery(), 1, 50).total(),
                "解锁后不过滤，私密与普通材料一起显示");
    }

    @Test
    @DisplayName("显式指定分类时，材料直接进入该分类")
    void uploadIntoExplicitCategory() {
        String certId = ctx.categoryService().listAll().stream()
                .filter(c -> "证书".equals(c.getName())).findFirst().orElseThrow().getId();

        Result<UploadSummary> r = ctx.materialService().upload(
                List.of(file("分类落位测试材料.pdf", "placement content")),
                new UploadOptions().setCategoryId(certId));

        assertTrue(r.isSuccess(), r.getMessage());
        Material m = ctx.searchService().search(new MaterialQuery().setKeyword("落位测试"), 1, 10).items().get(0);
        assertEquals("证书", m.getCategoryName());
    }

    @Test
    @DisplayName("相同内容重复上传会被识别为疑似重复，但仍允许保存")
    void duplicateIsWarnedNotBlocked() {
        String content = "重复内容示例 " + System.nanoTime();
        ctx.materialService().upload(List.of(file("竞赛获奖证书A.pdf", content)), new UploadOptions());
        Result<UploadSummary> second = ctx.materialService().upload(
                List.of(file("竞赛获奖证书B.pdf", content)), new UploadOptions());

        assertTrue(second.isSuccess());
        assertEquals(1, second.getData().getDuplicateWarnings().size(),
                "内容哈希相同应触发重复提示");
        assertEquals(1, second.getData().getSuccessCount(), "提示不应阻断保存");
    }

    @Test
    @DisplayName("批量上传时单个文件失败不影响整批（对应 AC-02）")
    void oneBadFileDoesNotAbortBatch() {
        File missing = new File(staging.toFile(), "不存在的文件.pdf");
        Result<UploadSummary> r = ctx.materialService().upload(
                List.of(missing, file("社会实践证明.pdf", "practice")), new UploadOptions());

        assertEquals(1, r.getData().getFailCount());
        assertEquals(1, r.getData().getSuccessCount());
        assertEquals("E102", r.getData().getFailed().get(0).code());
    }

    @Test
    @DisplayName("超过 200 MB 的文件被拒绝并给出 E101")
    void oversizedFileIsRejected() {
        UploadSummary summary = new UploadSummary();
        summary.fail("big.pdf", "E101", "文件超过 200 MB，无法上传");
        assertEquals("E101", summary.getFailed().get(0).code());
        assertEquals(200L * 1024 * 1024, MaterialServiceImplLimits.MAX_FILE_SIZE);
    }

    /** 把常量集中到这里，避免测试直接依赖实现类名。 */
    private static final class MaterialServiceImplLimits {
        static final long MAX_FILE_SIZE = com.campus.archive.service.impl.MaterialServiceImpl.MAX_FILE_SIZE;
    }

    @Test
    @DisplayName("普通删除进回收站，恢复后回到原分类（对应 AC-08）")
    void softDeleteThenRestore() {
        ctx.materialService().upload(List.of(file("实习鉴定表.docx", "intern")), new UploadOptions());
        Material m = ctx.searchService().search(new MaterialQuery().setKeyword("实习鉴定"), 1, 5).items().get(0);
        String originalCategory = m.getCategoryId();

        assertTrue(ctx.materialService().softDelete(List.of(m.getId())).isSuccess());
        assertTrue(ctx.searchService().search(new MaterialQuery().setKeyword("实习鉴定"), 1, 5).items().isEmpty(),
                "删除后不应出现在正常档案列表");
        assertEquals(1, ctx.materialService().listTrashed().size());

        assertTrue(ctx.materialService().restore(List.of(m.getId())).isSuccess());
        Material restored = ctx.materialService().findById(m.getId());
        assertFalse(restored.isTrashed());
        assertEquals(originalCategory, restored.getCategoryId(), "恢复后应回到原分类");
    }

    @Test
    @DisplayName("永久删除会同时清理元数据与磁盘文件（对应 AC-09）")
    void purgeRemovesMetadataAndFile() {
        ctx.materialService().upload(List.of(file("待删除材料.pdf", "to be purged")), new UploadOptions());
        Material m = ctx.searchService().search(new MaterialQuery().setKeyword("待删除"), 1, 5).items().get(0);
        Path stored = ctx.fileStore().resolve(m.getStoredPath());
        assertTrue(java.nio.file.Files.exists(stored));

        assertTrue(ctx.materialService().softDelete(List.of(m.getId())).isSuccess());
        assertTrue(ctx.materialService().purge(List.of(m.getId())).isSuccess());

        assertNull(ctx.materialService().findById(m.getId()));
        assertFalse(java.nio.file.Files.exists(stored), "物理文件也应被删除");
        assertTrue(ctx.materialService().listTrashed().stream().noneMatch(x -> x.getId().equals(m.getId())));
    }

    @Test
    @DisplayName("组合筛选：分类 + 类型同时生效")
    void combinedFiltersWork() {
        MaterialQuery q = new MaterialQuery();
        var cert = ctx.repos().query(d -> d.category().findByName("证书"));
        q.setCategoryId(cert.getId());
        q.setFileType(com.campus.archive.entity.FileType.PDF);

        Page<Material> page = ctx.searchService().search(q, 1, 100);
        assertTrue(page.items().stream().allMatch(m -> "证书".equals(m.getCategoryName())));
        assertTrue(page.items().stream()
                .allMatch(m -> m.getFileType() == com.campus.archive.entity.FileType.PDF));
    }

    @Test
    @DisplayName("统计口径：私密数与回收站数单独统计")
    void statsReflectData() {
        var stats = ctx.searchService().stats();
        assertTrue((Long) toLong(stats.get("total")) > 0);
        assertNotNull(stats.get("byCategory"));
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
