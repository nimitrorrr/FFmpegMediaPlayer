package wseemann.media.fmpdemo.helper;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class SurfaceHelper implements SurfaceHolder.Callback {

    public interface SurfaceListener {
        void onSurfaceCreated(Surface surface);
        void onSurfaceDestroyed();
    }

    private Surface mSurface;
    private SurfaceHolder mSurfaceHolder;
    private SurfaceListener mListener;

    public SurfaceHelper(SurfaceView surfaceView, SurfaceListener listener) {
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        mListener = listener;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurface = holder.getSurface();
        if (mListener != null) {
            mListener.onSurfaceCreated(mSurface);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurface = null;
        if (mListener != null) {
            mListener.onSurfaceDestroyed();
        }
    }
}
