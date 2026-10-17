package com.campus.archive.dao;

import com.campus.archive.dto.MaterialQuery;
import com.campus.archive.entity.Material;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 材料数据访问。只做数据操作，不含任何业务判断（详细设计 6.4 节 R1）。
 * 动态 SQL 用注解内的 &lt;script&gt; 表达，避免为原型引入额外的 XML 文件。
 */
public interface MaterialDao {

    String BASE_SELECT = """
            SELECT m.id, m.file_name, m.stored_path, m.file_type, m.file_size, m.content_hash,
                   m.category_id, m.is_private, m.used_at, m.created_at, m.deleted_at,
                   c.name AS category_name
            FROM material m
            LEFT JOIN category c ON c.id = m.category_id
            """;

    String WHERE_CLAUSE = """
            <where>
              <choose>
                <when test="q.onlyTrashed">m.deleted_at IS NOT NULL</when>
                <when test="q.includeTrashed">1 = 1</when>
                <otherwise>m.deleted_at IS NULL</otherwise>
              </choose>
              <if test="q.keywordLike != null">
                AND (m.file_name LIKE #{q.keywordLike}
                     OR c.name LIKE #{q.keywordLike})
              </if>
              <if test="q.categoryIds != null and q.categoryIds.size() > 0">
                AND m.category_id IN
                <foreach collection="q.categoryIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
              </if>
              <if test="q.fileType != null">AND m.file_type = #{q.fileType}</if>
              <if test="q.onlyPrivate != null and q.onlyPrivate">AND m.is_private = 1</if>
              <if test="q.onlyPrivate != null and q.onlyPrivate == false">AND m.is_private = 0</if>
              <if test="q.createdFrom != null">AND m.created_at &gt;= #{q.createdFrom}</if>
              <if test="q.createdTo != null">AND m.created_at &lt;= #{q.createdTo}</if>
            </where>
            """;

    @Insert("""
            INSERT INTO material(id, file_name, stored_path, file_type, file_size, content_hash,
                                 category_id, is_private, used_at, created_at, deleted_at)
            VALUES(#{id}, #{fileName}, #{storedPath}, #{fileType}, #{fileSize}, #{contentHash},
                   #{categoryId}, #{isPrivate}, #{usedAt}, #{createdAt}, #{deletedAt})
            """)
    int insert(Material m);

    @Select(BASE_SELECT + " WHERE m.id = #{id}")
    Material findById(@Param("id") String id);

    /** 重复文件检测的二级判定：文件名 + 大小相同后按哈希确认（详细设计 9.2 节）。 */
    @Select("""
            SELECT id FROM material
            WHERE content_hash = #{hash} AND file_size = #{size} AND deleted_at IS NULL
            LIMIT 1
            """)
    String findDuplicateId(@Param("hash") String hash, @Param("size") long size);

    @Select("<script>" + BASE_SELECT + WHERE_CLAUSE
            + " ORDER BY ${orderBy} <choose><when test=\"q.desc\">DESC</when><otherwise>ASC</otherwise></choose>"
            + " LIMIT #{limit} OFFSET #{offset}</script>")
    List<Material> search(@Param("q") MaterialQuery q,
                          @Param("orderBy") String orderBy,
                          @Param("limit") int limit,
                          @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM material m LEFT JOIN category c ON c.id = m.category_id "
            + WHERE_CLAUSE + "</script>")
    int countSearch(@Param("q") MaterialQuery q);

    @Select(BASE_SELECT + " WHERE m.deleted_at IS NOT NULL ORDER BY m.deleted_at DESC")
    List<Material> listTrashed();

    @Select(BASE_SELECT + " WHERE m.deleted_at IS NULL ORDER BY m.created_at DESC LIMIT #{limit}")
    List<Material> listRecent(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM material WHERE deleted_at IS NULL")
    int countActive();

    @Select("SELECT COUNT(*) FROM material WHERE deleted_at IS NULL AND is_private = 1")
    int countPrivate();

    /** 统计视图：各分类的材料数量。 */
    @Select("""
            SELECT c.name AS name, COUNT(m.id) AS cnt
            FROM category c LEFT JOIN material m ON m.category_id = c.id AND m.deleted_at IS NULL
            GROUP BY c.id, c.name ORDER BY c.sort
            """)
    List<Map<String, Object>> countByCategory();

    @Update("UPDATE material SET file_name = #{fileName} WHERE id = #{id}")
    int updateName(@Param("id") String id, @Param("fileName") String fileName);

    @Update("UPDATE material SET category_id = #{categoryId} WHERE id = #{id}")
    int updateCategory(@Param("id") String id, @Param("categoryId") String categoryId);

    @Update("UPDATE material SET used_at = #{usedAt} WHERE id = #{id}")
    int touchUsed(@Param("id") String id, @Param("usedAt") LocalDateTime usedAt);

    @Update("UPDATE material SET is_private = #{isPrivate} WHERE id = #{id}")
    int updatePrivate(@Param("id") String id, @Param("isPrivate") boolean isPrivate);

    @Update("UPDATE material SET deleted_at = #{deletedAt} WHERE id = #{id}")
    int markDeleted(@Param("id") String id, @Param("deletedAt") LocalDateTime deletedAt);

    @Update("""
            <script>
            UPDATE material SET deleted_at = NULL WHERE id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int clearDeleted(@Param("ids") List<String> ids);

    @Delete("""
            <script>
            DELETE FROM material WHERE id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteByIds(@Param("ids") List<String> ids);

    /* ---- 回收站记录 ---- */

    @Insert("""
            INSERT OR REPLACE INTO trash_record(id, material_id, deleted_at, origin_category_id)
            VALUES(#{id}, #{materialId}, #{deletedAt}, #{originCategoryId})
            """)
    int insertTrash(com.campus.archive.entity.TrashRecord record);

    @Select("SELECT * FROM trash_record WHERE material_id = #{materialId}")
    com.campus.archive.entity.TrashRecord findTrash(@Param("materialId") String materialId);

    @Delete("DELETE FROM trash_record WHERE material_id = #{materialId}")
    int deleteTrash(@Param("materialId") String materialId);

    /* ---- 永久删除时的级联清理 ---- */


    @Delete("DELETE FROM vault_entry WHERE material_id = #{materialId}")
    int deleteVaultEntry(@Param("materialId") String materialId);
}
