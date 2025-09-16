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
        try {
            tryToPrepare();
        } catch (IOException e) {
            Log.e("MediaPlayerHelper", "Error preparing player when setting surface", e);
        }
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

    public void pause() {
        if (mMediaPlayer != null) {
            mMediaPlayer.pause();
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

    public boolean isPlaying() {
        return mMediaPlayer != null && mMediaPlayer.isPlaying();
    }

    public long getCurrentPosition() {
        return mMediaPlayer != null ? mMediaPlayer.getCurrentPosition() : 0;
    }

    public long getDuration() {
        return mMediaPlayer != null ? mMediaPlayer.getDuration() : 0;
    }

    public void seekTo(long position) {
        if (mMediaPlayer != null) {
                if (position > Integer.MAX_VALUE) {
                        mMediaPlayer.seekTo(Integer.MAX_VALUE);
                } else if (position < 0) {
                        mMediaPlayer.seekTo(0);
                } else {
                        mMediaPlayer.seekTo((int) position);
                }
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

