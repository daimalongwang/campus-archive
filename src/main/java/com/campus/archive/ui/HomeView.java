package com.campus.archive.ui;

import com.campus.archive.config.AppContext;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页：一块"桌面"，每个模块是一扇可自由摆放的窗口。
 *
 * <p>窗口逻辑对齐 Windows：拖标题栏移动、8 个方向拖边框缩放、点中即置顶、
 * 不允许拖出桌面、几何与层叠顺序持久化。普通模式下窗口固定（避免误拖），
 * 从「我的 → 调整布局」进入编辑模式后才可拖动与缩放。</p>
 */
public class HomeView extends VBox {

    private static final String DROP_ACTIVE = "drop-active";
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    private static final int MAX_FILES = 200;

    private final AppContext ctx;
    private final MainWindow window;

    private LayoutPlan plan;
    private boolean editing;
    private LayoutPlan.Block selected;

    private final Pane desktop = new Pane();
    private final List<ModuleWindow> windows = new ArrayList<>();

    public HomeView(AppContext ctx, MainWindow window) {
        this.ctx = ctx;
        this.window = window;
        this.plan = LayoutPlan.load(ctx.repos());

        getStyleClass().add("content");
        setSpacing(16);
        desktop.getStyleClass().add("desktop-pane");
        VBox.setVgrow(desktop, Priority.ALWAYS);

        // 容器尺寸变化时把窗口收回桌面内（与 Windows 缩小桌面后的行为一致）
        desktop.widthProperty().addListener((o, a, b) -> relayout());
        desktop.heightProperty().addListener((o, a, b) -> relayout());

        render();
    }

    /* ======================== 布局与编辑 ======================== */

    public void startEditing() {
        editing = true;
        render();
    }

    public void reloadLayout() {
        plan = LayoutPlan.load(ctx.repos());
        editing = false;
        render();
    }

    private void render() {
        getChildren().clear();
        if (editing) getChildren().add(editBar());
        getChildren().add(desktop);

        desktop.getChildren().clear();
        windows.clear();
        for (LayoutPlan.Block b : plan.stack()) {
            ModuleWindow w = new ModuleWindow(b);
            windows.add(w);
            desktop.getChildren().add(w);
        }
        refreshSelection();
        relayout();
    }

    /**
     * 按当前桌面尺寸摆放窗口。
     *
     * <p>夹取只发生在"摆放"这一步，<b>不写回</b> LayoutPlan —— 否则布局早期容器尺寸很小，
     * 会把窗口永久压扁（放大窗口后也回不来）。用户拖动/缩放时才写回。</p>
     */
    private void relayout() {
        double cw = desktop.getWidth();
        double ch = desktop.getHeight();
        if (cw <= 0 || ch <= 0) return;
        for (ModuleWindow w : windows) w.applyGeometry(cw, ch);
    }

    private void refreshSelection() {
        for (ModuleWindow w : windows) {
            boolean on = editing && w.block == selected;
            w.getStyleClass().remove("module-window-selected");
            if (on) w.getStyleClass().add("module-window-selected");
            w.setHandlesVisible(editing);
        }
    }

