package wseemann.media.fmpdemo.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import java.io.IOException;

import wseemann.media.fmpdemo.R;
import wseemann.media.fmpdemo.helper.FileExplorerHelper;
import wseemann.media.fmpdemo.helper.MediaPlayerHelper;
import wseemann.media.fmpdemo.helper.SurfaceHelper;

public class VideoPlayerActivity extends FragmentActivity implements 
        SurfaceHelper.SurfaceListener {

    private SurfaceView mSurfaceView;
    private TextView mFileNameText;
    private Button mOpenFileButton;
    private SurfaceHelper mSurfaceHelper;
    private MediaPlayerHelper mMediaPlayerHelper;
    private Uri mCurrentFileUri;

    public void onCreate(Bundle icicle) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(icicle);
        setContentView(R.layout.activity_video_player);

        mSurfaceView = findViewById(R.id.surfaceview);
        mFileNameText = findViewById(R.id.file_name_text);
        mOpenFileButton = findViewById(R.id.open_file_button);
        
        mMediaPlayerHelper = new MediaPlayerHelper();
        mSurfaceHelper = new SurfaceHelper(mSurfaceView, this);
        
        mOpenFileButton.setOnClickListener(v -> {
            FileExplorerHelper.openFilePicker(this);
        });
        
        // Добавим кнопку для перемотки и управления воспроизведением
        Button playPauseButton = findViewById(R.id.play_pause_button);
        playPauseButton.setOnClickListener(v -> {
            if (mMediaPlayerHelper.isPlaying()) {
                mMediaPlayerHelper.pause();
                playPauseButton.setText("Play");
            } else {
                mMediaPlayerHelper.start();
                playPauseButton.setText("Pause");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == FileExplorerHelper.REQUEST_CODE_OPEN_FILE && 
            resultCode == RESULT_OK && data != null) {
            
            Uri uri = data.getData();
            if (uri != null) {
                // Получаем постоянные права на доступ к файлу
                getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                
                mCurrentFileUri = uri;
                String fileName = FileExplorerHelper.getFileName(this, uri);
                mFileNameText.setText(fileName);
                
                try {
                    mMediaPlayerHelper.setDataSource(uri.toString());
                    Toast.makeText(this, "Playing: " + fileName, Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(this, "Error playing file", Toast.LENGTH_SHORT).show();
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface) {
        mMediaPlayerHelper.setSurface(surface);
        // Если файл уже выбран, перезапускаем воспроизведение
        if (mCurrentFileUri != null) {
            try {
                mMediaPlayerHelper.setDataSource(mCurrentFileUri.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onSurfaceDestroyed() {
        mMediaPlayerHelper.setSurface(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mMediaPlayerHelper.release();
    }
}
