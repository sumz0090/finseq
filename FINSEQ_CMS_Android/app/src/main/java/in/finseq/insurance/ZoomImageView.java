package in.finseq.insurance;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;
import android.widget.ImageView;

public class ZoomImageView extends ImageView {
    private final Matrix transform = new Matrix();
    private ScaleGestureDetector detector;
    private float zoom = 1f, lastX, lastY;

    public ZoomImageView(Context context) { super(context); init(context); }
    public ZoomImageView(Context context, AttributeSet attrs) { super(context, attrs); init(context); }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        detector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector gesture) {
                float next = Math.max(1f, Math.min(6f, zoom * gesture.getScaleFactor()));
                float factor = next / zoom;
                zoom = next;
                transform.postScale(factor, factor, gesture.getFocusX(), gesture.getFocusY());
                clamp();
                setImageMatrix(transform);
                return true;
            }
        });
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (detector != null) resetZoom();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetZoom();
    }

    public void resetZoom() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        int bitmapWidth = drawable.getIntrinsicWidth(), bitmapHeight = drawable.getIntrinsicHeight();
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return;
        float fit = Math.min((float) getWidth() / bitmapWidth, (float) getHeight() / bitmapHeight);
        transform.setScale(fit, fit);
        transform.postTranslate((getWidth() - bitmapWidth * fit) / 2f,
                (getHeight() - bitmapHeight * fit) / 2f);
        zoom = 1f;
        setImageMatrix(transform);
    }

    private void clamp() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        transform.mapRect(bounds);
        float dx = bounds.width() <= getWidth() ? (getWidth() / 2f - bounds.centerX())
                : bounds.left > 0 ? -bounds.left : bounds.right < getWidth() ? getWidth() - bounds.right : 0;
        float dy = bounds.height() <= getHeight() ? (getHeight() / 2f - bounds.centerY())
                : bounds.top > 0 ? -bounds.top : bounds.bottom < getHeight() ? getHeight() - bounds.bottom : 0;
        transform.postTranslate(dx, dy);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        ViewParent parent = getParent();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            lastX = event.getX(); lastY = event.getY();
        }
        detector.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !detector.isInProgress()) {
            if (zoom > 1f) {
                transform.postTranslate(event.getX() - lastX, event.getY() - lastY);
                clamp();
                setImageMatrix(transform);
            } else if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            lastX = event.getX(); lastY = event.getY();
        }
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && parent != null)
            parent.requestDisallowInterceptTouchEvent(false);
        return true;
    }
}
