package wseemann.media.fmpdemo.activity;

import wseemann.media.fmpdemo.R;
import wseemann.media.fmpdemo.service.IMediaPlaybackService;
import wseemann.media.fmpdemo.service.MediaPlaybackService;
import wseemann.media.fmpdemo.service.MusicUtils;
import wseemann.media.fmpdemo.service.MusicUtils.ServiceToken;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends FragmentActivity {
    
    private static final String TAG = MainActivity.class.getName();
    
    private IMediaPlaybackService mService = null;
    private ServiceToken mToken;
    private SkinWindow mSkinWindow;
    private boolean paused = false;
    
    private static final int REFRESH = 1;
    private static final int QUIT = 2;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            super.onCreate(savedInstanceState);
            
            setVolumeControlStream(AudioManager.STREAM_MUSIC);
            
            Log.d(TAG, "MainActivity onCreate started");
            
            // Создаем SkinWindow
            mSkinWindow = new SkinWindow(this);
            setContentView(mSkinWindow);
            
            // Загружаем скин из ресурсов
            mSkinWindow.loadSkinFromAssets();
            
            Log.d(TAG, "SkinWindow created successfully");
        } catch (Exception e) {
            showError("Error in onCreate: " + e.getMessage());
            finish();
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
            
            if (mSkinWindow != null) {
                mSkinWindow.setMediaService(mService);
                Log.d(TAG, "Service set to existing SkinWindow");
            }
            
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
        return 1000;
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
        private int windowType = 0;
        private String mSkinLoadError = null;
        
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
        
        public void loadSkinFromAssets() {
            try {
                Log.d(TAG, "Loading skin from assets");
                
                // Создаем временную папку для извлечения
                File tempDir = new File(getContext().getCacheDir(), "skin_temp");
                Log.d(TAG, "Temp directory: " + tempDir.getAbsolutePath());
                
                if (tempDir.exists()) {
                    Log.d(TAG, "Temp directory exists, deleting...");
                    deleteRecursive(tempDir);
                }
                
                if (!tempDir.mkdirs()) {
                    String error = "Cannot create temp directory: " + tempDir.getAbsolutePath();
                    Log.e(TAG, error);
                    showError(error);
                    return;
                }
                Log.d(TAG, "Temp directory created successfully");
                
                // Извлекаем .wsz файл из assets
                Log.d(TAG, "Opening default.wsz from assets");
                try {
                    // Сначала проверим, какие файлы есть в assets
                    String[] assetFiles = getContext().getAssets().list("");
                    Log.d(TAG, "Files in assets:");
                    for (String file : assetFiles) {
                        Log.d(TAG, " - " + file);
                    }
                    
                    InputStream inputStream = getContext().getAssets().open("default.wsz");
                    Log.d(TAG, "Successfully opened default.wsz from assets");
                    
                    try (ZipInputStream zis = new ZipInputStream(inputStream)) {
                        ZipEntry entry;
                        byte[] buffer = new byte[1024];
                        int extractedFiles = 0;
                        
                        Log.d(TAG, "Starting ZIP extraction");
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.isDirectory()) continue;
                            
                            File outFile = new File(tempDir, entry.getName());
                            File parentDir = outFile.getParentFile();
                            if (parentDir != null && !parentDir.exists()) {
                                Log.d(TAG, "Creating parent directory: " + parentDir.getAbsolutePath());
                                if (!parentDir.mkdirs()) {
                                    Log.w(TAG, "Failed to create parent directory: " + parentDir.getAbsolutePath());
                                }
                            }
                            
                            Log.d(TAG, "Extracting: " + entry.getName() + " to " + outFile.getAbsolutePath());
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                int len;
                                while ((len = zis.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                }
                            }
                            
                            extractedFiles++;
                            Log.d(TAG, "Extracted: " + entry.getName() + " (" + entry.getSize() + " bytes)");
                        }
                        
                        Log.d(TAG, "Extraction completed. Total files: " + extractedFiles);
                        
                        if (extractedFiles == 0) {
                            String error = "No files found in the skin archive";
                            Log.e(TAG, error);
                            showError(error);
                            return;
                        }
                    } catch (IOException e) {
                        String error = "Error extracting skin: " + e.getMessage();
                        Log.e(TAG, error, e);
                        showError(error);
                        return;
                    }
                } catch (IOException e) {
                    String error = "Cannot open default.wsz from assets: " + e.getMessage();
                    Log.e(TAG, error, e);
                    showError(error);
                    return;
                }
                
                // Загружаем bitmap'ы
                loadBitmaps(tempDir);
                
                // Очищаем временную папку
                deleteRecursive(tempDir);
                
                Log.i(TAG, "Skin loaded successfully from assets");
                
            } catch (Exception e) {
                String error = "Unexpected error loading skin: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.e(TAG, error, e);
                showError(error);
            }
        }
        
        private void loadBitmaps(File skinDir) {
            String[] bitmapNames = {
                "main.bmp", "cbuttons.bmp", "titlebar.bmp", 
                "text.bmp", "numbers.bmp", "volume.bmp", 
                "balance.bmp", "monoster.bmp", "playpaus.bmp",
                "pledit.bmp", "eqmain.bmp", "eq_ex.bmp"
            };
            
            Log.d(TAG, "Loading bitmaps from: " + skinDir.getAbsolutePath());
            
            // Сначала проверим, какие файлы вообще есть в директории
            File[] allFiles = skinDir.listFiles();
            if (allFiles != null) {
                Log.d(TAG, "Files in skin directory:");
                for (File file : allFiles) {
                    Log.d(TAG, " - " + file.getName() + " (" + file.length() + " bytes)");
                }
            } else {
                Log.w(TAG, "No files found in skin directory");
            }
            
            int loadedCount = 0;
            for (String name : bitmapNames) {
                File bmpFile = new File(skinDir, name);
                Log.d(TAG, "Looking for bitmap: " + bmpFile.getAbsolutePath());
                
                if (bmpFile.exists()) {
                    try {
                        Log.d(TAG, "Found bitmap, decoding: " + name);
                        Bitmap bitmap = BitmapFactory.decodeFile(bmpFile.getAbsolutePath());
                        if (bitmap != null) {
                            skinBitmaps.put(name, bitmap);
                            loadedCount++;
                            Log.d(TAG, "Loaded bitmap: " + name + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                        } else {
                            Log.w(TAG, "Failed to decode bitmap: " + name);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not load bitmap: " + name, e);
                    }
                } else {
                    Log.d(TAG, "Bitmap file not found: " + name);
                }
            }
            
            // Загружаем region данные
            loadRegions(skinDir);
            
            Log.i(TAG, "Loaded " + loadedCount + " skin bitmaps");
            
            if (loadedCount == 0) {
                String error = "No bitmaps were loaded from the skin";
                Log.e(TAG, error);
                showError(error);
            }
            
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
            // Для простоты используем стандартные координаты
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
            mSkinLoadError = message;
            Log.e(TAG, message);
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            post(this::invalidate);
        }
        
        public void setMediaService(IMediaPlaybackService service) {
            mService = service;
            Log.d(TAG, "Media service set to SkinWindow");
            updateDisplay();
        }
        
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
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
                    // Рисуем заглушку если скин не загружен с информацией об ошибке
                    paint.setColor(0xFF333333);
                    canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                    paint.setColor(0xFFFFFFFF);
                    paint.setTextSize(24);
                    
                    String errorMessage = "No skin loaded";
                    if (mSkinLoadError != null) {
                        errorMessage += "\nError: " + mSkinLoadError;
                    }
                    
                    // Разбиваем сообщение на строки для отображения
                    String[] lines = errorMessage.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        canvas.drawText(lines[i], 50, 50 + i * 30, paint);
                    }
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
