package app.carbuddy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class MascotView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private final ValueAnimator idle = ValueAnimator.ofFloat(0f, 1f);
    private final ValueAnimator reaction = ValueAnimator.ofFloat(0f, 1f);
    private CharacterArt current;
    private Expression face, previousFace;
    private Bitmap sheet, previousSheet;
    private float phase, progress = 1f;

    MascotView(Context context) {
        super(context);
        idle.setDuration(3200);
        idle.setInterpolator(new LinearInterpolator());
        idle.setRepeatCount(ValueAnimator.INFINITE);
        idle.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
        reaction.setInterpolator(new LinearInterpolator());
        reaction.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            if (progress == 1f) previousSheet = null;
            invalidate();
        });
    }

    void show(CharacterArt next, Expression expression) {
        if (current == next && face == expression) return;
        reaction.cancel();
        previousSheet = sheet;
        previousFace = face;
        boolean switched = current != next;
        if (switched) sheet = BitmapFactory.decodeResource(getResources(), next.image);
        current = next;
        face = expression;
        if (isShown() && ValueAnimator.areAnimatorsEnabled()) {
            if (!idle.isStarted()) idle.start();
            if (previousSheet != null) {
                reaction.setDuration(switched ? 350 : 500);
                reaction.start();
            }
        } else {
            stopMotion();
        }
        invalidate();
    }

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && sheet != null && ValueAnimator.areAnimatorsEnabled()) {
            if (!idle.isStarted()) idle.start();
        } else {
            stopMotion();
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }

    private void stopMotion() {
        idle.cancel();
        reaction.cancel();
        previousSheet = null;
        phase = 0f;
        progress = 1f;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sheet == null) return;
        float motionScale = Math.min(1f, getHeight() / (float) dp(220));
        float wave = (float) Math.sin(phase * 2 * Math.PI);
        float resting = -dp(5) * wave * motionScale;
        float wobble = (float) Math.sin(progress * Math.PI * 4) * (1f - progress);
        float impact = (float) Math.sin(progress * Math.PI);
        float crossfade = Math.min(1f, progress * 3f);
        boolean switchingCharacter = previousSheet != null && previousSheet != sheet;
        if (previousSheet != null && crossfade < 1f) {
            drawFace(canvas, previousSheet, previousFace, switchingCharacter ? -dp(20) * crossfade * motionScale : 0,
                    resting, 0, 1f, 1f - crossfade);
        }
        float x = 0, y = resting, tilt = 0, scaleY = 1f;
        if (switchingCharacter) {
            x = dp(26) * (1f - crossfade) * motionScale;
            scaleY -= .035f * impact;
        } else {
            switch (face) {
                case FUEL_LOW -> x = dp(9) * wobble * motionScale;
                case MUSIC -> {
                    tilt = 3f * wave;
                    y -= dp(7) * impact * motionScale;
                }
                case BRAKE -> {
                    y -= dp(18) * impact * motionScale;
                    tilt = -6f * impact;
                    scaleY += .06f * impact;
                }
                case SPEED -> tilt = 7f * impact;
                    case ACCELERATION -> tilt = -7f * impact;
            }
        }
        drawFace(canvas, sheet, face, x, y, tilt, scaleY, crossfade);
    }

    private void drawFace(Canvas canvas, Bitmap bitmap, Expression expression,
                          float offsetX, float offsetY, float tilt, float scaleY, float opacity) {
        int cellWidth = bitmap.getWidth() / 2;
        int cellHeight = bitmap.getHeight() / 3;
        int left = expression.spriteIndex() % 2 * cellWidth;
        int top = expression.spriteIndex() / 2 * cellHeight;
        source.set(left, top, left + cellWidth, top + cellHeight);
        float scale = Math.min(getWidth() * .9f / cellWidth, getHeight() * .91f / cellHeight);
        float width = cellWidth * scale, height = cellHeight * scale;
        float x = (getWidth() - width) / 2 + offsetX;
        float y = (getHeight() - height) / 2 + offsetY;
        destination.set(x, y, x + width, y + height);
        canvas.save();
        canvas.rotate(tilt, destination.centerX(), destination.bottom);
        canvas.scale(1f, scaleY, destination.centerX(), destination.bottom);
        paint.setAlpha(Math.round(opacity * 255));
        canvas.drawBitmap(bitmap, source, destination, paint);
        canvas.restore();
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }
}
