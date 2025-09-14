package wseemann.media.fmpdemo.helper;

import android.util.Log;
import android.view.Surface;

import java.io.IOException;

import wseemann.media.FFmpegMediaPlayer;

public class MediaPlayerHelper implements 
        FFmpegMediaPlayer.OnPreparedListener,
        FFmpegMediaPlayer.OnErrorListener {

    private FFmpegMediaPlayer mMediaPlayer;
    private Surface mSurface;
    private String mDataSource;

    public void setSurface(Surface surface) {
        mSurface = surface;
        tryToPrepare();
    }

    public void setDataSource(String path) throws IOException {
        mDataSource = path;
        tryToPrepare();
    }

    private void tryToPrepare() throws IOException {
        if (mMediaPlayer != null) {
            mMediaPlayer.reset();
        } else {
            mMediaPlayer = new FFmpegMediaPlayer();
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnErrorListener(this);
        }

        if (mDataSource != null) {
            mMediaPlayer.setDataSource(mDataSource);
            if (mSurface != null) {
                mMediaPlayer.setSurface(mSurface);
            }
            mMediaPlayer.prepareAsync();
        }
    }

    public void start() {
        if (mMediaPlayer != null) {
            mMediaPlayer.start();
        }
    }

    public void stop() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
        }
    }

    public void release() {
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    @Override
    public void onPrepared(FFmpegMediaPlayer mp) {
        mp.start();
    }

    @Override
    public boolean onError(FFmpegMediaPlayer mp, int what, int extra) {
        Log.d("MediaPlayerHelper", "Error: " + what);
        return true;
    }
}
