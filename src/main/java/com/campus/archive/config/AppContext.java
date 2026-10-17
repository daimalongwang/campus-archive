package com.campus.archive.config;

import com.campus.archive.dto.MaterialQuery;
import com.campus.archive.infra.FileStore;
import com.campus.archive.pattern.export.*;
import com.campus.archive.service.*;
import com.campus.archive.service.impl.*;

/**
 * 应用装配根（简单工厂 + 单例）。
 *
 * <p>原型阶段用手工装配代替 Spring 容器：依赖关系只有十几条，显式 new 出来反而比引入容器
 * 更直观，也更容易在答辩时讲清"谁依赖谁"。设计模式的装配点集中在本类，一眼可见。</p>
 */
public final class AppContext {

    private static AppContext instance;

    private final Database database;
    private final Repositories repos;
    private final FileStore fileStore;

    private final MaterialService materialService;
    private final PrivateSecurityService securityService;
    private final SearchService searchService;
    private final CategoryService categoryService;
    private final ExportService exportService;
    private final BackupService backupService;


    private AppContext(Database database) {
        this.database = database;
        this.repos = new Repositories(database.factory());
        this.fileStore = new FileStore(database.dir("files"), database.dir("vault"));

        this.securityService = new PrivateSecurityServiceImpl(repos, fileStore);
        this.materialService = new MaterialServiceImpl(repos, fileStore, securityService);
        this.searchService = new SearchServiceImpl(repos);
        this.categoryService = new CategoryServiceImpl(repos);

        // ---------- 模式 ② 模板方法模式：两个具体导出器共用同一套流程骨架 ----------
        ExportContext exportContext = new ExportContext(
                id -> repos.query(d -> d.material().findById(id)),
                query -> repos.query(d -> d.material().search(
                        query == null ? new MaterialQuery() : query, "m.created_at", 100_000, 0)),
                securityService::isUnlocked,
                new MaterialContentReaderImpl(fileStore, securityService));

        ArchiveExporter selectionExporter = new SelectionExportExporter(exportContext);
        ArchiveExporter backupExporter = new FullBackupExporter(exportContext);

        this.backupService = new BackupServiceImpl(repos, fileStore, backupExporter);
        this.exportService = new ExportServiceImpl(selectionExporter);

    }

    public static synchronized AppContext get() {
        if (instance == null) instance = new AppContext(Database.get());
        return instance;
    }

    /** 在指定数据目录上组装一套独立的协作对象，供单元测试使用。 */
    public static AppContext forTesting(java.nio.file.Path home) {
        return new AppContext(Database.open(home));
    }

    public Database database() { return database; }
    public Repositories repos() { return repos; }
    public FileStore fileStore() { return fileStore; }

    public MaterialService materialService() { return materialService; }
    public PrivateSecurityService securityService() { return securityService; }
    public SearchService searchService() { return searchService; }
    public CategoryService categoryService() { return categoryService; }
    public BackupService backupService() { return backupService; }
    public ExportService exportService() { return exportService; }

}
