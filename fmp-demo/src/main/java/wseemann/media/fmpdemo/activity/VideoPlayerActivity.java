package wseemann.media.fmpdemo.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile; 
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.List;

import wseemann.media.fmpdemo.R;
import wseemann.media.fmpdemo.adapter.FileListAdapter;
import wseemann.media.fmpdemo.helper.FileExplorerHelper;
import wseemann.media.fmpdemo.helper.MediaPlayerHelper;
import wseemann.media.fmpdemo.helper.SurfaceHelper;

public class VideoPlayerActivity extends FragmentActivity implements 
        SurfaceHelper.SurfaceListener,
        FileListAdapter.OnFileSelectedListener {

    private SurfaceView mSurfaceView;
    private RecyclerView mFileRecyclerView;
    private SurfaceHelper mSurfaceHelper;
    private MediaPlayerHelper mMediaPlayerHelper;
    private FileListAdapter mFileAdapter;

    public void onCreate(Bundle icicle) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(icicle);
        setContentView(R.layout.activity_video_player);

        mSurfaceView = findViewById(R.id.surfaceview);
        mFileRecyclerView = findViewById(R.id.file_recycler_view);
        
        mMediaPlayerHelper = new MediaPlayerHelper();
        mSurfaceHelper = new SurfaceHelper(mSurfaceView, this);
        
        setupFileList();
        FileExplorerHelper.openDirectoryPicker(this);
    }

    private void setupFileList() {
        mFileAdapter = new FileListAdapter(this);
        mFileRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mFileRecyclerView.setAdapter(mFileAdapter);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == FileExplorerHelper.REQUEST_CODE_OPEN_DIRECTORY && 
            resultCode == RESULT_OK && data != null) {
            
            Uri treeUri = data.getData();
            getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            List<DocumentFile> files = FileExplorerHelper.listFiles(this, treeUri);
            mFileAdapter.setFiles(files);
        }
    }

    @Override
    public void onSurfaceCreated(Surface surface) {
        mMediaPlayerHelper.setSurface(surface);
    }

    @Override
    public void onSurfaceDestroyed() {
        mMediaPlayerHelper.setSurface(null);
    }

    @Override
    public void onFileSelected(DocumentFile file) {
        try {
            mMediaPlayerHelper.setDataSource(file.getUri().toString());
            Toast.makeText(this, "Playing: " + file.getName(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Error playing file", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mMediaPlayerHelper.release();
    }
}
