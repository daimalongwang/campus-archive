package com.campus.archive.config;

import com.campus.archive.dao.*;
import org.apache.ibatis.session.SqlSession;

/**
 * 一次会话中可用的全部 Mapper。
 * 用 record 打包，避免每个服务方法都写 7 行 getMapper。
 */
public record DaoBundle(MaterialDao material,
                        CategoryDao category,
                        SecurityDao security,
                        ConfigDao config) {

    public static DaoBundle of(SqlSession session) {
        return new DaoBundle(
                session.getMapper(MaterialDao.class),
                session.getMapper(CategoryDao.class),
                session.getMapper(SecurityDao.class),
                session.getMapper(ConfigDao.class));
    }
}
