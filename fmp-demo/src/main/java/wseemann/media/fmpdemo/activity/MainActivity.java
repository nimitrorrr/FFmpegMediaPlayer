package wseemann.media.fmpdemo.activity;

import wseemann.media.fmpdemo.R;
import wseemann.media.fmpdemo.service.IMediaPlaybackService;
import wseemann.media.fmpdemo.service.MediaPlaybackService;
import wseemann.media.fmpdemo.service.MusicUtils;
import wseemann.media.fmpdemo.service.MusicUtils.ServiceToken;
import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends FragmentActivity {
    
    private static final String TAG = MainActivity.class.getName();
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private IMediaPlaybackService mService = null;
    private ServiceToken mToken;
    private SkinWindow mSkinWindow;
    private boolean paused = false;
    private Uri skinFolderUri = null;
    
    private static final int REFRESH = 1;
    private static final int QUIT = 2;
    
    private ActivityResultLauncher<Intent> folderPickerLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            super.onCreate(savedInstanceState);
            
            setVolumeControlStream(AudioManager.STREAM_MUSIC);
            
            Log.d(TAG, "MainActivity onCreate started");
            
            // Создаем временный SkinWindow чтобы избежать null reference
            mSkinWindow = new SkinWindow(this);
            setContentView(mSkinWindow);
            
            // Инициализируем folder picker launcher
            folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    try {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            skinFolderUri = result.getData().getData();
                            if (skinFolderUri != null) {
                                // Сохраняем persistable permission
                                getContentResolver().takePersistableUriPermission(
                                    skinFolderUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                );
                                
                                Log.d(TAG, "Folder selected: " + skinFolderUri);
                                
                                // Создаем SkinWindow после получения доступа к папке
                                createSkinWindow();
                            }
                        } else {
                            showError("No folder selected");
                        }
                    } catch (Exception e) {
                        showError("Error processing folder selection: " + e.getMessage());
                    }
                }
            );
            
            // Проверяем доступ к папке или запрашиваем его
            checkStorageAccess();
            
        } catch (Exception e) {
            showError("Error in onCreate: " + e.getMessage());
            finish();
        }
    }
    
    private void checkStorageAccess() {
        try {
            Log.d(TAG, "Checking storage access");
            // Для Android 12 сразу запрашиваем выбор папки
            requestFolderAccess();
        } catch (Exception e) {
            showError("Error requesting folder access: " + e.getMessage());
            Log.e(TAG, "Error in checkStorageAccess", e);
        }
    }
    
    private void requestFolderAccess() {
        Log.d(TAG, "Requesting folder access");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | 
                       Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        
        // Пытаемся открыть папку winamp_skin если она существует
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Awinamp_skin");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        
        folderPickerLauncher.launch(intent);
    }
    
    private void createSkinWindow() {
        try {
            Log.d(TAG, "Creating SkinWindow");
            mSkinWindow = new SkinWindow(this);
            if (skinFolderUri != null) {
                mSkinWindow.setSkinFolderUri(skinFolderUri);
            }
            
            // Если сервис уже подключен, передаем его в SkinWindow
            if (mService != null) {
                mSkinWindow.setMediaService(mService);
                Log.d(TAG, "Service already connected, setting to SkinWindow");
            }
            
            setContentView(mSkinWindow);
            
            Log.d(TAG, "SkinWindow created successfully");
        } catch (Exception e) {
            showError("Error creating skin window: " + e.getMessage());
            Log.e(TAG, "Error in createSkinWindow", e);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                createSkinWindow();
            } else {
                showError("Storage permission required to load skins");
                finish();
            }
        }
    }
    
    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Log.e(TAG, message);
    }
    
    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "onStart called");
        paused = false;
        
        mToken = MusicUtils.bindToService(this, osc);
        if (mToken == null) {
            mHandler.sendEmptyMessage(QUIT);
        }
        
        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.PLAYSTATE_CHANGED);
        f.addAction(MediaPlaybackService.META_CHANGED);
        registerReceiver(mStatusListener, f);
    }
    
    @Override
    public void onStop() {
        Log.d(TAG, "onStop called");
        paused = true;
        mHandler.removeMessages(REFRESH);
        if (mStatusListener != null) {
            try {
                unregisterReceiver(mStatusListener);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering receiver", e);
            }
        }
        if (mToken != null) {
            MusicUtils.unbindFromService(mToken);
        }
        mService = null;
        super.onStop();
    }
    
    private ServiceConnection osc = new ServiceConnection() {
        public void onServiceConnected(ComponentName classname, IBinder obj) {
            Log.d(TAG, "Service connected");
            mService = IMediaPlaybackService.Stub.asInterface(obj);
            
            // Добавляем null check чтобы предотвратить race condition
            if (mSkinWindow != null) {
                mSkinWindow.setMediaService(mService);
                Log.d(TAG, "Service set to existing SkinWindow");
            } else {
                Log.d(TAG, "SkinWindow is null, service will be set later");
            }
            // Если mSkinWindow равен null, сервис будет установлен позже в createSkinWindow()
            
            startPlayback();
        }
        
        public void onServiceDisconnected(ComponentName classname) {
            Log.d(TAG, "Service disconnected");
            mService = null;
        }
    };
    
    private void startPlayback() {
        if (mService == null) {
            Log.w(TAG, "Cannot start playback - service is null");
            return;
        }
        long next = refreshNow();
        queueNextRefresh(next);
    }
    
    private void queueNextRefresh(long delay) {
        if (!paused) {
            Message msg = mHandler.obtainMessage(REFRESH);
            mHandler.removeMessages(REFRESH);
            mHandler.sendMessageDelayed(msg, delay);
        }
    }

    private long refreshNow() {
        if (mService == null || mSkinWindow == null) {
            return 500;
        }
        try {
            mSkinWindow.updateDisplay();
        } catch (Exception e) {
            Log.e(TAG, "Error updating display", e);
        }
        return 1000; // Обновляем каждую секунду
    }
    
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REFRESH:
                    long next = refreshNow();
                    queueNextRefresh(next);
                    break;
                case QUIT:
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Service Error")
                            .setMessage("Cannot start media service")
                            .setPositiveButton(android.R.string.ok,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int whichButton) {
                                            finish();
                                        }
                                    })
                            .setCancelable(false)
                            .show();
                    break;
            }
        }
    };

    private BroadcastReceiver mStatusListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && (action.equals(MediaPlaybackService.META_CHANGED) || 
                action.equals(MediaPlaybackService.PLAYSTATE_CHANGED))) {
                // Добавляем null check
                if (mSkinWindow != null) {
                    try {
                        mSkinWindow.updateDisplay();
                    } catch (Exception e) {
                        Log.e(TAG, "Error updating display from broadcast", e);
                    }
                }
            }
        }
    };
    
    // SkinWindow класс - основной класс для рендеринга скинов
    public static class SkinWindow extends View {
        
        private Map<String, Bitmap> skinBitmaps;
        private Map<String, Rect> buttonRegions;
        private IMediaPlaybackService mService;
        private Paint paint;
        private float scaleX = 1.0f, scaleY = 1.0f;
        private int windowType = 0; // 0 = Main, 1 = Equalizer, 2 = Playlist
        private Uri skinFolderUri;
        
        // Размеры оригинальных окон Winamp
        private static final int MAIN_WIDTH = 275;
        private static final int MAIN_HEIGHT = 116;
        
        public SkinWindow(Context context) {
            super(context);
            try {
                init();
            } catch (Exception e) {
                Log.e(TAG, "Error in SkinWindow constructor", e);
            }
        }
        
        public SkinWindow(Context context, AttributeSet attrs) {
            super(context, attrs);
            try {
                init();
            } catch (Exception e) {
                Log.e(TAG, "Error in SkinWindow constructor", e);
            }
        }
        
        private void init() {
            paint = new Paint();
            paint.setAntiAlias(true);
            skinBitmaps = new HashMap<>();
            buttonRegions = new HashMap<>();
            
            Log.d(TAG, "SkinWindow initialized");
        }
        
        public void setSkinFolderUri(Uri uri) {
            skinFolderUri = uri;
            loadSkin();
        }
        
        private void loadSkin() {
            if (skinFolderUri == null) {
                Log.w(TAG, "No skin folder selected");
                return;
            }
            
            try {
                Log.d(TAG, "Loading skin from URI: " + skinFolderUri);
                
                DocumentFile skinDir = DocumentFile.fromTreeUri(getContext(), skinFolderUri);
                if (skinDir == null || !skinDir.exists()) {
                    showError("Skin folder not accessible");
                    return;
                }
                
                Log.d(TAG, "Skin folder accessible: " + skinDir.getName());
                
                // Ищем .wsz файлы
                DocumentFile[] files = skinDir.listFiles();
                if (files == null) {
                    showError("Cannot read files from selected folder");
                    return;
                }
                
                Log.d(TAG, "Found " + files.length + " files in folder");
                
                DocumentFile wszFile = null;
                for (DocumentFile file : files) {
                    if (file != null && file.getName() != null && file.getName().toLowerCase().endsWith(".wsz")) {
                        wszFile = file;
                        Log.d(TAG, "Found .wsz file: " + file.getName());
                        break;
                    }
                }
                
                if (wszFile == null) {
                    showError("No .wsz skin files found in selected folder");
                    return;
                }
                
                extractAndLoadSkin(wszFile);
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading skin", e);
                showError("Error loading skin: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        
        private void extractAndLoadSkin(DocumentFile wszFile) throws IOException {
            // Создаем временную папку для извлечения
            File tempDir = new File(getContext().getCacheDir(), "skin_temp");
            if (tempDir.exists()) {
                deleteRecursive(tempDir);
            }
            if (!tempDir.mkdirs()) {
                throw new IOException("Cannot create temp directory");
            }
            
            Log.d(TAG, "Extracting skin to: " + tempDir.getAbsolutePath());
            
            // Извлекаем .wsz (ZIP) файл
            try (InputStream inputStream = getContext().getContentResolver().openInputStream(wszFile.getUri())) {
                if (inputStream == null) {
                    throw new IOException("Cannot open input stream for " + wszFile.getName());
                }
                
                try (ZipInputStream zis = new ZipInputStream(inputStream)) {
                    ZipEntry entry;
                    byte[] buffer = new byte[1024];
                    
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        
                        File outFile = new File(tempDir, entry.getName());
                        File parentDir = outFile.getParentFile();
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs();
                        }
                        
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }
                        
                        Log.d(TAG, "Extracted: " + entry.getName());
                    }
                }
            }
            
            // Загружаем bitmap'ы
            loadBitmaps(tempDir);
        }
        
        private void loadBitmaps(File skinDir) {
            String[] bitmapNames = {
                "main.bmp", "cbuttons.bmp", "titlebar.bmp", 
                "text.bmp", "numbers.bmp", "volume.bmp", 
                "balance.bmp", "monoster.bmp", "playpaus.bmp",
                "pledit.bmp", "eqmain.bmp", "eq_ex.bmp"
            };
            
            for (String name : bitmapNames) {
                File bmpFile = new File(skinDir, name);
                if (bmpFile.exists()) {
                    try {
                        Bitmap bitmap = BitmapFactory.decodeFile(bmpFile.getAbsolutePath());
                        if (bitmap != null) {
                            skinBitmaps.put(name, bitmap);
                            Log.d(TAG, "Loaded bitmap: " + name + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not load bitmap: " + name, e);
                    }
                }
            }
            
            // Загружаем region данные
            loadRegions(skinDir);
            
            Log.i(TAG, "Loaded " + skinBitmaps.size() + " skin bitmaps");
            
            // Перерисовываем после загрузки
            post(this::invalidate);
        }
        
        private void deleteRecursive(File fileOrDirectory) {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child);
                    }
                }
            }
            fileOrDirectory.delete();
        }
        
        private void loadRegions(File skinDir) {
            // Ищем файлы с region данными
            File[] regionFiles = {
                new File(skinDir, "region.txt"),
                new File(skinDir, "main.rgn"),
                new File(skinDir, "pledit.rgn"),
                new File(skinDir, "eqmain.rgn")
            };
            
            for (File regionFile : regionFiles) {
                if (regionFile.exists()) {
                    parseRegionFile(regionFile);
                }
            }
            
            // Если region файлы не найдены, используем стандартные координаты
            if (buttonRegions.isEmpty()) {
                setupDefaultRegions();
            }
            
            Log.i(TAG, "Loaded " + buttonRegions.size() + " button regions");
        }
        
        private void parseRegionFile(File regionFile) {
            try {
                if (regionFile.getName().equals("region.txt")) {
                    parseRegionTxt(regionFile);
                } else if (regionFile.getName().endsWith(".rgn")) {
                    parseRgnFile(regionFile);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing region file: " + regionFile.getName(), e);
            }
        }
        
        private void parseRegionTxt(File file) throws IOException {
            // Парсим region.txt файл
            // Формат обычно: ButtonName=x1,y1,x2,y2
            java.util.Scanner scanner = new java.util.Scanner(file);
            try {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    
                    String[] parts = line.split("=");
                    if (parts.length == 2) {
                        String buttonName = parts[0].trim();
                        String[] coords = parts[1].split(",");
                        if (coords.length == 4) {
                            try {
                                int x1 = Integer.parseInt(coords[0].trim());
                                int y1 = Integer.parseInt(coords[1].trim());
                                int x2 = Integer.parseInt(coords[2].trim());
                                int y2 = Integer.parseInt(coords[3].trim());
                                buttonRegions.put(buttonName.toLowerCase(), new Rect(x1, y1, x2, y2));
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Invalid coordinates in region.txt: " + line);
                            }
                        }
                    }
                }
            } finally {
                scanner.close();
            }
        }
        
        private void parseRgnFile(File file) throws IOException {
            // Парсим .rgn файл (бинарный формат)
            // Для простоты пока используем стандартные координаты
            // TODO: Реализовать полный парсинг .rgn формата
            setupDefaultRegions();
        }
        
        private void setupDefaultRegions() {
            // Стандартные координаты кнопок для Winamp 2.91
            buttonRegions.put("previous", new Rect(16, 88, 44, 104));
            buttonRegions.put("play", new Rect(45, 88, 73, 104));
            buttonRegions.put("pause", new Rect(45, 88, 73, 104));
            buttonRegions.put("stop", new Rect(104, 88, 132, 104));
            buttonRegions.put("next", new Rect(74, 88, 102, 104));
            buttonRegions.put("eject", new Rect(133, 89, 153, 109));
            
            // Дополнительные элементы
            buttonRegions.put("shuffle", new Rect(164, 89, 180, 109));
            buttonRegions.put("repeat", new Rect(181, 89, 197, 109));
            buttonRegions.put("volume", new Rect(107, 57, 147, 68));
            buttonRegions.put("balance", new Rect(177, 57, 217, 68));
            buttonRegions.put("position", new Rect(16, 72, 248, 82));
            
            Log.d(TAG, "Setup default regions");
        }
        
        private void showError(String message) {
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            Log.e(TAG, message);
        }
        
        public void setMediaService(IMediaPlaybackService service) {
            mService = service;
            Log.d(TAG, "Media service set to SkinWindow");
            updateDisplay();
        }
        
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Вычисляем масштаб для растягивания скина
            scaleX = (float) w / MAIN_WIDTH;
            scaleY = (float) h / MAIN_HEIGHT;
            Log.d(TAG, "Size changed: " + w + "x" + h + ", scale: " + scaleX + "x" + scaleY);
            invalidate();
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            try {
                if (skinBitmaps.isEmpty()) {
                    // Рисуем заглушку если скин не загружен
                    paint.setColor(0xFF333333);
                    canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                    paint.setColor(0xFFFFFFFF);
                    paint.setTextSize(24);
                    canvas.drawText("No skin loaded", 50, 50, paint);
                    canvas.drawText("Please select a folder with Winamp skins", 50, 80, paint);
                    return;
                }
                
                drawMainWindow(canvas);
            } catch (Exception e) {
                Log.e(TAG, "Error in onDraw", e);
            }
        }
        
        private void drawMainWindow(Canvas canvas) {
            try {
                // Рисуем основное окно
                Bitmap mainBg = skinBitmaps.get("main.bmp");
                if (mainBg != null) {
                    Rect srcRect = new Rect(0, 0, mainBg.getWidth(), mainBg.getHeight());
                    Rect destRect = new Rect(0, 0, getWidth(), getHeight());
                    canvas.drawBitmap(mainBg, srcRect, destRect, paint);
                }
                
                // TODO: Добавить отрисовку кнопок, текста времени и т.д.
            } catch (Exception e) {
                Log.e(TAG, "Error drawing main window", e);
            }
        }
        
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                try {
                    handleTouch(event.getX(), event.getY());
                } catch (Exception e) {
                    Log.e(TAG, "Error handling touch", e);
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
        
        private void handleTouch(float x, float y) {
            // Переводим координаты касания в координаты оригинального скина
            float origX = x / scaleX;
            float origY = y / scaleY;
            
            // Проверяем все кнопки
            for (Map.Entry<String, Rect> entry : buttonRegions.entrySet()) {
                String buttonName = entry.getKey();
                Rect region = entry.getValue();
                
                if (region.contains((int)origX, (int)origY)) {
                    handleButtonClick(buttonName);
                    break;
                }
            }
        }
        
        private void handleButtonClick(String buttonName) {
            if (mService == null) {
                Log.w(TAG, "Cannot handle button click - service is null");
                return;
            }
            
            try {
                switch (buttonName) {
                    case "previous":
                        mService.prev();
                        break;
                    case "play":
                        if (!mService.isPlaying()) {
                            mService.play();
                        }
                        break;
                    case "pause":
                        if (mService.isPlaying()) {
                            mService.pause();
                        }
                        break;
                    case "stop":
                        mService.pause();
                        mService.seek(0);
                        break;
                    case "next":
                        mService.next();
                        break;
                    case "shuffle":
                        toggleShuffle();
                        break;
                    case "repeat":
                        toggleRepeat();
                        break;
                    // TODO: Добавить обработку volume, balance, position
                }
                
                Log.d(TAG, "Button clicked: " + buttonName);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in handleButtonClick", e);
            }
        }
        
        private void toggleShuffle() {
            try {
                if (mService != null) {
                    int shuffle = mService.getShuffleMode();
                    if (shuffle == MediaPlaybackService.SHUFFLE_NONE) {
                        mService.setShuffleMode(MediaPlaybackService.SHUFFLE_NORMAL);
                    } else {
                        mService.setShuffleMode(MediaPlaybackService.SHUFFLE_NONE);
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error toggling shuffle", e);
            }
        }
        
        private void toggleRepeat() {
            try {
                if (mService != null) {
                    int mode = mService.getRepeatMode();
                    if (mode == MediaPlaybackService.REPEAT_NONE) {
                        mService.setRepeatMode(MediaPlaybackService.REPEAT_ALL);
                    } else if (mode == MediaPlaybackService.REPEAT_ALL) {
                        mService.setRepeatMode(MediaPlaybackService.REPEAT_CURRENT);
                    } else {
                        mService.setRepeatMode(MediaPlaybackService.REPEAT_NONE);
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error toggling repeat", e);
            }
        }
        
        public void updateDisplay() {
            // Перерисовываем окно при изменении состояния
            try {
                post(this::invalidate);
            } catch (Exception e) {
                Log.e(TAG, "Error updating display", e);
            }
        }
    }
}
