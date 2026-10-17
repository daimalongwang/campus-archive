package com.campus.archive.ui;

import com.campus.archive.config.Repositories;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 首页窗口布局：每个模块是一扇可自由摆放的"窗口"，记录位置、尺寸与层叠顺序。
 *
 * <p>顺序（{@link #stack()}）从底到顶，末尾是最上层，对应 Windows 的 z-order。
 * 几何与顺序都持久化在 app_config 表；配置损坏时静默退回默认布局。</p>
 */
public final class LayoutPlan {

    /** 首页可摆放的窗口。 */
    public enum Block {
        DROP_AREA("拖拽上传区", 1173, 240, 320, 140),
        UPLOAD_BUTTON("上传按钮", 1178, 199, 220, 96);

        public final String label;
        public final double defaultWidth;
        public final double defaultHeight;
        public final double minWidth;
        public final double minHeight;

        Block(String label, double defaultWidth, double defaultHeight, double minWidth, double minHeight) {
            this.label = label;
            this.defaultWidth = defaultWidth;
            this.defaultHeight = defaultHeight;
            this.minWidth = minWidth;
            this.minHeight = minHeight;
        }
    }

    /** 一扇窗口的几何。可变对象，拖动/缩放时直接改后重新摆放。 */
    public static final class Window {
        public double x;
        public double y;
        public double width;
        public double height;

        public Window(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private static final String KEY_STACK = "layout.home.stack";
    private static final String KEY_GEO = "layout.home.geo.";

    private final List<Block> stack = new ArrayList<>();
    private final Map<Block, Window> windows = new EnumMap<>(Block.class);

    private LayoutPlan() {
    }

    /* ======================== 构造 / 持久化 ======================== */

    /** 默认摆放：两个窗口自上而下通栏排列（与当前演示布局一致）。 */
    public static LayoutPlan defaults() {
        LayoutPlan p = new LayoutPlan();
        double y = 20;
        for (Block b : Block.values()) {
            p.stack.add(b);
            p.windows.put(b, new Window(20, y, b.defaultWidth, b.defaultHeight));
            y += b.defaultHeight + 16;
        }
        return p;
    }

    public static LayoutPlan load(Repositories repos) {
        LayoutPlan p = defaults();
        try {
            List<Block> saved = parseStack(repos.query(d -> d.config().get(KEY_STACK)));
            if (!saved.isEmpty()) {
                p.stack.clear();
                p.stack.addAll(saved);
            }
            for (Block b : Block.values()) {
                Window w = parseWindow(repos.query(d -> d.config().get(KEY_GEO + b.name())), b);
                if (w != null) p.windows.put(b, w);
            }
        } catch (RuntimeException ignored) {
            // 读取失败：保持默认，绝不阻断启动
        }
        return p;
    }

    public void save(Repositories repos) {
        repos.tx(d -> {
            d.config().put(KEY_STACK, toStackCsv());
            for (Block b : Block.values()) {
                d.config().put(KEY_GEO + b.name(), toGeo(windows.get(b)));
            }
            return null;
        });
    }

    /* ======================== 访问 / 修改 ======================== */

    /** 从底到顶的层叠顺序。 */
    public List<Block> stack() {
        return List.copyOf(stack);
    }

    public Window window(Block b) {
        return windows.get(b);
    }

    /** 点中某窗口即置顶（与 Windows 一致）。 */
    public void bringToFront(Block b) {
        if (stack.remove(b)) stack.add(b);
    }

    /** 用窗口当前的实际层叠顺序覆盖内部顺序（保存前调用）。 */
    public void setStack(List<Block> bottomToTop) {
        stack.clear();
        for (Block b : bottomToTop) if (!stack.contains(b)) stack.add(b);
        for (Block b : Block.values()) if (!stack.contains(b)) stack.add(b);
    }

    /** 把全部窗口收进容器，并保证不小于各自的最小尺寸。 */
    public void clampAll(double containerW, double containerH) {
        if (containerW <= 0 || containerH <= 0) return;
        for (Block b : Block.values()) {
            Window w = windows.get(b);
            w.width = Math.max(b.minWidth, Math.min(w.width, containerW));
            w.height = Math.max(b.minHeight, Math.min(w.height, containerH));
            w.x = Math.max(0, Math.min(w.x, containerW - w.width));
            w.y = Math.max(0, Math.min(w.y, containerH - w.height));
        }
    }

    /* ======================== 序列化 ======================== */

    String toStackCsv() {
        StringBuilder sb = new StringBuilder();
        for (Block b : stack) {
            if (sb.length() > 0) sb.append(',');
            sb.append(b.name());
        }
        return sb.toString();
    }

    static String toGeo(Window w) {
        if (w == null) return "";
        return String.format(Locale.ROOT, "%.0f,%.0f,%.0f,%.0f", w.x, w.y, w.width, w.height);
    }

    /** 解析层叠顺序：跳过未知与重复项，缺失的窗口补到末尾。 */
    static List<Block> parseStack(String csv) {
        List<Block> out = new ArrayList<>();
        if (csv != null) {
            for (String raw : csv.split(",")) {
                String name = raw.trim();
                if (name.isEmpty()) continue;
                try {
                    Block b = Block.valueOf(name);
                    if (!out.contains(b)) out.add(b);
                } catch (IllegalArgumentException ignored) {
                    // 未知窗口名：忽略
                }
            }
        }
        for (Block b : Block.values()) if (!out.contains(b)) out.add(b);
        return out;
    }

    /** 解析几何：格式 "x,y,w,h"；非法或缺失返回 null（沿用默认）。 */
    static Window parseWindow(String raw, Block b) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        if (parts.length != 4) return null;
        try {
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double w = Math.max(b.minWidth, Double.parseDouble(parts[2].trim()));
            double h = Math.max(b.minHeight, Double.parseDouble(parts[3].trim()));
            return new Window(x, y, w, h);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
