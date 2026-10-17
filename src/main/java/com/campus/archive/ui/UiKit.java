package com.campus.archive.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** 界面通用构件。统一交互反馈与文案风格（对应需求 NFR-UI-05 / NFR-UI-06 / NFR-UI-07）。 */
public final class UiKit {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private UiKit() {
    }

    public static String fmt(LocalDateTime t) {
        return t == null ? "—" : t.format(TIME);
    }

    public static String fmtDate(LocalDateTime t) {
        return t == null ? "—" : t.format(DATE);
    }

    public static void info(String message) {
        alert(Alert.AlertType.INFORMATION, "提示", message);
    }

    public static void warn(String message) {
        alert(Alert.AlertType.WARNING, "注意", message);
    }

    public static void error(String message) {
        alert(Alert.AlertType.ERROR, "操作失败", message);
    }

    private static void alert(Alert.AlertType type, String title, String message) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(null);
        a.getDialogPane().setContent(wrap(message));
        a.showAndWait();
    }

    /** 高风险操作的二次确认（对应 NFR-UI-08 / AC-09）。 */
    public static boolean confirm(String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle(title);
        a.setHeaderText(null);
        a.getDialogPane().setContent(wrap(message));
        a.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    public static Optional<String> prompt(String title, String label, String initial) {
        TextInputDialog d = new TextInputDialog(initial == null ? "" : initial);
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(label);
        return d.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    public static Label label(String text, String styleClass) {
        Label l = new Label(text);
        if (styleClass != null) l.getStyleClass().add(styleClass);
        return l;
    }

    /**
     * 对话框自带独立 Scene，不会继承主窗口的样式表；不显式挂上就会退回 JavaFX 默认皮肤
     * （层级徽标、按钮、文字层级全部失样式）。所有 Dialog 创建后都应调用本方法。
     */
    public static void styleDialog(Dialog<?> dialog) {
        var css = UiKit.class.getResource("/css/app.css");
        if (css == null) return;
        var sheets = dialog.getDialogPane().getStylesheets();
        if (!sheets.contains(css.toExternalForm())) sheets.add(css.toExternalForm());
    }

    /**
     * 把 text 中命中关键词的片段高亮（多个关键词用空格分隔，大小写不敏感）。
     * 返回由若干 Label 拼成的 HBox，可直接作为 TableCell / TreeCell 的 graphic 使用。
     */
    public static Node highlight(String text, String keyword) {
        String s = text == null ? "" : text;
        HBox row = new HBox(0);
        row.setAlignment(Pos.CENTER_LEFT);
        List<String> keys = keywords(keyword);

        boolean[] marked = new boolean[s.length()];
        String lower = s.toLowerCase();
        for (String k : keys) {
            for (int at = lower.indexOf(k); at >= 0; at = lower.indexOf(k, at + k.length())) {
                for (int i = at; i < Math.min(at + k.length(), s.length()); i++) marked[i] = true;
            }
        }

        if (s.isEmpty()) {
            row.getChildren().add(chunk("", false));
            return row;
        }
        int i = 0;
        while (i < s.length()) {
            int j = i;
            while (j < s.length() && marked[j] == marked[i]) j++;
            row.getChildren().add(chunk(s.substring(i, j), marked[i]));
            i = j;
        }
        return row;
    }

    private static Label chunk(String text, boolean marked) {
        Label l = new Label(text);
        l.getStyleClass().add(marked ? "hl" : "plain-chunk");
        return l;
    }

    private static List<String> keywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.trim().toLowerCase().split("\\s+")).filter(k -> !k.isEmpty()).toList();
    }

    /**
     * 分类树的层级配色：顶层 0 深蓝，逐层转暖；伪节点（根 / 回收站，depth &lt; 0）用灰色。
     * 档案页与上传窗口共用同一套取值，保证两处样式一致。
     */
    public static String depthColor(int depth) {
        return switch (Math.max(depth, -1)) {
            case 0 -> "#1f3864";
            case 1 -> "#2f5597";
            case 2 -> "#237a63";
            case 3 -> "#9a6a12";
            default -> depth >= 4 ? "#7a4f9e" : "#7a8699";
        };
    }

    /** 分类树节点文本：层级数字 + 名称（数字与名称同色，由调用方 setStyle 着色）。 */
    public static String depthText(int depth, String name) {
        return depth < 0 ? name : depth + "  " + name;
    }

    private static Region wrap(String message) {
        Label l = new Label(message);
        l.setWrapText(true);
        l.setMaxWidth(460);
        return l;
    }
}
