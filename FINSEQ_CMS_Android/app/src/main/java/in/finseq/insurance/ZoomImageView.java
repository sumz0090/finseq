package in.finseq.insurance;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;
import android.widget.ImageView;

public class ZoomImageView extends ImageView {
    public interface PageSwipeListener { void onNext(); void onPrevious(); }
    private final Matrix transform=new Matrix();
    private ScaleGestureDetector detector; private GestureDetector gestures;
    private float zoom=1f,lastX,lastY; private PageSwipeListener swipeListener;
    public ZoomImageView(Context c){super(c);init(c);} public ZoomImageView(Context c,AttributeSet a){super(c,a);init(c);}
    public void setPageSwipeListener(PageSwipeListener l){swipeListener=l;}
    public boolean isZoomed(){return zoom>1.01f;}
    private void init(Context c){setScaleType(ScaleType.MATRIX);
        detector=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector g){float next=Math.max(1f,Math.min(6f,zoom*g.getScaleFactor()));float factor=next/zoom;zoom=next;transform.postScale(factor,factor,g.getFocusX(),g.getFocusY());clamp();setImageMatrix(transform);return true;}});
        gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDoubleTap(MotionEvent e){if(zoom>1.01f){resetZoom();}else{zoom=2f;transform.postScale(2f,2f,e.getX(),e.getY());clamp();setImageMatrix(transform);}return true;}
            @Override public boolean onFling(MotionEvent e1,MotionEvent e2,float vx,float vy){if(isZoomed()||swipeListener==null)return false;float dx=e2.getX()-e1.getX();if(Math.abs(dx)>120&&Math.abs(vx)>400){if(dx<0)swipeListener.onNext();else swipeListener.onPrevious();return true;}return false;}
        });
    }
    @Override public void setImageDrawable(Drawable d){super.setImageDrawable(d);if(detector!=null)resetZoom();}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);resetZoom();}
    public void resetZoom(){Drawable d=getDrawable();if(d==null||getWidth()==0||getHeight()==0)return;int bw=d.getIntrinsicWidth(),bh=d.getIntrinsicHeight();if(bw<=0||bh<=0)return;float fit=Math.min((float)getWidth()/bw,(float)getHeight()/bh);transform.setScale(fit,fit);transform.postTranslate((getWidth()-bw*fit)/2f,(getHeight()-bh*fit)/2f);zoom=1f;setImageMatrix(transform);}
    private void clamp(){Drawable d=getDrawable();if(d==null)return;RectF b=new RectF(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight());transform.mapRect(b);float dx=b.width()<=getWidth()?(getWidth()/2f-b.centerX()):b.left>0?-b.left:b.right<getWidth()?getWidth()-b.right:0;float dy=b.height()<=getHeight()?(getHeight()/2f-b.centerY()):b.top>0?-b.top:b.bottom<getHeight()?getHeight()-b.bottom:0;transform.postTranslate(dx,dy);}
    @Override public boolean onTouchEvent(MotionEvent e){ViewParent p=getParent();int a=e.getActionMasked();if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){if(p!=null)p.requestDisallowInterceptTouchEvent(true);lastX=e.getX();lastY=e.getY();}detector.onTouchEvent(e);gestures.onTouchEvent(e);if(a==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&!detector.isInProgress()){if(isZoomed()){transform.postTranslate(e.getX()-lastX,e.getY()-lastY);clamp();setImageMatrix(transform);}else if(p!=null)p.requestDisallowInterceptTouchEvent(false);lastX=e.getX();lastY=e.getY();}if((a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)&&p!=null)p.requestDisallowInterceptTouchEvent(false);return true;}
}
