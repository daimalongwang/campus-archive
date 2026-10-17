package com.campus.archive.service;

import com.campus.archive.TestEnv;
import com.campus.archive.config.AppContext;
import com.campus.archive.dto.MaterialQuery;
import com.campus.archive.dto.Result;
import com.campus.archive.dto.UploadOptions;
import com.campus.archive.entity.Category;
import com.campus.archive.entity.Material;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分类树（Windows 资源管理器式）与全库搜索。
 *
 * <p>两条核心规则：</p>
 * <ol>
 *   <li>点父分类要能看到子分类里的材料——筛选按<b>整棵子树</b>生效；</li>
 *   <li>关键词与分类筛选叠加，不再跨分类命中。</li>
 * </ol>
 * <p>删除分类必须保证材料不悬空，因此每个删除路径都要验一遍材料去向。</p>
 */
@DisplayName("分类树：增删改 / 子树筛选 / 全库搜索")
class CategoryTreeTest {

    private AppContext ctx;
    private Path staging;

    @BeforeEach
    void setUp() throws Exception {
        ctx = TestEnv.newContext("category-tree");
        staging = Files.createTempDirectory("archive-cat-src-");
    }

    @AfterEach
    void tearDown() {
        TestEnv.deleteRecursively(staging);
    }

    private String cat(String name) {
        return ctx.categoryService().listAll().stream()
                .filter(c -> name.equals(c.getName())).findFirst().orElseThrow().getId();
    }

    private String uploadTo(String categoryId, String fileName) {
        File f = TestEnv.sample(staging, fileName, "内容 " + fileName + " " + System.nanoTime());
        var r = ctx.materialService().upload(List.of(f),
                new UploadOptions().setCategoryId(categoryId));
        assertEquals(1, r.getData().getSuccessCount());
        return ctx.searchService().search(new MaterialQuery(), 1, 100).items().stream()
                .filter(m -> m.getFileName().equals(fileName)).findFirst().orElseThrow().getId();
    }

    @Test
    @DisplayName("A. 新建子分类后父子关系正确，且能多层嵌套")
    void createNestedCategories() {
        Result<Category> english = ctx.categoryService().create(cat("证书"), "英语等级");
        assertTrue(english.isSuccess(), english.getMessage());
        assertEquals(cat("证书"), english.getData().getParentId());

        Result<Category> cet = ctx.categoryService().create(english.getData().getId(), "四级");
        assertTrue(cet.isSuccess());

        List<Category> all = ctx.categoryService().listAll();
        assertEquals(9, all.size(), "7 个内置 + 新建的 2 个");
        assertTrue(all.stream().anyMatch(c -> "四级".equals(c.getName())));
    }

    @Test
    @DisplayName("B. 同级重名被拒绝，不同层级可以同名")
    void duplicateNameOnlyBlockedInSameLevel() {
        Result<Category> first = ctx.categoryService().create(cat("证书"), "英语等级");
        assertTrue(first.isSuccess());

        Result<Category> dup = ctx.categoryService().create(cat("证书"), "英语等级");
        assertFalse(dup.isSuccess(), "同一父分类下不能重名");
        assertEquals("E300", dup.getCode());

        Result<Category> other = ctx.categoryService().create(cat("竞赛"), "英语等级");
        assertTrue(other.isSuccess(), "不同父分类下允许同名");
    }

    @Test
    @DisplayName("C. subtreeIds 返回自身与全部后代，且不误伤兄弟节点")
    void subtreeIdsCoverDescendants() {
        String cert = cat("证书");
        String english = ctx.categoryService().create(cert, "英语等级").getData().getId();
        String cet4 = ctx.categoryService().create(english, "四级").getData().getId();
        String cet6 = ctx.categoryService().create(english, "六级").getData().getId();
        String contest = cat("竞赛");

        List<String> ids = ctx.categoryService().subtreeIds(cert);
        assertEquals(4, ids.size(), "证书 + 英语等级 + 四级 + 六级");
        assertTrue(ids.containsAll(List.of(cert, english, cet4, cet6)));
        assertFalse(ids.contains(contest), "兄弟分类不应被带进来");

        assertEquals(List.of(english, cet4, cet6).size(), ctx.categoryService().subtreeIds(english).size());
    }

