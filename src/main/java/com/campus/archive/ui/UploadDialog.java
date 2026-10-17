package com.campus.archive.ui;

import com.campus.archive.config.AppContext;
import com.campus.archive.dto.Result;
import com.campus.archive.dto.UploadOptions;
import com.campus.archive.dto.UploadSummary;
import com.campus.archive.entity.Category;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传对话框：把<b>一批</b>材料放进<b>同一个</b>分类。
 *
 * <p>可以一次选多个文件，但不能分别指定分类——这是刻意的取舍：
 * 常见场景是"一次把某个学期的证书全传进去"，逐个挑分类反而更慢。</p>
 *
 * <p>分类树复用档案页的结构并全部展开；除缩进外在名称前显示层级徽标（顶层 0，逐层 +1，不同层级不同底色）。</p>
 */
public class UploadDialog extends Dialog<Void> {

    private final AppContext ctx;
    private final List<File> files;
    private final TreeView<Category> categoryTree = new TreeView<>();
    private final ListView<String> fileList = new ListView<>();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = UiKit.label("请选择存放分类", "muted");

    private Node uploadButton;

    public UploadDialog(AppContext ctx, List<File> files) {
        this.ctx = ctx;
        this.files = List.copyOf(files);

        setTitle("上传材料");
        setHeaderText("这 " + this.files.size() + " 个文件将一起存入同一个分类");
        setResizable(true);

        ButtonType uploadType = new ButtonType("上传", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(uploadType, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(560);
        UiKit.styleDialog(this);

        build();

        uploadButton = getDialogPane().lookupButton(uploadType);
        uploadButton.setDisable(true);
        uploadButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            if (selectedCategory() != null) doUpload();
        });
    }

    private void build() {
        for (File f : files) {
            fileList.getItems().add("  " + f.getName() + "    " + humanSize(f.length()));
        }
        fileList.setPrefHeight(Math.min(150, 34 + files.size() * 26));

        categoryTree.setPrefHeight(260);
        categoryTree.setShowRoot(false);
        categoryTree.setCellFactory(v -> new TreeCell<>() {
            @Override
            protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    return;
                }
                int depth = depthOf(getTreeItem());
                // 与档案页保持一致：层级数字 + 名称，按层级上色，文字交给 skin 绘制
                setText(UiKit.depthText(depth, item.getName()));
                setGraphic(null);
                setStyle("-fx-text-fill:" + UiKit.depthColor(depth) + ";");
            }
        });
        categoryTree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean none = sel == null;
            if (uploadButton != null) uploadButton.setDisable(none);
            status.setText(none ? "请选择存放分类" : "将存入：" + sel.getValue().getName());
        });
        rebuildTree();

        progress.setPrefWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);

        VBox box = new VBox(8,
                UiKit.label("待上传文件（" + files.size() + " 个）", "card-title"),
                fileList,
                UiKit.label("存放分类（本批全部存入所选分类，顶层为 0）", "card-title"),
                categoryTree,
                progress,
                status);
        box.setPadding(new Insets(4));
        getDialogPane().setContent(box);
    }

    /** 按档案页的方式重建分类树，并全部展开。 */
    private void rebuildTree() {
        List<Category> flat = ctx.categoryService().listAll();
        Map<String, TreeItem<Category>> nodes = new LinkedHashMap<>();
        for (Category c : flat) {
            nodes.put(c.getId(), new TreeItem<>(c));
        }

        TreeItem<Category> root = new TreeItem<>();      // 隐藏的虚拟根，不参与选择
        for (Category c : flat) {
            TreeItem<Category> node = nodes.get(c.getId());
            TreeItem<Category> parent = c.isRoot() ? root : nodes.get(c.getParentId());
            // 父节点缺失（数据异常）时挂到根上，保证任何分类都可见
            (parent == null ? root : parent).getChildren().add(node);
        }
        expandAll(root);
        categoryTree.setRoot(root);
    }

    private static void expandAll(TreeItem<Category> node) {
        node.setExpanded(true);
        for (TreeItem<Category> child : node.getChildren()) expandAll(child);
    }

    /** 层级：顶层为 0（虚拟根的父节点为 null，因此从根的子节点起算）。 */
    private static int depthOf(TreeItem<Category> item) {
        if (item == null) return 0;
        int depth = 0;
        for (TreeItem<Category> p = item.getParent(); p != null && p.getParent() != null; p = p.getParent()) {
            depth++;
        }
        return depth;
    }

    private Category selectedCategory() {
        TreeItem<Category> item = categoryTree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private void doUpload() {
        Category cat = selectedCategory();
        if (cat == null) return;

        UploadOptions opt = new UploadOptions().setCategoryId(cat.getId());

        uploadButton.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        status.setText("正在上传…");

        Thread worker = new Thread(() -> {
            Result<UploadSummary> r = ctx.materialService().upload(new ArrayList<>(files), opt);
            Platform.runLater(() -> {
                progress.setProgress(1);
                status.setText("上传结束");
                if (!r.isSuccess()) {
                    UiKit.error("[" + r.getCode() + "] " + r.getMessage());
                    uploadButton.setDisable(false);
                    return;
                }
                UploadSummary s = r.getData();
                StringBuilder sb = new StringBuilder(s.summaryText());
                if (!s.getDuplicateWarnings().isEmpty()) {
                    sb.append("\n\n疑似重复（系统建议确认后再决定是否保留）：\n");
                    s.getDuplicateWarnings().forEach(n -> sb.append("· ").append(n).append('\n'));
                }
                if (s.hasFailure()) {
                    sb.append("\n失败明细：\n");
                    s.getFailed().forEach(f -> sb.append("· ").append(f.fileName())
                            .append("：[").append(f.code()).append("] ").append(f.message()).append('\n'));
                }
                UiKit.info(sb.toString());
                setResult(null);
                close();
            });
        }, "upload-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
