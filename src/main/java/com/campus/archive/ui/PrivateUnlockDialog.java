package com.campus.archive.ui;

import com.campus.archive.config.AppContext;
import com.campus.archive.dto.Result;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

/**
 * 私密空间解锁对话框（详细设计图 5-3 私密材料访问时序图的前半段）。
 * 首次使用时引导设置安全密钥；已设置时要求验证。
 */
public final class PrivateUnlockDialog {

    private PrivateUnlockDialog() {
    }

    /**
     * 确保私密空间处于已解锁状态。
     *
     * @return true 表示当前已解锁，可以继续后续敏感操作
     */
    public static boolean ensureUnlocked(AppContext ctx) {
        if (ctx.securityService().isUnlocked()) return true;

        if (!ctx.securityService().isEnabled()) {
            return initKey(ctx);
        }
        return unlock(ctx);
    }

    private static boolean initKey(AppContext ctx) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("设置安全密钥");
        d.setHeaderText("首次使用私密空间，请设置安全密钥");
        PasswordField p1 = new PasswordField();
        p1.setPromptText("不少于 6 位");
        PasswordField p2 = new PasswordField();
        p2.setPromptText("再次输入以确认");
        Label tip = new Label("提醒：系统只保存密钥的派生校验值，不保存明文，\n"
                + "也【不提供找回】功能。请务必自行记住，遗忘后私密材料将无法打开。");
        tip.setWrapText(true);
        tip.getStyleClass().add("muted");

        VBox box = new VBox(10, tip, p1, p2);
        box.setPadding(new Insets(6, 4, 4, 4));
        box.setAlignment(Pos.CENTER_LEFT);
        d.getDialogPane().setContent(box);
        UiKit.styleDialog(d);
        ButtonType ok = new ButtonType("设置并解锁", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        var r = d.showAndWait();
        if (r.isEmpty() || r.get() != ok) return false;

        if (!p1.getText().equals(p2.getText())) {
            UiKit.error("两次输入的密钥不一致");
            return false;
        }
        char[] key = p1.getText().toCharArray();
        Result<Void> res = ctx.securityService().initKey(key);
        com.campus.archive.infra.CryptoUtil.wipe(key);
        if (!res.isSuccess()) {
            UiKit.error(res.getMessage());
            return false;
        }
        UiKit.info("安全密钥已设置，私密空间已解锁");
        return true;
    }

    private static boolean unlock(AppContext ctx) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("解锁私密空间");
        d.setHeaderText("查看私密材料前需要验证安全密钥");
        PasswordField p = new PasswordField();
        p.setPromptText("请输入安全密钥");
        Label err = new Label();
        err.getStyleClass().add("muted");
        err.setTextFill(javafx.scene.paint.Color.web("#c0392b"));

        VBox box = new VBox(10, p, err);
        box.setPadding(new Insets(6, 4, 4, 4));
        d.getDialogPane().setContent(box);
        UiKit.styleDialog(d);
        ButtonType ok = new ButtonType("解锁", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        while (true) {
            var r = d.showAndWait();
            if (r.isEmpty() || r.get() != ok) return false;

            char[] key = p.getText().toCharArray();
            Result<Void> res = ctx.securityService().unlock(key);
            com.campus.archive.infra.CryptoUtil.wipe(key);

            if (res.isSuccess()) {
                UiKit.info("私密空间已解锁");
                return true;
            }
            int fails = ctx.securityService().failCount();
            err.setText("验证失败，请重新输入（已连续失败 " + fails + " 次）");
            p.clear();
            if (fails >= 5) {
                UiKit.warn("连续失败次数过多，出于安全考虑本次操作已终止。");
                return false;
            }
        }
    }
}
