package com.campus.archive;

import com.campus.archive.config.AppContext;
import com.campus.archive.config.DemoDataSeeder;
import com.campus.archive.ui.MainWindow;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 原型入口。
 *
 * <p>命令行参数：</p>
 * <ul>
 *   <li>{@code --snapshot=目录}（等价于 -Dsnapshot.dir=目录）——启动后依次切换各页面并截图，
 *       便于生成交付材料与回归检查界面是否正常，截完图自动退出。</li>
 *   <li>{@code --reset-demo}——清空演示数据后重新播种，供现场演示前重置环境。</li>
 * </ul>
 */
public class ArchiveApp extends Application {

    private MainWindow window;
    private Path snapshotDir;

    @Override
    public void start(Stage stage) {
        parseArgs();

        AppContext ctx = AppContext.get();
        seedIfNeeded(ctx);

        window = new MainWindow(ctx);
        Scene scene = new Scene(window.root(), 1440, 880);
        var css = ArchiveApp.class.getResource("/css/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("大学生个人学业档案与证书日程管理系统 · MVP 原型");
        stage.setScene(scene);
        stage.show();

        if (snapshotDir != null) {
            scheduleSnapshots(stage);
        }
    }

    @Override
    public void stop() {
        AppContext.get().securityService().lock();
        // 系统托盘观察者会启动 AWT 事件分发线程，它是非守护线程，
        // 不显式退出的话关闭窗口后 JVM 仍会驻留。这里在完成资源清理后主动退出。
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }

    /* ============================ 辅助 ============================ */

    private void parseArgs() {
        String fromProp = System.getProperty("snapshot.dir");
        if (fromProp != null && !fromProp.isBlank()) snapshotDir = Path.of(fromProp);

        for (String a : getParameters().getRaw()) {
            if (a.startsWith("--snapshot=")) {
                snapshotDir = Path.of(a.substring("--snapshot=".length()));
            } else if ("--reset-demo".equals(a)) {
                System.setProperty("archive.resetDemo", "true");
            }
        }
    }

    private void seedIfNeeded(AppContext ctx) {
        boolean isEmpty = ctx.searchService().stats().get("total") instanceof Number n && n.longValue() == 0;
        if (!isEmpty) return;
        Path staging = Path.of(System.getProperty("user.dir"), "demo-samples");
        int n = DemoDataSeeder.seed(ctx, staging);
        System.out.println("[启动] 首次运行，已生成演示数据，新增材料 " + n + " 条");
    }

    private void scheduleSnapshots(Stage stage) {
        String[] keys = {"home", "archive", "settings"};
        Timeline tl = new Timeline();
        double t = 1.0;
        for (String key : keys) {
            tl.getKeyFrames().add(new KeyFrame(Duration.seconds(t), e -> window.show(key)));
            t += 1.1;
            final double shot = t;
            tl.getKeyFrames().add(new KeyFrame(Duration.seconds(shot), e -> saveSnapshot(stage, key)));
            t += 0.5;
        }
        tl.getKeyFrames().add(new KeyFrame(Duration.seconds(t + 0.6), e -> {
            System.out.println("[截图] 完成，输出目录：" + snapshotDir.toAbsolutePath());
            Platform.exit();
        }));
        tl.play();
    }

    private void saveSnapshot(Stage stage, String key) {
        try {
            Files.createDirectories(snapshotDir);
            WritableImage img = stage.getScene().snapshot(null);
            int w = (int) img.getWidth();
            int h = (int) img.getHeight();
            int[] buffer = new int[w * h];
            img.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), buffer, 0, w);
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            bi.setRGB(0, 0, w, h, buffer, 0, w);
            File out = snapshotDir.resolve(key + ".png").toFile();
            ImageIO.write(bi, "png", out);
            System.out.println("[截图] " + out.getName() + "  " + w + "x" + h);
        } catch (Exception e) {
            System.err.println("[截图] " + key + " 失败：" + e.getMessage());
        }
    }
}
