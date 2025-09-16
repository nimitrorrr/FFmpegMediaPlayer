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
        private int windowType = 0;
        private String mSkinLoadError = null;
        
        // ОРИГИНАЛЬНЫЕ РАЗМЕРЫ (по умолчанию для упрощения, но будут браться из битмапов)
        private static final int MAIN_WIDTH = 275;
        private static final int MAIN_HEIGHT = 116;
        
        // --- исходные (оригинальные) битмапы ---
        private Bitmap mainOrig;
        private Bitmap eqOrig;
        private Bitmap playlistOrig;
        
        // --- отмасштабированные кеши ---
        private Bitmap mainScaled;
        private float mainScale = 1.0f;
        private int mainScaledH = 0;
        private int mainOrigW = MAIN_WIDTH;
        private int mainOrigH = MAIN_HEIGHT;
        
        private Bitmap eqScaled;
        private float eqScale = 1.0f;
        private int eqScaledH = 0;
        private int eqOrigW = MAIN_WIDTH;
        private int eqOrigH = MAIN_HEIGHT;
        
        // playlist pieces (orig + scaled pieces)
        private int playlistOrigW = 0;
        private int playlistOrigH = 0;
        private int playlistMiddleStart = 0;
        private int playlistMiddleEnd = 0; // exclusive
        
        private Bitmap playlistTopScaled;
        private Bitmap playlistMiddleScaled;
        private Bitmap playlistBottomScaled;
        private int playlistTopScaledH = 0;
        private int playlistMiddleScaledH = 0;
        private int playlistBottomScaledH = 0;
        private float playlistScale = 1.0f;
        private boolean playlistReady = false;
        
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
        // Создаем map всех файлов в директории без учета регистра
        Map<String, File> allFilesMap = new HashMap<>();
        File[] allFiles = skinDir.listFiles();
        if (allFiles != null) {
            for (File file : allFiles) {
                allFilesMap.put(file.getName().toLowerCase(), file);
            }
        }
    
        // Список битмапов, которые мы ищем (в нижнем регистре для сравнения)
        String[] bitmapNames = {
            "main.bmp", "cbuttons.bmp", "titlebar.bmp", 
            "text.bmp", "numbers.bmp", "volume.bmp", 
            "balance.bmp", "monoster.bmp", "playpaus.bmp",
            "pledit.bmp", "eqmain.bmp", "eq_ex.bmp"
        };
        
        Log.d(TAG, "Loading bitmaps from: " + skinDir.getAbsolutePath());
        
        int loadedCount = 0;
        for (String name : bitmapNames) {
            // Ищем файл без учета регистра
            File bmpFile = allFilesMap.get(name.toLowerCase());
            
            if (bmpFile != null && bmpFile.exists()) {
                try {
                    Log.d(TAG, "Found bitmap, decoding: " + bmpFile.getName());
                    Bitmap bitmap = BitmapFactory.decodeFile(bmpFile.getAbsolutePath());
                    if (bitmap != null) {
                        // Сохраняем с оригинальным именем (в нижнем регистре для consistency)
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
    
    // Привяжем основные битмапы к полям
    Bitmap mb = skinBitmaps.get("main.bmp");
    if (mb != null) {
        mainOrig = mb;
        mainOrigW = mainOrig.getWidth();
        mainOrigH = mainOrig.getHeight();
    }
    Bitmap eqb = skinBitmaps.get("eqmain.bmp");
    if (eqb != null) {
        eqOrig = eqb;
        eqOrigW = eqOrig.getWidth();
        eqOrigH = eqOrig.getHeight();
    }
    Bitmap plb = skinBitmaps.get("pledit.bmp");
    if (plb != null) {
        playlistOrig = plb;
        playlistOrigW = playlistOrig.getWidth();
        playlistOrigH = playlistOrig.getHeight();
        detectPlaylistSlices(playlistOrig);
    }
    
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
    // Создаем map всех файлов в директории без учета регистра
    Map<String, File> allFilesMap = new HashMap<>();
    File[] allFiles = skinDir.listFiles();
    if (allFiles != null) {
        for (File file : allFiles) {
            allFilesMap.put(file.getName().toLowerCase(), file);
        }
    }

        // Ищем файлы с region данными без учета регистра
        String[] regionFileNames = {
            "region.txt",
            "main.rgn",
            "pledit.rgn", 
            "eqmain.rgn"
        };
        
        for (String fileName : regionFileNames) {
            File regionFile = allFilesMap.get(fileName.toLowerCase());
            if (regionFile != null && regionFile.exists()) {
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
            Log.d(TAG, "Size changed: " + w + "x" + h);
            // Пересчитываем все подготовленные элементы (main, eq, playlist)
            prepareAllScaled(w, h);
            invalidate();
        }
        
        /**
         * Подготовить все отмасштабированные битмапы для текущей ширины/высоты view.
         * Стек: main сверху, затем eq, затем playlist (playlist заполняет оставшуюся высоту до низа).
         */
        private void prepareAllScaled(int viewWidth, int viewHeight) {
            // освобождаем старые scaled битмапы (без удаления оригиналов)
            safeRecycleBitmap(mainScaled); mainScaled = null;
            safeRecycleBitmap(eqScaled); eqScaled = null;
            safeRecycleBitmap(playlistTopScaled); playlistTopScaled = null;
            safeRecycleBitmap(playlistMiddleScaled); playlistMiddleScaled = null;
            safeRecycleBitmap(playlistBottomScaled); playlistBottomScaled = null;
            playlistReady = false;
            
            // MAIN: scale по ширине (uniform)
            if (mainOrig != null && viewWidth > 0) {
                mainOrigW = mainOrig.getWidth();
                mainOrigH = mainOrig.getHeight();
                mainScale = (float)viewWidth / (float)mainOrigW;
                mainScaledH = Math.max(1, Math.round(mainOrigH * mainScale));
                try {
                    mainScaled = Bitmap.createScaledBitmap(mainOrig, viewWidth, mainScaledH, true);
                } catch (OutOfMemoryError oom) {
                    Log.e(TAG, "OOM scaling main bitmap", oom);
                    mainScaled = null;
                }
            } else {
                mainScale = 1.0f;
                mainScaledH = 0;
            }
            
            // EQ: аналогично main (scale по ширине, aspect ratio сохраняем)
            if (eqOrig != null && viewWidth > 0) {
                eqOrigW = eqOrig.getWidth();
                eqOrigH = eqOrig.getHeight();
                eqScale = (float)viewWidth / (float)eqOrigW;
                eqScaledH = Math.max(1, Math.round(eqOrigH * eqScale));
                try {
                    eqScaled = Bitmap.createScaledBitmap(eqOrig, viewWidth, eqScaledH, true);
                } catch (OutOfMemoryError oom) {
                    Log.e(TAG, "OOM scaling eq bitmap", oom);
                    eqScaled = null;
                }
            } else {
                eqScale = 1.0f;
                eqScaledH = 0;
            }
            
            // Playlist: оставшееся пространство отрисуем плиткой middle
            int playlistTopY = mainScaledH + eqScaledH;
            int playlistTargetH = Math.max(0, viewHeight - playlistTopY);
            if (playlistOrig != null && viewWidth > 0 && playlistTargetH > 0) {
                prepareScaledPlaylist(viewWidth, playlistTargetH);
            } else {
                // нет плейлиста или места — пометим не готовым
                playlistReady = false;
            }
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            try {
                int viewW = getWidth();
                int viewH = getHeight();
                
                if ((mainOrig == null && eqOrig == null && playlistOrig == null) || skinBitmaps.isEmpty()) {
                    // Рисуем заглушку если скин не загружен с информацией об ошибке
                    paint.setColor(0xFF333333);
                    canvas.drawRect(0, 0, viewW, viewH, paint);
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
                
                int y = 0;
                
                // DRAW MAIN (сверху)
                if (mainScaled != null) {
                    canvas.drawBitmap(mainScaled, 0, y, paint);
                    y += mainScaledH;
                } else if (mainOrig != null) {
                    // fallback: draw scaled via src/dest without caching
                    Rect src = new Rect(0, 0, mainOrig.getWidth(), mainOrig.getHeight());
                    Rect dest = new Rect(0, 0, viewW, Math.max(1, Math.round((float)mainOrig.getHeight() * ((float)viewW / (float)mainOrig.getWidth()))));
                    canvas.drawBitmap(mainOrig, src, dest, paint);
                    y = dest.bottom;
                }
                
                // DRAW EQ (под main)
                if (eqScaled != null) {
                    canvas.drawBitmap(eqScaled, 0, y, paint);
                    y += eqScaledH;
                } else if (eqOrig != null) {
                    Rect src = new Rect(0, 0, eqOrig.getWidth(), eqOrig.getHeight());
                    Rect dest = new Rect(0, y, viewW, y + Math.max(1, Math.round((float)eqOrig.getHeight() * ((float)viewW / (float)eqOrig.getWidth()))));
                    canvas.drawBitmap(eqOrig, src, dest, paint);
                    y = dest.bottom;
                }
                
                // DRAW PLAYLIST (плотно от y до низа)
                if (playlistOrig != null) {
                    drawPlaylist(canvas, y);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error in onDraw", e);
            }
        }
        
        // ----------------- Playlist: детект / подготовка / рисование / маппинг -----------------
        
        // --- автодетект границ top/middle/bottom ---
        // простая эвристика: ищем самую длинную вертикальную зону с низкой вариативностью (однородный фон)
        private void detectPlaylistSlices(Bitmap bmp) {
            try {
                int w = bmp.getWidth();
                int h = bmp.getHeight();
                if (h <= 3) {
                    playlistMiddleStart = 1;
                    playlistMiddleEnd = Math.max(2, h - 1);
                    return;
                }
                int[] uniqueCounts = new int[h];
                for (int y = 0; y < h; y++) {
                    java.util.HashSet<Integer> set = new java.util.HashSet<>();
                    for (int x = 0; x < w; x++) {
                        set.add(bmp.getPixel(x, y));
                        if (set.size() > Math.max(4, w / 8)) { // ранний выход
                            break;
                        }
                    }
                    uniqueCounts[y] = set.size();
                }
                long sum = 0;
                for (int v : uniqueCounts) sum += v;
                double avg = (h > 0) ? (double) sum / h : 0.0;
                double threshold = Math.max(1.0, avg * 0.8);
                
                int bestStart = -1, bestEnd = -1, bestLen = 0;
                int curStart = -1;
                for (int y = 0; y < h; y++) {
                    if (uniqueCounts[y] <= threshold) {
                        if (curStart == -1) curStart = y;
                    } else {
                        if (curStart != -1) {
                            int len = y - curStart;
                            if (len > bestLen) {
                                bestLen = len;
                                bestStart = curStart;
                                bestEnd = y;
                            }
                            curStart = -1;
                        }
                    }
                }
                if (curStart != -1) {
                    int len = h - curStart;
                    if (len > bestLen) {
                        bestLen = len;
                        bestStart = curStart;
                        bestEnd = h;
                    }
                }
                
                if (bestLen >= 4 || (h > 0 && bestLen >= h * 0.05)) {
                    playlistMiddleStart = Math.max(1, bestStart);
                    playlistMiddleEnd = Math.min(h - 1, bestEnd);
                } else {
                    int top = Math.max(1, h / 10);
                    int bottom = Math.max(1, h / 10);
                    playlistMiddleStart = top;
                    playlistMiddleEnd = h - bottom;
                }
                if (playlistMiddleStart >= playlistMiddleEnd) {
                    playlistMiddleStart = Math.max(1, h / 10);
                    playlistMiddleEnd = Math.max(playlistMiddleStart + 1, h - Math.max(1, h / 10));
                }
                Log.d(TAG, "Playlist slices detected: [" + playlistMiddleStart + "," + playlistMiddleEnd + ") of " + h);
            } catch (Exception e) {
                Log.w(TAG, "Error detecting playlist slices", e);
                // fallback
                int h = (bmp != null) ? bmp.getHeight() : 0;
                playlistMiddleStart = Math.max(1, h / 10);
                playlistMiddleEnd = Math.max(playlistMiddleStart + 1, h - Math.max(1, h / 10));
            }
        }
        
        // Подготовить отмасштабированные куски playlist (top/middle/bottom) для заданной ширины
        private void prepareScaledPlaylist(int viewWidth, int targetHeight) {
            if (playlistOrig == null || viewWidth <= 0) {
                playlistReady = false;
                return;
            }
            playlistOrigW = playlistOrig.getWidth();
            playlistOrigH = playlistOrig.getHeight();
            playlistScale = (float) viewWidth / (float) playlistOrigW;
            
            int topH = Math.max(0, playlistMiddleStart);
            int middleH = Math.max(1, playlistMiddleEnd - playlistMiddleStart);
            int bottomH = Math.max(0, playlistOrigH - playlistMiddleEnd);
            
            // освобождаем предыдущие
            safeRecycleBitmap(playlistTopScaled); playlistTopScaled = null;
            safeRecycleBitmap(playlistMiddleScaled); playlistMiddleScaled = null;
            safeRecycleBitmap(playlistBottomScaled); playlistBottomScaled = null;
            playlistTopScaledH = playlistMiddleScaledH = playlistBottomScaledH = 0;
            
            try {
                if (topH > 0) {
                    Bitmap topSub = Bitmap.createBitmap(playlistOrig, 0, 0, playlistOrigW, topH);
                    playlistTopScaledH = Math.max(1, Math.round(topH * playlistScale));
                    playlistTopScaled = Bitmap.createScaledBitmap(topSub, viewWidth, playlistTopScaledH, true);
                    topSub.recycle();
                }
                if (middleH > 0) {
                    Bitmap midSub = Bitmap.createBitmap(playlistOrig, 0, playlistMiddleStart, playlistOrigW, middleH);
                    playlistMiddleScaledH = Math.max(1, Math.round(middleH * playlistScale));
                    playlistMiddleScaled = Bitmap.createScaledBitmap(midSub, viewWidth, playlistMiddleScaledH, true);
                    midSub.recycle();
                }
                if (bottomH > 0) {
                    Bitmap botSub = Bitmap.createBitmap(playlistOrig, 0, playlistMiddleEnd, playlistOrigW, bottomH);
                    playlistBottomScaledH = Math.max(1, Math.round(bottomH * playlistScale));
                    playlistBottomScaled = Bitmap.createScaledBitmap(botSub, viewWidth, playlistBottomScaledH, true);
                    botSub.recycle();
                }
            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "OOM while scaling playlist pieces", oom);
                safeRecycleBitmap(playlistTopScaled); playlistTopScaled = null;
                safeRecycleBitmap(playlistMiddleScaled); playlistMiddleScaled = null;
                safeRecycleBitmap(playlistBottomScaled); playlistBottomScaled = null;
                playlistReady = false;
                return;
            }
            playlistReady = true;
            Log.d(TAG, "Prepared playlist scaled pieces: topH=" + playlistTopScaledH + " midH=" + playlistMiddleScaledH + " botH=" + playlistBottomScaledH);
        }
        
        // Рисуем плейлист, начиная с screen Y = topY (обычно mainHeight + eqHeight)
        private void drawPlaylist(Canvas canvas, int topY) {
            if (playlistOrig == null) return;
            int viewW = getWidth();
            int viewH = getHeight();
            if (viewW == 0 || viewH == 0) return;
            int targetHeight = viewH - topY;
            if (targetHeight <= 0) return;
            
            // если не подготовлено или ширина изменилась — подготовим
            if (!playlistReady || Math.abs((float)viewW - playlistScale * playlistOrigW) > 0.5f) {
                prepareScaledPlaylist(viewW, targetHeight);
            }
            
            int y = topY;
            
            // Если ничего не готово — fallback: растянуть оригинал по ширине и отрисовать от topY до низа
            if ((playlistTopScaled == null && playlistMiddleScaled == null && playlistBottomScaled == null) || playlistScale <= 0) {
                Rect src = new Rect(0, 0, playlistOrigW, playlistOrigH);
                Rect dest = new Rect(0, topY, viewW, viewH);
                canvas.drawBitmap(playlistOrig, src, dest, paint);
                return;
            }
            
            int totalScaled = playlistTopScaledH + playlistMiddleScaledH + playlistBottomScaledH;
            
            // Если middle отсутствует или суммарная высота >= targetHeight — просто рисуем куски подряд (обрезая bottom)
            if (playlistMiddleScaled == null || totalScaled >= targetHeight) {
                if (playlistTopScaled != null) {
                    canvas.drawBitmap(playlistTopScaled, 0, y, paint);
                    y += playlistTopScaledH;
                }
                if (playlistMiddleScaled != null) {
                    canvas.drawBitmap(playlistMiddleScaled, 0, y, paint);
                    y += playlistMiddleScaledH;
                }
                if (playlistBottomScaled != null) {
                    int remain = targetHeight - (y - topY);
                    int drawH = Math.min(remain, playlistBottomScaledH);
                    if (drawH > 0) {
                        Rect src = new Rect(0, 0, playlistBottomScaled.getWidth(), drawH);
                        Rect dest = new Rect(0, y, viewW, y + drawH);
                        canvas.drawBitmap(playlistBottomScaled, src, dest, paint);
                    }
                }
                return;
            }
            
            // Normal: draw top, tile middle vertically to fill, then bottom
            if (playlistTopScaled != null) {
                canvas.drawBitmap(playlistTopScaled, 0, y, paint);
                y += playlistTopScaledH;
            }
            
            // сколько места должна занять tiled middle
            int remaining = targetHeight - playlistTopScaledH - playlistBottomScaledH;
            if (remaining < 0) remaining = 0;
            int midH = Math.max(1, playlistMiddleScaledH);
            while (remaining > 0) {
                int drawH = Math.min(midH, remaining);
                if (drawH == midH) {
                    canvas.drawBitmap(playlistMiddleScaled, 0, y, paint);
                } else {
                    Rect src = new Rect(0, 0, playlistMiddleScaled.getWidth(), drawH);
                    Rect dest = new Rect(0, y, viewW, y + drawH);
                    canvas.drawBitmap(playlistMiddleScaled, src, dest, paint);
                }
                y += drawH;
                remaining -= drawH;
            }
            
            if (playlistBottomScaled != null) {
                canvas.drawBitmap(playlistBottomScaled, 0, y, paint);
            }
        }
        
        // Маппинг касания в координаты оригинального скина
        // Возвращает float[]{origX, origY} в координатах оригинального файла или null, если вне областей.
        private float[] mapTouchToOriginal(float touchX, float touchY) {
            int viewW = getWidth();
            int viewH = getHeight();
            if (viewW == 0 || viewH == 0) return null;
            
            // main area
            int mainTop = 0;
            int mainBottom = mainScaledH;
            if (touchY >= mainTop && touchY < mainBottom) {
                float origX = touchX / Math.max(0.0001f, mainScale);
                float origY = (touchY - mainTop) / Math.max(0.0001f, mainScale);
                return new float[] { origX, origY };
            }
            
            // eq area
            int eqTop = mainScaledH;
            int eqBottom = mainScaledH + eqScaledH;
            if (touchY >= eqTop && touchY < eqBottom) {
                float origX = touchX / Math.max(0.0001f, eqScale);
                float origY = (touchY - eqTop) / Math.max(0.0001f, eqScale);
                return new float[] { origX, origY };
            }
            
            // playlist area
            int playlistTop = mainScaledH + eqScaledH;
            if (touchY >= playlistTop && playlistOrig != null) {
                // ensure playlist prepared
                if (!playlistReady) prepareScaledPlaylist(viewW, Math.max(0, viewH - playlistTop));
                // relative y inside playlist
                float relY = touchY - playlistTop;
                // heights
                int topH = playlistTopScaledH;
                int midH = Math.max(1, playlistMiddleScaledH);
                int botH = playlistBottomScaledH;
                
                // total tiled middle height we actually used:
                int tiledMiddleTotal = Math.max(0, viewH - playlistTop - topH - botH);
                if (relY < topH) {
                    float origX = touchX / Math.max(0.0001f, playlistScale);
                    float origY = relY / Math.max(0.0001f, playlistScale);
                    return new float[] { origX, origY };
                } else if (relY < topH + tiledMiddleTotal) {
                    int inside = (int)(relY - topH);
                    int yInOne = inside % midH;
                    float origX = touchX / Math.max(0.0001f, playlistScale);
                    float origY = playlistMiddleStart + (yInOne / Math.max(0.0001f, playlistScale));
                    return new float[] { origX, origY };
                } else {
                    int insideBottom = (int)(relY - topH - tiledMiddleTotal);
                    float origX = touchX / Math.max(0.0001f, playlistScale);
                    float origY = playlistMiddleEnd + (insideBottom / Math.max(0.0001f, playlistScale));
                    if (origY > playlistOrigH - 1) origY = playlistOrigH - 1;
                    return new float[] { origX, origY };
                }
            }
            
            // вне интересующих областей
            return null;
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
            // Маппим касание в оригинальные координаты (в зависимости от области)
            float[] orig = mapTouchToOriginal(x, y);
            if (orig == null) return;
            float origX = orig[0];
            float origY = orig[1];
            
            // Проверяем все кнопки из buttonRegions — предполагаем, что они описаны в координатах "main" (или общего скина).
            for (Map.Entry<String, Rect> entry : buttonRegions.entrySet()) {
                String buttonName = entry.getKey();
                Rect region = entry.getValue();
                if (region.contains((int)origX, (int)origY)) {
                    handleButtonClick(buttonName);
                    return;
                }
            }
            
            // Другие возможные интерактивы (например, клики в плейлисте) можно обрабатывать здесь:
            // Если касание в пределах плейлиста — можно преобразовать в индекс строки и т.д. (не реализовано).
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
        
        // Утилита: безопасно утилизировать bitmap
        private void safeRecycleBitmap(Bitmap bmp) {
            try {
                if (bmp != null && !bmp.isRecycled()) {
                    bmp.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error recycling bitmap", e);
            }
        }
    }
}
