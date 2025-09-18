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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
        private String mSkinLoadError = null;
        
        // ОРИГИНАЛЬНЫЕ РАЗМЕРЫ (из JSON)
        private int mainWindowWidth = 275;
        private int mainWindowHeight = 116;
        
        // Данные layout из JSON
        private Map<String, SkinElement> skinElements = new HashMap<>();
        
        // XML данные
        private Map<String, BitmapElement> xmlBitmaps = new HashMap<>();
        private Map<String, ElementStates> elementStates = new HashMap<>();
        
        // --- исходные (оригинальные) битмапы ---
        private Bitmap mainOrig;
        
        // --- отмасштабированные кеши ---
        private Bitmap mainScaled;
        private float mainScale = 1.0f;
        private int mainScaledH = 0;
        private int mainOrigW = mainWindowWidth;
        private int mainOrigH = mainWindowHeight;
        
        // Отслеживание нажатий
        private Set<String> pressedButtons = new HashSet<>();
        private boolean equalizerEnabled = false;
        private boolean playlistEnabled = false;
        
        // Класс для хранения информации о bitmap элементе из XML
        private static class BitmapElement {
            String id;
            String file;
            int x = 0;
            int y = 0;
            int w = 0;
            int h = 0;
            
            public BitmapElement(String id, String file) {
                this.id = id;
                this.file = file;
            }
        }
        
        // Класс для хранения состояний элемента (normal, pressed, etc)
        private static class ElementStates {
            BitmapElement normal;
            BitmapElement pressed;
            BitmapElement active;
            BitmapElement disabled;
            BitmapElement enabled;
        }
        
        // Класс для хранения данных об элементе skin
        private static class SkinElement {
            String id;
            int left;
            int top;
            int width;
            int height;
        }
        
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
                
                // Загружаем XML конфигурацию из assets
                loadXMLConfiguration();
                
                // Загружаем layout из JSON
                loadLayoutFromJSON(tempDir);
                
                // Создаем элементы с состояниями
                createElementStates();
                
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
                "pledit.bmp", "eqmain.bmp", "eq_ex.bmp", "shufrep.bmp",
                "posbar.bmp", "numfont.png", "nums_ex.bmp", "genex.bmp"
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
        
        private void loadXMLConfiguration() {
            Log.d(TAG, "Loading XML configuration from assets");
            
            String[] xmlFiles = {
                "wacup-classic-elements.xml",
                "classic-genex.xml", 
                "classic-colors.xml"
            };
            
            for (String xmlFile : xmlFiles) {
                try {
                    Log.d(TAG, "Loading XML file: " + xmlFile);
                    InputStream xmlStream = getContext().getAssets().open(xmlFile);
                    parseXMLFile(xmlStream, xmlFile);
                    xmlStream.close();
                } catch (IOException e) {
                    Log.w(TAG, "Could not load XML file: " + xmlFile + ", error: " + e.getMessage());
                }
            }
            
            Log.i(TAG, "Loaded " + xmlBitmaps.size() + " bitmap definitions from XML");
        }
        
        private void parseXMLFile(InputStream xmlStream, String fileName) {
            try {
                // Читаем весь файл в строку
                BufferedReader reader = new BufferedReader(new InputStreamReader(xmlStream));
                StringBuilder xmlContent = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    xmlContent.append(line).append("\n");
                }
                reader.close();
                
                // Простой парсинг bitmap тегов
                String xmlString = xmlContent.toString();
                parseBitmapTags(xmlString);
                
            } catch (Exception e) {
                Log.e(TAG, "Error parsing XML file " + fileName, e);
            }
        }
        
        private void parseBitmapTags(String xmlContent) {
            // Простой парсер для тегов <bitmap>
            String[] lines = xmlContent.split("\n");
            
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("<bitmap ") && line.contains("id=") && line.contains("file=")) {
                    try {
                        BitmapElement element = parseBitmapTag(line);
                        if (element != null) {
                            xmlBitmaps.put(element.id, element);
                            Log.d(TAG, "Parsed bitmap: " + element.id + " from " + element.file + 
                                  " (" + element.x + "," + element.y + "," + element.w + "," + element.h + ")");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error parsing bitmap line: " + line, e);
                    }
                }
            }
        }
        
        private BitmapElement parseBitmapTag(String line) {
            try {
                // Извлекаем атрибуты из строки вида: <bitmap id="play" file="skin/cbuttons.bmp" x="23" y="0" h="18" w="23"/>
                String id = extractAttribute(line, "id");
                String file = extractAttribute(line, "file");
                
                if (id == null || file == null) {
                    return null;
                }
                
                BitmapElement element = new BitmapElement(id, file);
                
                String x = extractAttribute(line, "x");
                String y = extractAttribute(line, "y");
                String w = extractAttribute(line, "w");
                String h = extractAttribute(line, "h");
                
                if (x != null) element.x = Integer.parseInt(x);
                if (y != null) element.y = Integer.parseInt(y);
                if (w != null) element.w = Integer.parseInt(w);
                if (h != null) element.h = Integer.parseInt(h);
                
                return element;
                
            } catch (Exception e) {
                Log.w(TAG, "Error parsing bitmap tag: " + line, e);
                return null;
            }
        }
        
        private String extractAttribute(String line, String attributeName) {
            String pattern = attributeName + "=\"";
            int start = line.indexOf(pattern);
            if (start == -1) return null;
            
            start += pattern.length();
            int end = line.indexOf("\"", start);
            if (end == -1) return null;
            
            return line.substring(start, end);
        }
        
        private void createElementStates() {
            Log.d(TAG, "Creating element states");
            
            // Создаем состояния для кнопок плеера
            createButtonStates("play-pause", "play", "playp");
            createButtonStates("previous", "prev", "prevp");
            createButtonStates("next", "next", "nextp");
            createButtonStates("stop", "stop", "stopp");
            
            // Создаем состояния для переключателей
            createTogglerStates("equalizer-button", "player.toggler.eq");
            createTogglerStates("playlist-button", "player.toggler.pl");
            
            // Создаем состояния для repeat/shuffle
            createButtonStates("repeat", "rep", "repp", "repa");
            createButtonStates("shuffle", "shuf", "shufp", "shufa");
            
            Log.i(TAG, "Created " + elementStates.size() + " element state definitions");
        }
        
        private void createButtonStates(String elementId, String normalId, String pressedId) {
            createButtonStates(elementId, normalId, pressedId, null);
        }
        
        private void createButtonStates(String elementId, String normalId, String pressedId, String activeId) {
            ElementStates states = new ElementStates();
            states.normal = xmlBitmaps.get(normalId);
            states.pressed = xmlBitmaps.get(pressedId);
            if (activeId != null) {
                states.active = xmlBitmaps.get(activeId);
            }
            
            if (states.normal != null || states.pressed != null) {
                elementStates.put(elementId, states);
                Log.d(TAG, "Created button states for: " + elementId);
            }
        }
        
        private void createTogglerStates(String elementId, String baseId) {
            ElementStates states = new ElementStates();
            states.disabled = xmlBitmaps.get(baseId + ".disabled");
            states.enabled = xmlBitmaps.get(baseId + ".enabled");
            states.pressed = xmlBitmaps.get(baseId + ".pressed");
            
            if (states.disabled != null || states.enabled != null || states.pressed != null) {
                elementStates.put(elementId, states);
                Log.d(TAG, "Created toggler states for: " + elementId);
            }
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
                
                // Рисуем главное окно как фон
                if (mainScaled != null) {
                    canvas.drawBitmap(mainOrig, src, dest, paint);
                }
                
                // Если есть элементы из JSON, рисуем их поверх фона
                if (!skinElements.isEmpty()) {
                    drawUsingJSONLayout(canvas, viewW, viewH);
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
            
            // Рисуем элементы, кроме главного окна
            for (SkinElement element : skinElements.values()) {
                // Пропускаем главное окно (уже нарисовано как фон)
                if ("main-window".equals(element.id)) {
                    continue;
                }
                
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
            // Получаем состояния элемента
            ElementStates states = elementStates.get(element.id);
            if (states == null) {
                // Если нет состояний, пытаемся найти прямое соответствие в XML
                BitmapElement xmlElement = xmlBitmaps.get(element.id);
                if (xmlElement != null) {
                    return extractBitmapRegion(xmlElement);
                }
                // Возвращаем null для элементов без битмапа
                return null;
            }
            
            // Определяем текущее состояние элемента
            BitmapElement currentElement = getCurrentElementState(element.id, states);
            if (currentElement != null) {
                return extractBitmapRegion(currentElement);
            }
            
            return null;
        }
        
        private BitmapElement getCurrentElementState(String elementId, ElementStates states) {
            // Определяем состояние на основе текущего состояния плеера/UI
            switch (elementId) {
                case "play-pause":
                    if (isPressed(elementId)) {
                        return states.pressed;
                    }
                    return states.normal;
                    
                case "equalizer-button":
                    if (isPressed(elementId)) {
                        return states.pressed;
                    }
                    return equalizerEnabled ? states.enabled : states.disabled;
                    
                case "playlist-button":
                    if (isPressed(elementId)) {
                        return states.pressed;
                    }
                    return playlistEnabled ? states.enabled : states.disabled;
                    
                case "repeat":
                    if (isPressed(elementId)) {
                        return states.pressed;
                    }
                    return isRepeatActive() ? states.active : states.normal;
                    
                case "shuffle":
                    if (isPressed(elementId)) {
                        return states.pressed;
                    }
                    return isShuffleActive() ? states.active : states.normal;
                    
                default:
                    if (isPressed(elementId)) {
                        return states.pressed;
                    }
                    return states.normal;
            }
        }
        
        private Bitmap extractBitmapRegion(BitmapElement element) {
            if (element == null) return null;
            
            // Получаем исходный bitmap по имени файла
            String fileName = element.file;
            if (fileName.startsWith("skin/")) {
                fileName = fileName.substring(5); // убираем "skin/"
            }
            
            Bitmap sourceBitmap = skinBitmaps.get(fileName);
            if (sourceBitmap == null) {
                Log.w(TAG, "Source bitmap not found: " + fileName);
                return null;
            }
            
            // Извлекаем область, если указаны размеры
            if (element.w > 0 && element.h > 0) {
                try {
                    int x = Math.max(0, Math.min(element.x, sourceBitmap.getWidth() - 1));
                    int y = Math.max(0, Math.min(element.y, sourceBitmap.getHeight() - 1));
                    int w = Math.min(element.w, sourceBitmap.getWidth() - x);
                    int h = Math.min(element.h, sourceBitmap.getHeight() - y);
                    
                    if (w > 0 && h > 0) {
                        return Bitmap.createBitmap(sourceBitmap, x, y, w, h);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error extracting bitmap region: " + element.id, e);
                }
            }
            
            return sourceBitmap;
        }
        
        // Методы для определения состояний
        private boolean isPressed(String elementId) {
            return pressedButtons.contains(elementId);
        }
        
        private boolean isRepeatActive() {
            try {
                if (mService != null) {
                    return mService.getRepeatMode() != MediaPlaybackService.REPEAT_NONE;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error getting repeat mode", e);
            }
            return false;
        }
        
        private boolean isShuffleActive() {
            try {
                if (mService != null) {
                    return mService.getShuffleMode() != MediaPlaybackService.SHUFFLE_NONE;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error getting shuffle mode", e);
            }
            return false;
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
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    try {
                        handleTouchDown(event.getX(), event.getY());
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling touch down", e);
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    try {
                        handleTouchUp(event.getX(), event.getY());
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling touch up", e);
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }
        
        private void handleTouchDown(float x, float y) {
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
                    // Отмечаем кнопку как нажатую
                    pressedButtons.add(buttonName);
                    invalidate(); // Перерисовываем для отображения pressed состояния
                    return;
                }
            }
        }
        
        private void handleTouchUp(float x, float y) {
            // Маппим касание в оригинальные координаты
            float[] orig = mapTouchToOriginal(x, y);
            if (orig == null) {
                // Снимаем все нажатия при выходе за границы
                if (!pressedButtons.isEmpty()) {
                    pressedButtons.clear();
                    invalidate();
                }
                return;
            }
            float origX = orig[0];
            float origY = orig[1];
            
            // Проверяем все нажатые кнопки
            String clickedButton = null;
            for (String buttonName : pressedButtons) {
                Rect region = buttonRegions.get(buttonName);
                if (region != null && region.contains((int)origX, (int)origY)) {
                    clickedButton = buttonName;
                    break;
                }
            }
            
            // Очищаем все нажатия
            pressedButtons.clear();
            
            // Если кнопка была отпущена внутри своей области, обрабатываем клик
            if (clickedButton != null) {
                handleButtonClick(clickedButton);
            }
            
            invalidate(); // Перерисовываем для снятия pressed состояния
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
                    case "equalizer-button":
                        toggleEqualizer();
                        break;
                    case "playlist-button":
                        togglePlaylist();
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
        
        private void toggleEqualizer() {
            equalizerEnabled = !equalizerEnabled;
            Log.d(TAG, "Equalizer toggled: " + equalizerEnabled);
            // TODO: Открыть окно эквалайзера
        }
        
        private void togglePlaylist() {
            playlistEnabled = !playlistEnabled;
            Log.d(TAG, "Playlist toggled: " + playlistEnabled);
            // TODO: Открыть окно плейлиста
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
}Bitmap(mainScaled, 0, 0, paint);
                } else if (mainOrig != null) {
                    Rect src = new Rect(0, 0, mainOrig.getWidth(), mainOrig.getHeight());
                    Rect dest = new Rect(0, 0, viewW, Math.max(1, Math.round((float)mainOrig.getHeight() * ((float)viewW / (float)mainOrig.getWidth()))));
                    canvas.draw
