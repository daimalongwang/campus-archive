package com.campus.archive.pattern.export;

import com.campus.archive.dto.MaterialQuery;
import com.campus.archive.entity.Material;

import java.util.List;
import java.util.function.Function;

/**
 * 导出模板所需的协作对象。
 *
 * <p>刻意使用 JDK 自带的函数式接口而不是直接注入 DAO：一来模板包不会依赖数据访问层，
 * 二来单元测试可以用 lambda 提供假数据，不必启动数据库。
 * 同时避免了"把绑定在已关闭会话上的 Mapper 传出去"这类隐蔽错误。</p>
 *
 * @param materialById  按材料 ID 取材料（选定材料导出时用）
 * @param allMaterials  按条件取材料（全量备份时用于取全部有效材料）
 * @param vaultGate     私密空间是否已解锁
 * @param contentReader 读取材料明文字节（私密材料在此解密）
 */
public record ExportContext(Function<String, Material> materialById,
                            Function<MaterialQuery, List<Material>> allMaterials,
                            VaultGate vaultGate,
                            MaterialContentReader contentReader) {
}
