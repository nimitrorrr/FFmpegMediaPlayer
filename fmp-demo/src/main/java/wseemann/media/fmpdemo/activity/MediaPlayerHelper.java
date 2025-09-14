// Добавим эти методы в класс MediaPlayerHelper
public void pause() {
    if (mMediaPlayer != null) {
        mMediaPlayer.pause();
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
        mMediaPlayer.seekTo(position);
    }
}
