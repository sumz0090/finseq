package in.finseq.attendance;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

final class FaceEmbeddingModel implements AutoCloseable {
    private static final int SIZE=160;
    private static final int DIM=128;
    private final Interpreter interpreter;

    FaceEmbeddingModel(Context context) throws Exception {
        Interpreter.Options options=new Interpreter.Options();
        options.setNumThreads(4);
        options.setUseXNNPACK(true);
        interpreter=new Interpreter(loadModel(context,"facenet.tflite"),options);
    }

    private static MappedByteBuffer loadModel(Context context,String name) throws Exception {
        AssetFileDescriptor afd=context.getAssets().openFd(name);
        try(FileInputStream in=new FileInputStream(afd.getFileDescriptor()); FileChannel ch=in.getChannel()){
            return ch.map(FileChannel.MapMode.READ_ONLY,afd.getStartOffset(),afd.getDeclaredLength());
        }
    }

    synchronized double[] embed(Bitmap source){
        Bitmap b=Bitmap.createScaledBitmap(source,SIZE,SIZE,true);
        int[] px=new int[SIZE*SIZE];
        b.getPixels(px,0,SIZE,0,0,SIZE,SIZE);
        float[] values=new float[SIZE*SIZE*3];
        double sum=0, sumSq=0;
        int k=0;
        for(int c:px){
            float r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
            values[k++]=r; values[k++]=g; values[k++]=bl;
            sum+=r+g+bl; sumSq+=r*r+g*g+bl*bl;
        }
        double mean=sum/values.length;
        double variance=Math.max(0,(sumSq/values.length)-(mean*mean));
        double std=Math.sqrt(variance);
        double minStd=1.0/Math.sqrt(values.length);
        if(std<minStd)std=minStd;
        ByteBuffer input=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder());
        for(float v:values)input.putFloat((float)((v-mean)/std));
        input.rewind();
        float[][] output=new float[1][DIM];
        interpreter.run(input,output);
        double[] out=new double[DIM];
        double norm=0;
        for(int i=0;i<DIM;i++){out[i]=output[0][i];norm+=out[i]*out[i];}
        norm=Math.sqrt(norm);
        if(norm>0)for(int i=0;i<DIM;i++)out[i]/=norm;
        return out;
    }

    @Override public void close(){ interpreter.close(); }
}
