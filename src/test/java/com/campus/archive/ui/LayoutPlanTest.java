package com.campus.archive.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 首页窗口布局的几何与层叠逻辑（不依赖数据库）。 */
@DisplayName("个性化布局：窗口几何与层叠顺序")
class LayoutPlanTest {

    @Test
    @DisplayName("默认布局：两扇窗口自上而下排列，尺寸取默认值")
    void defaultsStackVertically() {
        LayoutPlan p = LayoutPlan.defaults();
        assertEquals(List.of(LayoutPlan.Block.DROP_AREA, LayoutPlan.Block.UPLOAD_BUTTON), p.stack());

        LayoutPlan.Window drop = p.window(LayoutPlan.Block.DROP_AREA);
        LayoutPlan.Window upload = p.window(LayoutPlan.Block.UPLOAD_BUTTON);
        assertEquals(LayoutPlan.Block.DROP_AREA.defaultWidth, drop.width, 0.01);
        assertTrue(upload.y > drop.y, "第二个窗口应排在第一个下方");
    }

    @Test
    @DisplayName("层叠顺序解析：跳过未知与重复项，缺失窗口补到末尾")
    void parseStackIsTolerant() {
        assertEquals(List.of(LayoutPlan.Block.UPLOAD_BUTTON, LayoutPlan.Block.DROP_AREA),
                LayoutPlan.parseStack("UPLOAD_BUTTON, NOPE, DROP_AREA, UPLOAD_BUTTON"));
        assertEquals(List.of(LayoutPlan.Block.DROP_AREA, LayoutPlan.Block.UPLOAD_BUTTON),
                LayoutPlan.parseStack(null));
        assertEquals(List.of(LayoutPlan.Block.UPLOAD_BUTTON, LayoutPlan.Block.DROP_AREA),
                LayoutPlan.parseStack("UPLOAD_BUTTON"));
    }

    @Test
    @DisplayName("几何解析：合法值读取，缺项或非法值返回 null 以沿用默认")
    void parseWindowIsTolerant() {
        LayoutPlan.Window w = LayoutPlan.parseWindow("10,20,300,120", LayoutPlan.Block.UPLOAD_BUTTON);
        assertNotNull(w);
        assertEquals(10, w.x, 0.01);
        assertEquals(20, w.y, 0.01);
        assertEquals(300, w.width, 0.01);
        assertEquals(120, h(w), 0.01);

        assertNull(LayoutPlan.parseWindow("10,20,300", LayoutPlan.Block.UPLOAD_BUTTON));
        assertNull(LayoutPlan.parseWindow("a,b,c,d", LayoutPlan.Block.UPLOAD_BUTTON));
        assertNull(LayoutPlan.parseWindow(null, LayoutPlan.Block.UPLOAD_BUTTON));
    }

    @Test
    @DisplayName("几何解析：小于最小尺寸时抬到最小值")
    void parseWindowClampsToMin() {
        LayoutPlan.Window w = LayoutPlan.parseWindow("0,0,10,10", LayoutPlan.Block.DROP_AREA);
        assertNotNull(w);
        assertEquals(LayoutPlan.Block.DROP_AREA.minWidth, w.width, 0.01);
        assertEquals(LayoutPlan.Block.DROP_AREA.minHeight, h(w), 0.01);
    }

    @Test
    @DisplayName("点击置顶：把窗口移到层叠末尾，已在顶部时保持不变")
    void bringToFrontMovesWindowToTop() {
        LayoutPlan p = LayoutPlan.defaults();
        p.bringToFront(LayoutPlan.Block.DROP_AREA);
        assertEquals(List.of(LayoutPlan.Block.UPLOAD_BUTTON, LayoutPlan.Block.DROP_AREA), p.stack());

        p.bringToFront(LayoutPlan.Block.DROP_AREA);
        assertEquals(List.of(LayoutPlan.Block.UPLOAD_BUTTON, LayoutPlan.Block.DROP_AREA), p.stack());
    }

    @Test
    @DisplayName("clampAll 把越界窗口收回桌面内")
    void clampAllPullsWindowsInside() {
        LayoutPlan p = LayoutPlan.defaults();
        p.window(LayoutPlan.Block.DROP_AREA).x = 2000;
        p.window(LayoutPlan.Block.DROP_AREA).y = 2000;

        p.clampAll(900, 600);

        LayoutPlan.Window w = p.window(LayoutPlan.Block.DROP_AREA);
        assertTrue(w.x >= 0, "x 不应为负");
        assertTrue(w.x + w.width <= 900.01, "右边缘不应超出桌面");
        assertTrue(w.y + h(w) <= 600.01, "下边缘不应超出桌面");
    }

    @Test
    @DisplayName("setStack 按传入顺序重排，并补齐缺失窗口")
    void setStackRebuildsOrder() {
        LayoutPlan p = LayoutPlan.defaults();
        p.setStack(List.of(LayoutPlan.Block.UPLOAD_BUTTON));
        assertEquals(List.of(LayoutPlan.Block.UPLOAD_BUTTON, LayoutPlan.Block.DROP_AREA), p.stack());
    }

    @Test
    @DisplayName("几何序列化与解析往返一致")
    void geoRoundTrip() {
        LayoutPlan p = LayoutPlan.defaults();
        LayoutPlan.Window w = p.window(LayoutPlan.Block.UPLOAD_BUTTON);
        w.x = 12;
        w.y = 34;
        w.width = 260;
        w.height = 110;

        LayoutPlan.Window back = LayoutPlan.parseWindow(
                LayoutPlan.toGeo(w), LayoutPlan.Block.UPLOAD_BUTTON);
        assertNotNull(back);
        assertEquals(12, back.x, 0.01);
        assertEquals(34, back.y, 0.01);
        assertEquals(260, back.width, 0.01);
        assertEquals(110, h(back), 0.01);
    }

    private static double h(LayoutPlan.Window w) {
        return w.height;
    }
}
