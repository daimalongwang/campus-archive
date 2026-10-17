package com.campus.archive.pattern.export;

import com.campus.archive.dto.ExportItem;
import com.campus.archive.dto.ExportRequest;
import com.campus.archive.entity.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板方法的具体实现 B：全量档案备份（对应需求 UR-09 / AC-10）。
 *
 * <p>与 A 的差异恰好体现了模板方法的收益：骨架一行没改，只换了"收集什么"——
 * 这里收集全部有效材料，再通过 extraManifestJson 钩子把导出选项写进清单。</p>
 */
public class FullBackupExporter extends AbstractArchiveExporter {

    public FullBackupExporter(ExportContext ctx) {
        super(ctx);
    }

    @Override
    protected List<ExportItem> collect(ExportRequest request) {
        List<Material> all = ctx.allMaterials().apply(new com.campus.archive.dto.MaterialQuery());

        List<ExportItem> out = new ArrayList<>(all.size());
        for (Material m : all) {
            if (m.getIsPrivate() && !request.isIncludePrivate()) continue;
            out.add(new ExportItem(m.getId(), m.getFileName(), m.getStoredPath(), m.getFileSize(), m.getIsPrivate()));
        }
        return out;
    }

    @Override
    protected String emptyMessage() {
        return "个人档案中还没有任何材料，无法备份";
    }

    @Override
    protected String packageKind() {
        return "backup";
    }

    @Override
    protected boolean requiresUnlockedVault(ExportRequest request) {
        return request.isIncludePrivate();
    }

    @Override
    protected Map<String, Object> extraManifest(ExportRequest request) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("includePrivate", request.isIncludePrivate());
        return extra;
    }

    @Override
    public String describe() {
        return "全量档案备份";
    }
}
