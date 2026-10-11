package in.finseq.insurance;
import android.content.*;import android.graphics.*;import android.view.*;import java.util.*;
public class PatternLockView extends View{
 public interface Listener{void onPattern(String value);} Listener listener; Paint p=new Paint(1); ArrayList<Integer> selected=new ArrayList<>(); float[] xs=new float[9],ys=new float[9];float r;
 public PatternLockView(Context c){super(c);p.setStrokeWidth(Ui.dp(c,4));p.setStrokeCap(Paint.Cap.ROUND);setBackgroundColor(Color.TRANSPARENT);}
 public void setListener(Listener l){listener=l;} public void reset(){selected.clear();invalidate();}
 protected void onMeasure(int w,int h){int s=Math.min(MeasureSpec.getSize(w),Ui.dp(getContext(),340));setMeasuredDimension(s,s);}
 protected void onDraw(Canvas c){super.onDraw(c);float cell=getWidth()/3f;r=cell*.13f;for(int i=0;i<9;i++){xs[i]=(i%3+.5f)*cell;ys[i]=(i/3+.5f)*cell;}p.setStyle(Paint.Style.STROKE);p.setColor(Ui.BLUE);for(int i=1;i<selected.size();i++){int a=selected.get(i-1),b=selected.get(i);c.drawLine(xs[a],ys[a],xs[b],ys[b],p);}for(int i=0;i<9;i++){p.setStyle(Paint.Style.FILL);p.setColor(selected.contains(i)?Ui.BLUE:Color.rgb(203,213,225));c.drawCircle(xs[i],ys[i],r,p);}}
 public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN)selected.clear();if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){for(int i=0;i<9;i++){float dx=e.getX()-xs[i],dy=e.getY()-ys[i];if(dx*dx+dy*dy<r*r*2.4f&&!selected.contains(i)){selected.add(i);invalidate();break;}}return true;}if(e.getAction()==MotionEvent.ACTION_UP){StringBuilder b=new StringBuilder();for(int i:selected)b.append(i+1);if(listener!=null)listener.onPattern(b.toString());return true;}return true;}
}
