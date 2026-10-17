package com.campus.archive.service;

import com.campus.archive.dto.Result;
import com.campus.archive.dto.UploadOptions;
import com.campus.archive.dto.UploadSummary;
import com.campus.archive.entity.Material;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** 材料全生命周期服务（详细设计 6.5 节接口清单）。所有方法返回 Result，不外抛技术异常。 */
public interface MaterialService {

    /** 批量上传。单个文件失败不中断整批处理，失败原因汇总返回（对应 AC-02）。 */
    Result<UploadSummary> upload(List<File> files, UploadOptions options);

    Result<Void> rename(String id, String newName);

    Result<Void> moveToCategory(String id, String categoryId);

    /** 普通删除：进入回收站，不物理删除（对应 UR-08）。 */
    Result<Void> softDelete(List<String> ids);

    Result<Void> restore(List<String> ids);

    /** 永久删除：调用方必须先完成二次确认。 */
    Result<Void> purge(List<String> ids);

    Material findById(String id);

    List<Material> listTrashed();

    /** 取出可直接预览/下载的本地文件；私密材料返回解密后的临时文件。 */
    Result<Path> materialize(String id);

    Result<Void> touchUsed(String id);
}
