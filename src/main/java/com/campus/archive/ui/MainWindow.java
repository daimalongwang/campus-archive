package com.campus.archive.ui;

import com.campus.archive.config.AppContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主窗口：左侧一级导航 + 顶部标题栏 + 中部内容区。
 * 一级入口：首页 / 我的档案 / 我的。
 */
public class MainWindow {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final Map<String, Node> views = new LinkedHashMap<>();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final Label pageTitle = UiKit.label("首页", "page-title");

    private String current = "home";

    public MainWindow(AppContext ctx) {
        this.ctx = ctx;
        build();
        show("home");
    }

    public BorderPane root() {
        return root;
    }

    private void build() {
        // ---- 左侧导航 ----
        VBox nav = new VBox();
        nav.getStyleClass().add("nav-pane");
        nav.getChildren().add(UiKit.label("学业档案管理", "nav-brand"));

        views.put("home", new HomeView(ctx, this));
        views.put("archive", new ArchiveView(ctx));
        views.put("settings", new SettingsView(ctx, this));

        for (Map.Entry<String, Node> e : views.entrySet()) {
            Button b = new Button(titleOf(e.getKey()));
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(a -> show(e.getKey()));
            navButtons.put(e.getKey(), b);
            nav.getChildren().add(b);
        }

        // ---- 顶部栏 ----
        HBox topLeft = new HBox(12, pageTitle);
        topLeft.setAlignment(Pos.CENTER_LEFT);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox top = new HBox(topLeft, grow);
        top.getStyleClass().add("top-bar");
        top.setAlignment(Pos.CENTER_LEFT);

        content.setPadding(new Insets(0));
        root.setLeft(nav);
        root.setTop(top);
        root.setCenter(content);
        root.getStyleClass().add("root");
    }

    /** 切换一级页面。 */
    public void show(String key) {
        Node v = views.get(key);
        if (v == null) return;
        current = key;
        content.getChildren().setAll(v);
        pageTitle.setText(titleOf(key));
        navButtons.forEach((k, b) -> {
            b.getStyleClass().remove("nav-button-active");
            if (k.equals(key)) b.getStyleClass().add("nav-button-active");
        });
        if (v instanceof Refreshable r) r.refresh();
    }

    public String currentView() {
        return current;
    }

    /** 「我的」页调用：切到首页并进入布局编辑模式。 */
    public void beginLayoutEdit() {
        show("home");
        if (views.get("home") instanceof HomeView hv) hv.startEditing();
    }

    /** 「我的」页恢复默认后，让首页重新载入配置。 */
    public void reloadHomeLayout() {
        if (views.get("home") instanceof HomeView hv) hv.reloadLayout();
    }

    private static String titleOf(String key) {
        return switch (key) {
            case "home" -> "首页";
            case "archive" -> "我的档案";
            default -> "我的";
        };
    }

    /** 页面在被切回时刷新数据。 */
    public interface Refreshable {
        void refresh();
    }
}
