package com.treadmill.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;

/**
 * A quarter-mile running track drawn as a stadium shape. A dot shows where you are on the
 * current lap (counter-clockwise from the finish line), with laps, pace and moving time
 * in the infield.
 */
class TrackView extends View {

    /** A quarter mile, the same lap length iFit's own track view uses. */
    private static final double LAP_METERS = 402.336;

    private final Paint lane = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint finish = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path track = new Path();
    private final Path done = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] pos = new float[2];
    private final float density;

    private int laps;
    private float lapFraction;
    private String pace = "--:-- /mi";
    private String movingTime = "0:00";

    TrackView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        background.setColor(Color.argb(230, 0, 0, 0));
        lane.setStyle(Paint.Style.STROKE);
        lane.setStrokeWidth(14 * density);
        lane.setColor(Color.rgb(150, 60, 50)); // track red
        progress.setStyle(Paint.Style.STROKE);
        progress.setStrokeWidth(14 * density);
        progress.setStrokeCap(Paint.Cap.ROUND);
        progress.setColor(Color.rgb(140, 230, 160));
        finish.setStrokeWidth(3 * density);
        finish.setColor(Color.WHITE);
        dot.setColor(Color.WHITE);
        big.setColor(Color.WHITE);
        big.setTextSize(30 * density);
        big.setTextAlign(Paint.Align.CENTER);
        big.setFakeBoldText(true);
        small.setColor(Color.rgb(210, 215, 225));
        small.setTextSize(20 * density);
        small.setTextAlign(Paint.Align.CENTER);
    }

    void update(double meters, String pace, String movingTime) {
        this.laps = (int) (meters / LAP_METERS);
        this.lapFraction = (float) ((meters % LAP_METERS) / LAP_METERS);
        this.pace = pace;
        this.movingTime = movingTime;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float inset = 16 * density;
        float r = (h - 2 * inset) / 2f;
        float cx = w / 2f, cy = h / 2f;
        float half = Math.max(0, w / 2f - inset - r); // half the straight

        // Start at the finish line (middle of the bottom straight) and run counter-clockwise.
        track.reset();
        track.moveTo(cx, cy + r);
        track.lineTo(cx + half, cy + r);
        track.arcTo(new RectF(cx + half - r, cy - r, cx + half + r, cy + r), 90, -180);
        track.lineTo(cx - half, cy - r);
        track.arcTo(new RectF(cx - half - r, cy - r, cx - half + r, cy + r), 270, -180);
        track.lineTo(cx, cy + r);
        measure.setPath(track, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float corner = 24 * density;
        canvas.drawRoundRect(0, 0, w, h, corner, corner, background);

        canvas.drawPath(track, lane);
        float length = measure.getLength();
        if (lapFraction > 0) {
            done.reset();
            measure.getSegment(0, lapFraction * length, done, true);
            canvas.drawPath(done, progress);
        }
        float cy = h / 2f, r = (h - 32 * density) / 2f;
        canvas.drawLine(w / 2f, cy + r - 12 * density, w / 2f, cy + r + 12 * density, finish);

        measure.getPosTan(lapFraction * length, pos, null);
        canvas.drawCircle(pos[0], pos[1], 11 * density, dot);

        canvas.drawText("LAP " + (laps + 1) + "  ·  " + laps + " done", w / 2f, cy - 12 * density, big);
        canvas.drawText(pace + "   ·   moving " + movingTime, w / 2f, cy + 24 * density, small);
    }
}
