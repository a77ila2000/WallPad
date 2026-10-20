package com.homepad.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Three chevrons light in travel order; the clock keeps motion continuous across status redraws. */
final class ElevatorMovementView extends View {
    private final int direction;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ElevatorMovementView(Context context, int direction, int color) {
        super(context);
        this.direction = direction;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(2.7f * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float step = getHeight() / 3f;
        float center = getWidth() / 2f;
        float wing = Math.min(getWidth() * 0.33f, step * 0.36f);
        float phase = (SystemClock.uptimeMillis() % 720L) / 240f;
        for (int i = 0; i < 3; i++) {
            int travelIndex = direction == 2 ? i : 2 - i;
            float distance = Math.abs(travelIndex - phase);
            distance = Math.min(distance, 3f - distance);
            paint.setAlpha((int) (60 + 195 * Math.max(0f, 1f - distance)));
            float y = step * (i + 0.5f);
            float tip = direction == 2 ? y + wing * 0.55f : y - wing * 0.55f;
            float end = direction == 2 ? y - wing * 0.55f : y + wing * 0.55f;
            canvas.drawLine(center - wing, end, center, tip, paint);
            canvas.drawLine(center, tip, center + wing, end, paint);
        }
        if (isAttachedToWindow()) postInvalidateDelayed(50);
    }
}
