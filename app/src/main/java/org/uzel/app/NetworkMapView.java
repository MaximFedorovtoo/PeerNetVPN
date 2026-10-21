package org.uzel.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.function.IntConsumer;

/** Schematic region overview. Badges represent counts, never individual locations.
 * The caller supplies accessible region filter controls and the geography disclaimer.
 * All public calls, like other View operations, must happen on the UI thread.
 */
public final class NetworkMapView extends View {
    private static final float MAP_WIDTH = 1000f, MAP_HEIGHT = 470f;
    private static final int CYAN = Color.rgb(78, 222, 242);
    private static final float[] BADGE_X = {0, 526, 221, 741, 508, 858, 0};
    private static final float[] BADGE_Y = {0, 293, 190, 172, 129, 361, 0};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] land = new Path[7];
    private final Shader[] halos = new Shader[7];
    private final int[] counts = new int[7];
    private final String[] labels = {"", "", "", "", "", "", ""};
    private final RectF frame = new RectF();
    private final Path frameClip = new Path();
    private final float density;
    private final float touchSlop;
    private float mapScale = 1f, mapLeft, mapTop;
    private float downX, downY;
    private int selectedRegion = PeerRegions.ALL;
    private int pressedRegion = -1;
    private IntConsumer regionListener;

    public NetworkMapView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setMinimumHeight(Math.round(176 * density));
        // Equivalent accessible controls are provided beside the visualization.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        buildLand();
        for (int region = 1; region <= 5; region++) {
            halos[region] = new RadialGradient(BADGE_X[region], BADGE_Y[region], 82,
                    new int[]{0x3054def2, 0x0e54def2, 0x0054def2},
                    new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    /** Defensive copy; missing/negative counts are treated as zero. Unknown has no map mark. */
    public void setData(int[] regionCounts, int selected) {
        boolean changed = false;
        for (int region = 0; region < counts.length; region++) {
            int count = regionCounts != null && region < regionCounts.length
                    ? Math.max(0, regionCounts[region]) : 0;
            if (counts[region] != count) {
                counts[region] = count;
                labels[region] = count > 999 ? "999+" : Integer.toString(count);
                changed = true;
            }
        }
        int nextSelected = selected >= 0 && selected < counts.length ? selected : PeerRegions.ALL;
        if (selectedRegion != nextSelected) {
            selectedRegion = nextSelected;
            changed = true;
        }
        if (changed) invalidate();
    }

    public void setOnRegionSelectedListener(IntConsumer listener) {
        regionListener = listener;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.round(360 * density), widthMeasureSpec);
        int desiredHeight = Math.max(getSuggestedMinimumHeight(), Math.round(width * .50f));
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        frame.set(0, 0, w, h);
        frameClip.reset();
        frameClip.addRoundRect(frame, 20 * density, 20 * density, Path.Direction.CW);
        float inset = 10 * density;
        mapScale = Math.max(.001f, Math.min(Math.max(0, w - 2 * inset) / MAP_WIDTH,
                Math.max(0, h - 2 * inset) / MAP_HEIGHT));
        mapLeft = (w - MAP_WIDTH * mapScale) / 2;
        mapTop = (h - MAP_HEIGHT * mapScale) / 2;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.clipPath(frameClip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff0a1b2d);
        canvas.drawRect(frame, paint);
        canvas.translate(mapLeft, mapTop);
        canvas.scale(mapScale, mapScale);
        paint.setColor(0xff173148);
        paint.setStrokeWidth(1f);
        for (int x = 50; x < 1000; x += 90) canvas.drawLine(x, 24, x, 446, paint);
        for (int y = 55; y < 450; y += 65) canvas.drawLine(24, y, 976, y, paint);
        for (int region = 1; region <= 5; region++) {
            boolean active = counts[region] > 0;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? 0xff17485b : 0xff173449);
            canvas.drawPath(land[region], paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(active ? 0xff368a9b : 0xff284a5e);
            canvas.drawPath(land[region], paint);
        }
        for (int region = 1; region <= 5; region++) {
            if (counts[region] == 0) continue;
            float x = BADGE_X[region], y = BADGE_Y[region];
            float badgeRadius = 15 * density / mapScale;
            float badgeStroke = density / mapScale;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(halos[region]);
            canvas.drawCircle(x, y, 82, paint);
            paint.setShader(null);
            if (selectedRegion == region) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(badgeStroke);
                paint.setColor(0xff78edfb);
                canvas.drawCircle(x, y, badgeRadius + 4 * density / mapScale, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff071623);
            canvas.drawCircle(x, y + 2 * density / mapScale, badgeRadius, paint);
            paint.setColor(selectedRegion == region ? CYAN : 0xff123c50);
            canvas.drawCircle(x, y, badgeRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(badgeStroke);
            paint.setColor(CYAN);
            canvas.drawCircle(x, y, badgeRadius, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(selectedRegion == region ? 0xff062033 : 0xffe6fcff);
            paint.setTextSize((counts[region] > 999 ? 10f : 12f)
                    * getResources().getDisplayMetrics().scaledDensity / mapScale);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(labels[region], x, y - (paint.ascent() + paint.descent()) / 2, paint);
        }
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || regionListener == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedRegion = hitRegion(event.getX(), event.getY());
                downX = event.getX();
                downY = event.getY();
                return pressedRegion != -1;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop) pressedRegion = -1;
                return true;
            case MotionEvent.ACTION_UP:
                int region = pressedRegion;
                pressedRegion = -1;
                if (region != -1 && region == hitRegion(event.getX(), event.getY())) {
                    performClick();
                    regionListener.accept(region);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedRegion = -1;
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private int hitRegion(float x, float y) {
        float radius = 24 * density;
        int closest = -1;
        float best = radius * radius;
        for (int region = 1; region <= 5; region++) {
            if (counts[region] == 0) continue;
            float dx = x - (mapLeft + BADGE_X[region] * mapScale);
            float dy = y - (mapTop + BADGE_Y[region] * mapScale);
            float distance = dx * dx + dy * dy;
            if (distance <= best) {
                closest = region;
                best = distance;
            }
        }
        return closest;
    }

    private static void polygon(Path path, float... points) {
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.close();
    }

    /** Hand-authored overview, intentionally without country borders or location pins. */
    private void buildLand() {
        for (int i = 1; i <= 5; i++) land[i] = new Path();
        Path america = land[PeerRegions.AMERICAS];
        polygon(america, 65,105, 83,78, 118,75, 128,55, 163,58, 186,39, 216,48,
                247,42, 273,61, 306,67, 325,91, 303,111, 278,125, 270,151,
                246,164, 230,190, 211,207, 219,232, 242,244, 252,261,
                234,263, 214,247, 194,240, 182,218, 168,202, 156,176,
                138,158, 129,132, 107,124, 84,131);
        polygon(america, 251,260, 278,252, 304,267, 326,277, 346,301,
                337,326, 317,344, 306,376, 290,403, 275,433, 261,445,
                254,417, 259,387, 245,360, 244,336, 227,306, 231,281);
        polygon(america, 323,39, 351,25, 382,38, 390,63, 368,95, 344,101, 332,76);
        polygon(america, 259,220, 280,219, 291,228, 273,231);
        Path europe = land[PeerRegions.EUROPE];
        polygon(europe, 451,156, 463,135, 479,127, 469,114, 483,100,
                491,79, 512,62, 528,65, 517,93, 525,105, 557,93,
                576,110, 572,147, 553,163, 536,166, 524,155,
                515,174, 504,158, 490,163, 479,178, 460,176);
        polygon(europe, 447,121, 457,115, 463,131, 456,145, 446,142);
        polygon(europe, 412,76, 431,72, 442,83, 426,91, 413,88);
        Path africa = land[PeerRegions.AFRICA];
        polygon(africa, 462,188, 493,179, 526,185, 550,202, 563,225,
                579,247, 601,253, 586,277, 568,291, 563,322,
                547,346, 529,375, 512,366, 504,339, 490,315,
                490,287, 468,277, 446,253, 445,221);
        polygon(africa, 593,328, 601,314, 604,332, 596,351, 587,354);
        Path asia = land[PeerRegions.ASIA];
        polygon(asia, 576,110, 592,90, 625,86, 647,65, 680,66, 697,51,
                726,63, 751,48, 781,66, 811,58, 842,76, 871,72,
                903,93, 938,95, 959,115, 939,137, 909,135, 888,150,
                861,154, 846,180, 824,189, 816,214, 788,224, 781,252,
                763,260, 755,243, 735,227, 727,246, 713,249, 700,225,
                688,204, 673,201, 661,232, 649,252, 635,230, 628,211,
                608,206, 597,190, 579,192, 564,175, 553,163, 572,147);
        polygon(asia, 574,203, 596,209, 611,233, 596,246, 580,231);
        polygon(asia, 875,177, 884,162, 891,170, 887,192, 876,210, 869,205);
        polygon(asia, 789,271, 805,284, 820,290, 815,298, 797,292);
        polygon(asia, 827,301, 857,307, 868,314, 839,316);
        polygon(asia, 838,253, 849,262, 853,281, 843,273);
        Path oceania = land[PeerRegions.OCEANIA];
        polygon(oceania, 800,342, 826,330, 849,328, 859,313, 873,331,
                901,341, 910,366, 902,388, 881,401, 858,388, 840,384,
                817,391, 797,379, 792,357);
        polygon(oceania, 868,308, 885,301, 904,312, 914,324, 895,320);
        polygon(oceania, 945,388, 952,379, 953,395, 942,407, 936,404);
        polygon(oceania, 932,410, 941,407, 933,425, 919,434, 916,426);
        polygon(oceania, 879,410, 890,409, 889,420, 882,424);
    }
}
