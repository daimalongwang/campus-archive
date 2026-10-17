package com.campus.archive.config;

import com.campus.archive.dao.*;
import com.campus.archive.infra.LocalDateTimeTypeHandler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地数据访问基础设施（详细设计 7.3 节）。
 * 全部数据落在用户目录下的 .archive-app/，单文件 SQLite 库 + 本地文件目录，无任何网络出口。
 */
public final class Database {

    private static Database instance;

    private final Path home;
    private final SqlSessionFactory factory;

    private Database(Path home) {
        this.home = home;
        try {
            Files.createDirectories(home);
            for (String d : new String[]{"files", "vault", "backup", "logs"}) {
                Files.createDirectories(home.resolve(d));
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法创建应用数据目录：" + home, e);
        }
        initSchema();
        this.factory = buildFactory();
    }

    /** 默认落在 %USERPROFILE%\.archive-app；可用 -Darchive.home=... 覆盖。 */
    public static synchronized Database get() {
        if (instance == null) {
            String override = System.getProperty("archive.home");
            Path home = override != null && !override.isBlank()
                    ? Path.of(override)
                    : Path.of(System.getProperty("user.home"), ".archive-app");
            instance = new Database(home);
        }
        return instance;
    }

    /** 在指定目录打开一个独立实例，供单元测试与多档案场景使用。 */
    public static Database open(Path home) {
        return new Database(home);
    }

    public Path home() { return home; }
    public Path dir(String name) { return home.resolve(name); }
    public Path dbFile() { return home.resolve("archive.db"); }
    public SqlSessionFactory factory() { return factory; }

    private String jdbcUrl() {
        return "jdbc:sqlite:" + dbFile().toAbsolutePath();
    }

    private void initSchema() {
        String sql = readResource("/db/schema.sql");
        try (Connection c = DriverManager.getConnection(jdbcUrl()); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            for (String stmt : splitStatements(sql)) {
                if (!stmt.isBlank()) st.execute(stmt);
            }
            migrateLegacySchema(st);
        } catch (Exception e) {
            throw new IllegalStateException("数据库初始化失败", e);
        }
    }

    /**
     * 老版本数据库的兼容升级。
     *
     * <p>建表语句用了 IF NOT EXISTS，所以已经有 category 表的档案不会自动获得新列，
     * 这里补一次。列已存在时 SQLite 会报错，忽略即可——这正是"幂等升级"的常用做法。</p>
     */
    private void migrateLegacySchema(Statement st) {
        try {
            st.execute("ALTER TABLE category ADD COLUMN parent_id VARCHAR(36)");
        } catch (SQLException ignored) {
            // 列已存在，无需迁移
        }
    }

    private SqlSessionFactory buildFactory() {
        UnpooledDataSource ds = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl(), null, null);
        Environment env = new Environment("prototype", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.setMapUnderscoreToCamelCase(true);
        // 关键：我系统一按 ISO 文本存放，不依赖驱动对 java.time 的支持
        cfg.getTypeHandlerRegistry().register(LocalDateTime.class, new LocalDateTimeTypeHandler());
        cfg.addMapper(CategoryDao.class);
        cfg.addMapper(MaterialDao.class);
        cfg.addMapper(SecurityDao.class);
        cfg.addMapper(ConfigDao.class);
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    static String readResource(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("缺少资源：" + path);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().startsWith("--")) continue;
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取资源失败：" + path, e);
        }
    }

    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String part : sql.split(";")) {
            cur.append(part).append(';');
            if (!part.isBlank()) {
                out.add(part.trim());
                cur.setLength(0);
            }
        }
        return out;
    }
}
