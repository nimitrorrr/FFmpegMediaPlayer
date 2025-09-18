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

// Добавьте эти импорты в начало файла MainActivity.java

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import org.json.JSONObject;


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
        
        // ОРИГИНАЛЬНЫЕ РАЗМЕРЫ (из JSON)
        private int mainWindowWidth = 275;
        private int mainWindowHeight = 116;
        
        // Данные layout из JSON
        private Map<String, SkinElement> skinElements = new HashMap<>();
        
        // --- исходные (оригинальные) битмапы ---
        private Bitmap mainOrig;
        
        // --- отмасштабированные кеши ---
        private Bitmap mainScaled;
        private float mainScale = 1.0f;
        private int mainScaledH = 0;
        private int mainOrigW = mainWindowWidth;
        private int mainOrigH = mainWindowHeight;
        
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
                
                // Загружаем layout из JSON
                loadLayoutFromJSON(tempDir);
                
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
        
            Log.i(TAG, "Loaded " + loadedCount + " skin bitmaps");
            
            // Привяжем основные битмапы к полям
            Bitmap mb = skinBitmaps.get("main.bmp");
            if (mb != null) {
                mainOrig = mb;
                mainOrigW = mainOrig.getWidth();
                mainOrigH = mainOrig.getHeight();
            }
            
            if (loadedCount == 0) {
                String error = "No bitmaps were loaded from the skin";
                Log.e(TAG, error);
                showError(error);
            }
            
            // Перерисовываем после загрузки
            post(this::invalidate);
        }

        private void loadLayoutFromJSON(File skinDir) {
            
			// Предположим, что skinDir - это File, указывающий на целевую директорию
			try {
				InputStream layoutInputStream = getContext().getAssets().open("layout.json");
				FileOutputStream layoutOutputStream = new FileOutputStream(new File(skinDir, "layout.json"));
				byte[] buffer = new byte[1024];
				int length;
				while ((length = layoutInputStream.read(buffer)) > 0) {
					layoutOutputStream.write(buffer, 0, length);
				}
				layoutInputStream.close();
				layoutOutputStream.close();
				Log.d(TAG, "Copied layout.json from assets to " + skinDir.getAbsolutePath());
			} catch (IOException e) {
				Log.e(TAG, "Failed to copy layout.json from assets", e);
				return;
			}
			
			
			try {
                // Ищем файл layout.json
                File layoutFile = new File(skinDir, "layout.json");
                if (!layoutFile.exists()) {
                    Log.w(TAG, "layout.json not found in skin");
                    return;
                }

                // Читаем файл
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(layoutFile)));
                StringBuilder stringBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    stringBuilder.append(line);
                }
                reader.close();

                String jsonString = stringBuilder.toString();
                JSONObject jsonObject = new JSONObject(jsonString);

                // Парсим элементы
                JSONObject elements = jsonObject.getJSONObject("elements");
                Iterator<String> keys = elements.keys();

                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject elementJson = elements.getJSONObject(key);

                    SkinElement element = new SkinElement();
                    element.id = elementJson.getString("id");
                    element.left = elementJson.getInt("left");
                    element.top = elementJson.getInt("top");
                    element.width = elementJson.getInt("width");
                    element.height = elementJson.getInt("height");

                    skinElements.put(element.id, element);

                    // Если это главное окно, запоминаем его размеры
                    if ("main-window".equals(element.id)) {
                        mainWindowWidth = element.width;
                        mainWindowHeight = element.height;
                    }

                    // Добавляем region для кликабельных элементов
                    if (isClickableElement(element.id)) {
                        buttonRegions.put(element.id, 
                            new Rect(element.left, element.top, 
                                    element.left + element.width, 
                                    element.top + element.height));
                    }
                }

                Log.i(TAG, "Loaded " + skinElements.size() + " skin elements from JSON");

            } catch (Exception e) {
                Log.e(TAG, "Error loading layout from JSON", e);
            }
        }

        private boolean isClickableElement(String elementId) {
            // Определяем, какие элементы являются кликабельными
            return elementId.equals("play-pause") || 
                   elementId.equals("previous") ||
                   elementId.equals("next") ||
                   elementId.equals("stop") ||
                   elementId.equals("shuffle") ||
                   elementId.equals("repeat") ||
                   elementId.equals("volume") ||
                   elementId.equals("balance") ||
                   elementId.equals("equalizer-button") ||
                   elementId.equals("playlist-button");
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
            // Пересчитываем scaled main bitmap
            prepareMainScaled(w, h);
            invalidate();
        }
        
        private void prepareMainScaled(int viewWidth, int viewHeight) {
            safeRecycleBitmap(mainScaled);
            mainScaled = null;
            
            if (mainOrig != null && viewWidth > 0) {
                mainOrigW = mainOrig.getWidth();
                mainOrigH = mainOrig.getHeight();
                mainScale = (float)viewWidth / (float)mainWindowWidth;
                mainScaledH = Math.max(1, Math.round(mainWindowHeight * mainScale));
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
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            try {
                int viewW = getWidth();
                int viewH = getHeight();
                
                if (skinBitmaps.isEmpty()) {
                    drawFallback(canvas, viewW, viewH);
                    return;
                }
                
                // Если есть элементы из JSON, рисуем их
                if (!skinElements.isEmpty()) {
                    drawUsingJSONLayout(canvas, viewW, viewH);
                } else {
                    // Иначе рисуем main bitmap
                    if (mainScaled != null) {
                        canvas.drawBitmap(mainScaled, 0, 0, paint);
                    } else if (mainOrig != null) {
                        Rect src = new Rect(0, 0, mainOrig.getWidth(), mainOrig.getHeight());
                        Rect dest = new Rect(0, 0, viewW, Math.max(1, Math.round((float)mainOrig.getHeight() * ((float)viewW / (float)mainOrig.getWidth()))));
                        canvas.drawBitmap(mainOrig, src, dest, paint);
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error in onDraw", e);
            }
        }
        
        private void drawFallback(Canvas canvas, int viewW, int viewH) {
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
        }
        
        private void drawUsingJSONLayout(Canvas canvas, int viewW, int viewH) {
            // Масштабируем координаты согласно размеру view
            float scaleX = (float) viewW / mainWindowWidth;
            float scaleY = (float) viewH / mainWindowHeight;
            
            // Рисуем элементы
            for (SkinElement element : skinElements.values()) {
                // Пропускаем невидимые элементы
                if (element.width <= 0 || element.height <= 0) {
                    continue;
                }
                
                // Масштабируем координаты и размеры
                int scaledLeft = (int) (element.left * scaleX);
                int scaledTop = (int) (element.top * scaleY);
                int scaledWidth = (int) (element.width * scaleX);
                int scaledHeight = (int) (element.height * scaleY);
                
                // Определяем, какой битмап использовать для элемента
                Bitmap elementBitmap = getBitmapForElement(element);
                if (elementBitmap != null) {
                    Rect destRect = new Rect(scaledLeft, scaledTop, 
                                            scaledLeft + scaledWidth, 
                                            scaledTop + scaledHeight);
                    canvas.drawBitmap(elementBitmap, null, destRect, paint);
                }
            }
        }
        
        private Bitmap getBitmapForElement(SkinElement element) {
            // Здесь должна быть логика сопоставления элемента с битмапом
            // Для примера, используем main.bmp для всех элементов
            return mainOrig;
        }
        
        private float[] mapTouchToOriginal(float touchX, float touchY) {
            int viewW = getWidth();
            int viewH = getHeight();
            if (viewW == 0 || viewH == 0) return null;
            
            // Масштабируем обратно в координаты оригинального layout
            float scaleX = (float) viewW / mainWindowWidth;
            float scaleY = (float) viewH / mainWindowHeight;
            
            float origX = touchX / scaleX;
            float origY = touchY / scaleY;
            
            return new float[] { origX, origY };
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
            // Маппим касание в оригинальные координаты
            float[] orig = mapTouchToOriginal(x, y);
            if (orig == null) return;
            float origX = orig[0];
            float origY = orig[1];
            
            // Проверяем все кнопки из buttonRegions
            for (Map.Entry<String, Rect> entry : buttonRegions.entrySet()) {
                String buttonName = entry.getKey();
                Rect region = entry.getValue();
                if (region.contains((int)origX, (int)origY)) {
                    handleButtonClick(buttonName);
                    return;
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
                    case "play-pause":
                        if (mService.isPlaying()) {
                            mService.pause();
                        } else {
                            mService.play();
                        }
                        break;
                    case "previous":
                        mService.prev();
                        break;
                    case "next":
                        mService.next();
                        break;
                    case "stop":
                        mService.pause();
                        mService.seek(0);
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
        
        // Класс для хранения данных об элементе skin
        private static class SkinElement {
            String id;
            int left;
            int top;
            int width;
            int height;
        }
    }
}