    /** 顶部编辑工具条：所有改动先落在内存，点「保存」才写盘。 */
    private Node editBar() {
        Label hint = UiKit.label("拖动标题栏移动窗口，拖动边框或用右下角手柄调整大小，点中窗口即置顶", "muted");

        Button reset = new Button("恢复默认");
        reset.getStyleClass().addAll("button", "ghost-button");
        reset.setOnAction(e -> {
            plan = LayoutPlan.defaults();
            selected = null;
            render();
        });

        Button cancel = new Button("取消");
        cancel.getStyleClass().addAll("button", "ghost-button");
        cancel.setOnAction(e -> {
            plan = LayoutPlan.load(ctx.repos());
            selected = null;
            editing = false;
            render();
        });

        Button save = new Button("保存");
        save.getStyleClass().addAll("button", "primary-button");
        save.setOnAction(e -> {
            // 以桌面上的实际顺序为准（点击置顶会改变它）
            List<LayoutPlan.Block> bottomToTop = new ArrayList<>();
            for (Node n : desktop.getChildren()) {
                if (n instanceof ModuleWindow mw) bottomToTop.add(mw.block);
            }
            plan.setStack(bottomToTop);
            plan.save(ctx.repos());
            editing = false;
            render();
            UiKit.info("首页窗口布局已保存");
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, hint, spacer, reset, cancel, save);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("edit-bar");
        return bar;
    }

    private Node contentBlock(LayoutPlan.Block b) {
        return b == LayoutPlan.Block.DROP_AREA ? dropArea() : uploadArea();
    }

    /* ======================== 窗口 ======================== */

    /** 一扇模块窗口：标题栏 + 内容 + 8 个方向的缩放热区。 */
    private final class ModuleWindow extends Pane {

        private final LayoutPlan.Block block;
        private final VBox body;
        private final HBox titleBar;
        private final List<Region> handles = new ArrayList<>();

        private double dragOffsetX;
        private double dragOffsetY;

        private double resizeStartSceneX;
        private double resizeStartSceneY;
        private double startX;
        private double startY;
        private double startW;
        private double startH;

        ModuleWindow(LayoutPlan.Block block) {
            this.block = block;
            getStyleClass().add("module-window");
            // Pane 会对受管子节点调用 autosize()，把窗口打回内容首选尺寸；这里自行管理几何
            setManaged(false);

            Label title = UiKit.label(block.label, "window-title");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            titleBar = new HBox(8, title, spacer);
            titleBar.getStyleClass().add("window-titlebar");
            titleBar.setAlignment(Pos.CENTER_LEFT);

            Node content = contentBlock(block);
            VBox.setVgrow(content, Priority.ALWAYS);
            body = new VBox(titleBar, content);
            // 与 Windows 一样：内容超出窗口时被裁掉，而不是画到窗口外面
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
            clip.widthProperty().bind(body.widthProperty());
            clip.heightProperty().bind(body.heightProperty());
            body.setClip(clip);
            getChildren().add(body);

            createHandles();
            installTitleDrag();
            installClickToFront();
            setHandlesVisible(editing);
        }

        /* ---- 几何 ---- */

        void applyGeometry(double containerW, double containerH) {
            LayoutPlan.Window g = plan.window(block);
            double w = Math.min(g.width, Math.max(block.minWidth, containerW));
            double h = Math.min(g.height, Math.max(block.minHeight, containerH));
            double x = Math.max(0, Math.min(g.x, containerW - w));
            double y = Math.max(0, Math.min(g.y, containerH - h));
            resize(w, h);
            relocate(x, y);
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();
            body.resizeRelocate(0, 0, w, h);
            placeHandles(w, h);
        }

        private void placeHandles(double w, double h) {
            // 顺序：N, S, W, E, NW, NE, SW, SE
            setBounds(handles.get(0), 10, 0, Math.max(0, w - 20), 6);
            setBounds(handles.get(1), 10, h - 6, Math.max(0, w - 20), 6);
            setBounds(handles.get(2), 0, 10, 6, Math.max(0, h - 20));
            setBounds(handles.get(3), w - 6, 10, 6, Math.max(0, h - 20));
            setBounds(handles.get(4), 0, 0, 10, 10);
            setBounds(handles.get(5), w - 10, 0, 10, 10);
            setBounds(handles.get(6), 0, h - 10, 10, 10);
            setBounds(handles.get(7), w - 10, h - 10, 10, 10);
        }

        private void setBounds(Region r, double x, double y, double w, double h) {
            r.resizeRelocate(x, y, w, h);
        }

        void setHandlesVisible(boolean visible) {
            for (Region h : handles) h.setVisible(visible);
            body.setMouseTransparent(visible);   // 编辑态只操作窗口，不触发上传
        }

        /* ---- 拖动标题栏：移动窗口 ---- */

        private void installTitleDrag() {
            titleBar.setOnMousePressed(e -> {
                if (!editing) return;
                dragOffsetX = e.getSceneX() - getLayoutX();
                dragOffsetY = e.getSceneY() - getLayoutY();
                select();
                e.consume();
            });
            titleBar.setOnMouseDragged(e -> {
                if (!editing) return;
                LayoutPlan.Window g = plan.window(block);
                double cw = desktop.getWidth();
                double ch = desktop.getHeight();
                g.x = Math.max(0, Math.min(e.getSceneX() - dragOffsetX, cw - g.width));
                g.y = Math.max(0, Math.min(e.getSceneY() - dragOffsetY, ch - g.height));
                relocate(g.x, g.y);
                e.consume();
            });
            titleBar.setCursor(Cursor.DEFAULT);
        }

        /* ---- 点击置顶 ---- */

        private void installClickToFront() {
            setOnMousePressed(e -> {
                if (!editing) return;
                select();
            });
        }

        private void select() {
            selected = block;
            plan.bringToFront(block);
            toFront();
            refreshSelection();
        }

        /* ---- 8 向缩放 ---- */

        private void createHandles() {
            addHandle(0, -1, Cursor.V_RESIZE);   // N
            addHandle(0, 1, Cursor.V_RESIZE);    // S
            addHandle(-1, 0, Cursor.H_RESIZE);   // W
            addHandle(1, 0, Cursor.H_RESIZE);    // E
            addHandle(-1, -1, Cursor.NW_RESIZE); // NW
            addHandle(1, -1, Cursor.NE_RESIZE);  // NE
            addHandle(-1, 1, Cursor.SW_RESIZE);  // SW
            addHandle(1, 1, Cursor.SE_RESIZE);   // SE
        }

        /** @param mx -1 左边缘 / 0 不涉及 / 1 右边缘；my 同理处理上下 */
        private void addHandle(int mx, int my, Cursor cursor) {
            Region handle = new Region();
            handle.getStyleClass().add("resize-handle");
            handle.setCursor(cursor);

            handle.setOnMousePressed(e -> {
                if (!editing) return;
                LayoutPlan.Window g = plan.window(block);
                startX = g.x;
                startY = g.y;
                startW = g.width;
                startH = g.height;
                resizeStartSceneX = e.getSceneX();
                resizeStartSceneY = e.getSceneY();
                select();
                e.consume();
            });

            handle.setOnMouseDragged(e -> {
                if (!editing) return;
                resizeBy(mx, my, e.getSceneX() - resizeStartSceneX, e.getSceneY() - resizeStartSceneY);
                e.consume();
            });

            handles.add(handle);
            getChildren().add(handle);
        }

        private void resizeBy(int mx, int my, double dx, double dy) {
            LayoutPlan.Block b = block;
            double x = startX;
            double y = startY;
            double w = startW;
            double h = startH;

            if (mx < 0) {
                x = startX + dx;
                w = startW - dx;
            } else if (mx > 0) {
                w = startW + dx;
            }
            if (my < 0) {
                y = startY + dy;
                h = startH - dy;
            } else if (my > 0) {
                h = startH + dy;
            }

            // 先保最小尺寸（左/上边缘缩放时要同步修正起点，避免"反向生长"）
            if (w < b.minWidth) {
                if (mx < 0) x = startX + startW - b.minWidth;
                w = b.minWidth;
            }
            if (h < b.minHeight) {
                if (my < 0) y = startY + startH - b.minHeight;
                h = b.minHeight;
            }

            // 再夹到桌面边界
            double cw = desktop.getWidth();
            double ch = desktop.getHeight();
            if (x < 0) {
                w += x;
                x = 0;
            }
            if (y < 0) {
                h += y;
                y = 0;
            }
            if (x + w > cw) w = cw - x;
            if (y + h > ch) h = ch - y;
            w = Math.max(b.minWidth, w);
            h = Math.max(b.minHeight, h);

            LayoutPlan.Window g = plan.window(block);
            g.x = x;
            g.y = y;
            g.width = w;
            g.height = h;
            resize(w, h);
            relocate(x, y);
        }
    }

    /* ======================== 两个模块的内容 ======================== */

    private Node dropArea() {
        Label hint = UiKit.label("把文件拖到这里", "drop-hint");
        Label sub = UiKit.label("可一次拖入多个文件，它们会存入同一个分类，单个不超过 200 MB", "muted");
        sub.setWrapText(true);

        VBox box = new VBox(6, hint, sub);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("drop-area");
        VBox.setVgrow(box, Priority.ALWAYS);

        // 同时把拖放事件挂在窗口内容上：窗口缩小后热区也不会消失
        enableDrop(box);
        return box;
    }

    /** 上传按钮本身就是窗口的内容：铺满整个窗口，窗口多大按钮就多大。 */
    private Node uploadArea() {
        Button upload = new Button("＋  上传材料");
        upload.getStyleClass().addAll("button", "primary-button", "window-fill-button");
        upload.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        upload.setMinHeight(0);
        upload.setOnAction(e -> chooseFiles());
        return upload;
    }

    /* ======================== 上传入口 ======================== */

    private void chooseFiles() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择要归档的材料（可多选）");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("常见材料格式",
                        "*.pdf", "*.doc", "*.docx", "*.png", "*.jpg", "*.jpeg", "*.xls", "*.xlsx"),
                new FileChooser.ExtensionFilter("全部文件", "*.*"));
        List<File> picked = fc.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
        if (picked != null && !picked.isEmpty()) openDialog(picked);
    }

    private void openDialog(List<File> picked) {
        List<File> usable = new ArrayList<>();
        int oversized = 0;
        for (File f : picked) {
            if (f.isDirectory()) continue;
            if (f.length() > MAX_FILE_BYTES) { oversized++; continue; }
            if (usable.size() >= MAX_FILES) break;
            usable.add(f);
        }
        if (usable.isEmpty()) {
            UiKit.warn(oversized > 0 ? "所选文件均超过 200 MB，已取消" : "请选择文件，而不是文件夹");
            return;
        }
        if (oversized > 0) {
            UiKit.warn("已跳过 " + oversized + " 个超过 200 MB 的文件");
        }
        new UploadDialog(ctx, usable).showAndWait();
    }

    private void enableDrop(Node target) {
        target.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        target.setOnDragEntered(e -> {
            if (e.getDragboard().hasFiles() && !target.getStyleClass().contains(DROP_ACTIVE)) {
                target.getStyleClass().add(DROP_ACTIVE);
            }
            e.consume();
        });
        target.setOnDragExited(e -> {
            target.getStyleClass().remove(DROP_ACTIVE);
            e.consume();
        });
        target.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean done = false;
            if (db.hasFiles()) {
                done = openWithDropped(db.getFiles());
            }
            e.setDropCompleted(done);
            target.getStyleClass().remove(DROP_ACTIVE);
            e.consume();
        });
    }

    private boolean openWithDropped(List<File> dropped) {
        if (dropped.stream().noneMatch(f -> !f.isDirectory())) {
            UiKit.warn("请拖入文件，而不是文件夹");
            return false;
        }
        openDialog(dropped);
        return true;
    }
}
