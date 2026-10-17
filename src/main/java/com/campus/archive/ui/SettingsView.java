package com.campus.archive.ui;

import com.campus.archive.config.AppContext;
import com.campus.archive.dto.ExportResult;
import com.campus.archive.dto.Result;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;

/**
 * 我的：低频功能集中管理——安全设置与导入导出。
 */
public class SettingsView extends VBox implements MainWindow.Refreshable {

    private final AppContext ctx;
    private final MainWindow window;
    private final Label securityStatus = UiKit.label("", "muted");

    public SettingsView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        getStyleClass().add("content");
        setSpacing(14);
        build();
        refresh();
    }

    private void build() {
        getChildren().addAll(securityCard(), backupCard(), layoutCard());
    }

    private VBox securityCard() {
        Button init = new Button("设置 / 验证安全密钥");
        init.getStyleClass().addAll("button", "primary-button");
        init.setOnAction(e -> {
            PrivateUnlockDialog.ensureUnlocked(ctx);
            refresh();
        });

        Button lock = new Button("立即锁定私密空间");
        lock.getStyleClass().addAll("button", "ghost-button");
        lock.setOnAction(e -> {
            ctx.securityService().lock();
            UiKit.info("私密空间已锁定，派生密钥已从内存清零");
            refresh();
        });

        HBox actions = new HBox(8, init, lock);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, UiKit.label("私密空间与安全密钥", "card-title"), securityStatus, actions,
                UiKit.label("密钥经 PBKDF2-HMAC-SHA256（10 万轮）派生，系统只保存盐值与校验值，"
                        + "明文密钥从不落盘；私密材料以 AES-256-GCM 加密后存放于 vault/ 目录。", "muted"));
        box.getStyleClass().add("card");
        return box;
    }

    private VBox backupCard() {
        Button export = new Button("导出完整档案备份");
        export.getStyleClass().addAll("button", "primary-button");
        export.setOnAction(e -> onExport());

        Button importBtn = new Button("从备份包导入");
        importBtn.getStyleClass().addAll("button", "ghost-button");
        importBtn.setOnAction(e -> onImport());

        HBox actions = new HBox(8, export, importBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, UiKit.label("导入 / 导出与备份", "card-title"), actions,
                UiKit.label("备份包内含全部材料文件与 manifest.json（清单，含 SHA-256 校验和）。"
                        + "导入采用单事务，失败会整体回滚并清理已写入的文件。", "muted"));
        box.getStyleClass().add("card");
        return box;
    }

    /** 个性化：调整首页模块顺序与尺寸，并可一键恢复默认。 */
    private VBox layoutCard() {
        Button edit = new Button("调整布局");
        edit.getStyleClass().addAll("button", "primary-button");
        edit.setOnAction(e -> window.beginLayoutEdit());

        Button reset = new Button("恢复默认布局");
        reset.getStyleClass().addAll("button", "ghost-button");
        reset.setOnAction(e -> {
            LayoutPlan.defaults().save(ctx.repos());
            window.reloadHomeLayout();
            UiKit.info("首页布局已恢复默认");
        });

        HBox actions = new HBox(8, edit, reset);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, UiKit.label("个性化布局", "card-title"), actions,
                UiKit.label("首页采用窗口式布局：拖动标题栏移动、拖动边框改变大小、点中窗口置顶，"
                        + "编辑后点「保存」立即生效；随时可以恢复默认。", "muted"));
        box.getStyleClass().add("card");
        return box;
    }

    @Override
    public void refresh() {
        boolean enabled = ctx.securityService().isEnabled();
        boolean unlocked = ctx.securityService().isUnlocked();
        securityStatus.setText("状态：" + (enabled ? (unlocked ? "已设置密钥，当前【已解锁】" : "已设置密钥，当前【已锁定】") : "尚未启用")
                + (enabled ? "　连续验证失败次数：" + ctx.securityService().failCount() : ""));
    }

    private void onExport() {
        boolean hasPrivate = ctx.searchService().stats().get("privateCount") instanceof Number n && n.longValue() > 0;
        boolean includePrivate = false;
        if (hasPrivate) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("导出选项");
            a.setHeaderText("档案中包含私密材料");
            a.setContentText("私密材料一旦进入备份包就是普通文件，任何人拿到备份包都能读取。\n"
                    + "确定要把私密材料一并导出吗？\n（选择「取消」则只导出非私密材料）");
            a.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
            var r = a.showAndWait();
            includePrivate = r.isPresent() && r.get() == ButtonType.YES;
            if (includePrivate && !PrivateUnlockDialog.ensureUnlocked(ctx)) return;
        }

        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("选择备份输出目录");
        File dir = dc.showDialog(getScene() == null ? null : getScene().getWindow());
        final boolean withPrivate = includePrivate;
        Path target = dir != null ? dir.toPath() : ctx.database().dir("backup");

        Result<ExportResult> r = ctx.backupService().exportAll(target, withPrivate);
        if (r.isSuccess()) {
            UiKit.info(r.getData().summaryText());
        } else {
            UiKit.error("[" + r.getCode() + "] " + r.getMessage());
        }
    }

    private void onImport() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择备份包");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("备份包", "*.zip"));
        File f = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (f == null) return;
        if (!UiKit.confirm("导入备份", "导入会向当前档案追加内容（不会覆盖已有材料）。\n确定导入「" + f.getName() + "」吗？")) return;
        Result<String> r = ctx.backupService().importFrom(f.toPath());
        if (r.isSuccess()) {
            UiKit.info(r.getData());
        } else {
            UiKit.error("[" + r.getCode() + "] " + r.getMessage());
        }
    }
}