    @Test
    @DisplayName("D. 按父分类筛选能看到子分类里的材料")
    void parentFilterSeesDescendantMaterials() {
        String cert = cat("证书");
        String english = ctx.categoryService().create(cert, "英语等级").getData().getId();
        String cet4 = ctx.categoryService().create(english, "四级").getData().getId();

        uploadTo(cert, "普通话证书.png");
        uploadTo(cet4, "四级成绩单.pdf");

        // 只按父分类本身筛——会漏掉子分类里的成绩单
        MaterialQuery exactOnly = new MaterialQuery().setCategoryId(cert);
        assertEquals(1, ctx.searchService().search(exactOnly, 1, 50).total());

        // 按整棵子树筛——两份都在
        MaterialQuery subtree = new MaterialQuery().setCategoryIds(ctx.categoryService().subtreeIds(cert));
        assertEquals(2, ctx.searchService().search(subtree, 1, 50).total(),
                "点父分类应该能看到子分类里的材料");
    }

    @Test
    @DisplayName("E. 有子分类时不允许删除父分类")
    void deleteBlockedWhenHasChildren() {
        String cert = cat("证书");
        ctx.categoryService().create(cert, "英语等级");

        Result<Void> r = ctx.categoryService().delete(cert);
        assertFalse(r.isSuccess());
        assertTrue(r.getMessage().contains("子分类"));
        assertNotNull(ctx.repos().query(d -> d.category().findById(cert)), "拒绝后分类应仍存在");
    }

    @Test
    @DisplayName("F. 删除子分类时，其中的材料并入父分类而不是悬空")
    void deleteMovesMaterialsToParent() {
        String cert = cat("证书");
        String english = ctx.categoryService().create(cert, "英语等级").getData().getId();
        String mid = uploadTo(english, "四级成绩单.pdf");

        Result<Void> r = ctx.categoryService().delete(english);
        assertTrue(r.isSuccess(), r.getMessage());

        assertNull(ctx.repos().query(d -> d.category().findById(english)), "分类应被删除");
        assertEquals(cert, ctx.materialService().findById(mid).getCategoryId(),
                "材料应并入父分类，而不是指向一个不存在的分类");
    }

    @Test
    @DisplayName("G. 删除顶层分类时，其中的材料并入兜底分类「其他」")
    void deleteTopLevelMovesMaterialsToFallback() {
        String cert = cat("证书");
        String mid = uploadTo(cert, "普通话证书.png");

        assertTrue(ctx.categoryService().delete(cert).isSuccess());

        assertEquals(cat("其他"), ctx.materialService().findById(mid).getCategoryId());
    }

    @Test
    @DisplayName("H.「其他」是兜底分类，不允许删除；重命名仍可用")
    void fallbackCategoryIsProtected() {
        Result<Void> del = ctx.categoryService().delete(cat("其他"));
        assertFalse(del.isSuccess());
        assertTrue(del.getMessage().contains("其他"));

        assertTrue(ctx.categoryService().rename(cat("其他"), "待整理").isSuccess());
        assertEquals("待整理", ctx.repos().query(d -> d.category().findById(cat("待整理"))).getName());
    }

    @Test
    @DisplayName("I. 关键词搜索与分类筛选叠加，不再跨分类命中")
    void keywordSearchRespectsCategoryScope() {
        String cert = cat("证书");
        String contest = cat("竞赛");
        uploadTo(cert, "数学建模证书-甲.pdf");
        uploadTo(contest, "数学建模证书-乙.pdf");

        // 关键词 + 分类：只在「证书」子树内检索，不应命中「竞赛」里的同名材料
        MaterialQuery scoped = new MaterialQuery()
                .setCategoryIds(ctx.categoryService().subtreeIds(cert))
                .setKeyword("数学建模");
        assertEquals(1, ctx.searchService().search(scoped, 1, 50).total(),
                "关键词搜索应受分类筛选约束");

        // 不限定分类时才覆盖全库
        assertEquals(2, ctx.searchService().search(
                new MaterialQuery().setKeyword("数学建模"), 1, 50).total());
    }
}
