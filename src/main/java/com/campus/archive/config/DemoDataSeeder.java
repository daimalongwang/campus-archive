package com.campus.archive.config;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 演示数据播种。
 *
 * <p>原型首次启动时如果档案为空，就把内置的示例材料释放到临时目录并走一遍真实的上传流程，
 * 让现场演示可以从"已有数据"直接进入搜索、打包、导出等环节。</p>
 *
 * <p>示例文件是<b>真实可打开的文件</b>（真 PDF / 真 DOCX / 真 PNG / 真 CSV），
 * 随工程放在 resources/demo-samples 下，不是运行时拼出来的文本。</p>
 */
public final class DemoDataSeeder {

    /** 资源名 → 用户看到的显示名。资源名用 ASCII，避免中文与 "+" 在资源查找时的编码问题。 */
    private static final String[][] SAMPLES = {
            {"s01-cet4.pdf", "大学英语四级成绩报告单.pdf"},
            {"s02-mcm-award.pdf", "全国大学生数学建模竞赛获奖证书.pdf"},
            {"s03-volunteer.pdf", "志愿服务时长证明.pdf"},
            {"s04-research.docx", "科研立项结题报告.docx"},
            {"s05-internship.docx", "毕业实习鉴定表.docx"},
            {"s06-scholarship.png", "国家励志奖学金证书.png"},
            {"s07-putonghua.png", "普通话水平测试等级证书.png"},
            {"s08-internet-plus.png", "互联网+创新创业大赛校级三等奖.png"},
            {"s09-checklist.csv", "综合测评材料清单.csv"},
    };

    private DemoDataSeeder() {
    }

    /** 返回本次播种创建的材料数量。 */
    public static int seed(AppContext ctx, Path stagingDir) {
        int created = 0;
        try {
            Files.createDirectories(stagingDir);

            UploadOptionsHolder holder = new UploadOptionsHolder();
            for (String[] sample : SAMPLES) {
                File f = release(stagingDir, sample[0], sample[1]);
                if (f == null) continue;
                var result = ctx.materialService().upload(List.of(f), holder.options());
                created += result.getData().getSuccessCount();
            }

            seedSubCategories(ctx);

        } catch (IOException e) {
            System.err.println("[演示数据] 生成失败：" + e.getMessage());
        }
        return created;
    }

    /**
     * 建几个子分类并把示例材料归位，让分类树一开始就有层次，
     * 而不是七个平铺的顶层节点——用户才好理解"分类可以自己往下建"。
     */
    private static void seedSubCategories(AppContext ctx) {
        var cert = byName(ctx, "证书");
        var contest = byName(ctx, "竞赛");

        if (cert != null) {
            var english = ctx.categoryService().create(cert.getId(), "英语等级");
            if (english.isSuccess()) {
                String engId = english.getData().getId();
                ctx.categoryService().create(engId, "四级");
                ctx.categoryService().create(engId, "六级");
                move(ctx, "大学英语四级成绩报告单.pdf", engId);
                move(ctx, "普通话水平测试等级证书.png", engId);
            }
        }
        if (contest != null) {
            var innovation = ctx.categoryService().create(contest.getId(), "创新创业");
            if (innovation.isSuccess()) {
                move(ctx, "互联网+创新创业大赛校级三等奖.png", innovation.getData().getId());
            }
        }
    }

    private static com.campus.archive.entity.Category byName(AppContext ctx, String name) {
        return ctx.categoryService().listAll().stream()
                .filter(c -> name.equals(c.getName())).findFirst().orElse(null);
    }

    private static void move(AppContext ctx, String fileName, String categoryId) {
        ctx.searchService().search(new com.campus.archive.dto.MaterialQuery(), 1, 100).items().stream()
                .filter(m -> fileName.equals(m.getFileName()))
                .findFirst()
                .ifPresent(m -> ctx.materialService().moveToCategory(m.getId(), categoryId));
    }

    /** 把内置示例文件释放到临时目录；缺少资源时跳过并给出提示，不影响启动。 */
    private static File release(Path dir, String resourceName, String displayName) throws IOException {
        Path target = dir.resolve(displayName);
        try (InputStream in = DemoDataSeeder.class.getResourceAsStream("/demo-samples/" + resourceName)) {
            if (in == null) {
                System.err.println("[演示数据] 缺少内置资源：" + resourceName);
                return null;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toFile();
    }

    /** 上传选项的小包装，避免在循环里反复 new。 */
    private record UploadOptionsHolder(com.campus.archive.dto.UploadOptions options) {
        UploadOptionsHolder() {
            this(new com.campus.archive.dto.UploadOptions());
        }
    }
}
