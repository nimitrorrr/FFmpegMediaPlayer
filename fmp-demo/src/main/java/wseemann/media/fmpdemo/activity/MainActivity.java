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
import java.util.ArrayList;
import java.util.List;
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
            
            mSkinWindow = new SkinWindow(this);
            setContentView(mSkinWindow);
            
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
    
    public static class SkinWindow extends View {
        
        private Map<String, Bitmap> skinBitmaps;
        private Map<String, Rect> buttonRegions;
        private IMediaPlaybackService mService;
        private Paint paint;
        private String mSkinLoadError = null;
        
        private int mainWindowWidth = 275;
        private int mainWindowHeight = 116;
        
        private Map<String, SkinElement> skinElements = new HashMap<>();
        private Map<String, BitmapElement> xmlBitmaps = new HashMap<>();
        private Map<String, ElementStates> elementStates = new HashMap<>();
        
        private Bitmap mainOrig;
        private Bitmap mainScaled;
        private float mainScale = 1.0f;
        private int mainScaledH = 0;
        private int mainOrigW = mainWindowWidth;
        private int mainOrigH = mainWindowHeight;
        
        private Set<String> pressedButtons = new HashSet<>();
        private boolean equalizerEnabled = false;
        private boolean playlistEnabled = false;
        
        // Debug информация
        private List<String> debugMessages = new ArrayList<>();
        private boolean showDebugInfo = false;
        
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
        
        private static class ElementStates {
            BitmapElement normal;
            BitmapElement pressed;
            BitmapElement active;
            BitmapElement disabled;
            BitmapElement enabled;
        }
        
        private static class SkinElement {
            String id;
            int left;
            int top;
            int width;
            int height;
        }
        
        public SkinWindow(Context context) {
            super(context);
            init();
        }
        
        public SkinWindow(Context context, AttributeSet attrs) {
            super(context, attrs);
            init();
        }
        
        private void init() {
            paint = new Paint();
            paint.setAntiAlias(true);
            skinBitmaps = new HashMap<>();
            buttonRegions = new HashMap<>();
            debugMessages = new ArrayList<>();
            Log.d(TAG, "SkinWindow initialized");
        }
        
        private void addDebugMessage(String message) {
            debugMessages.add(message);
            Log.d(TAG, "DEBUG: " + message);
        }
        
        public void loadSkinFromAssets() {
            try {
                addDebugMessage("=== LOADING SKIN ===");
                
                File tempDir = new File(getContext().getCacheDir(), "skin_temp");
                if (tempDir.exists()) {
                    deleteRecursive(tempDir);
                }
                
                if (!tempDir.mkdirs()) {
                    addDebugMessage("ERROR: Cannot create temp directory");
                    showError("Cannot create temp directory");
                    return;
                }
                
                addDebugMessage("1. Extracting WSZ...");
                extractWSZ(tempDir);
                
                addDebugMessage("2. Loading bitmaps...");
                loadBitmaps(tempDir);
                
                addDebugMessage("3. Loading XML config...");
                loadXMLConfiguration();
                
                addDebugMessage("4. Loading JSON layout...");
                loadLayoutFromJSON(tempDir);
                
                addDebugMessage("5. Creating element states...");
                createElementStates();
                
                deleteRecursive(tempDir);
                
                addDebugMessage("=== LOADING COMPLETE ===");
                addDebugMessage("Skin bitmaps: " + skinBitmaps.size());
                addDebugMessage("XML bitmaps: " + xmlBitmaps.size());
                addDebugMessage("Skin elements: " + skinElements.size());
                addDebugMessage("Element states: " + elementStates.size());
                
                // Показываем debug информацию если что-то не загрузилось
                if (skinBitmaps.isEmpty() || xmlBitmaps.isEmpty() || skinElements.isEmpty()) {
                    showDebugInfo = true;
                }
                
            } catch (Exception e) {
                addDebugMessage("ERROR: " + e.getMessage());
                showError("Error loading skin: " + e.getMessage());
                showDebugInfo = true;
            }
        }
        
        private void extractWSZ(File tempDir) throws IOException {
            InputStream inputStream = getContext().getAssets().open("default.wsz");
            try (ZipInputStream zis = new ZipInputStream(inputStream)) {
                ZipEntry entry;
                byte[] buffer = new byte[1024];
                int extractedFiles = 0;
                
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
                    extractedFiles++;
                }
                
                addDebugMessage("Extracted " + extractedFiles + " files from WSZ");
            }
        }
        
        private void loadBitmaps(File skinDir) {
            Map<String, File> allFilesMap = new HashMap<>();
            File[] allFiles = skinDir.listFiles();
            if (allFiles != null) {
                for (File file : allFiles) {
                    allFilesMap.put(file.getName().toLowerCase(), file);
                }
                addDebugMessage("Found " + allFiles.length + " files in extracted WSZ");
            } else {
                addDebugMessage("ERROR: No files found in skin directory");
                return;
            }
        
            String[] bitmapNames = {
                "main.bmp", "cbuttons.bmp", "titlebar.bmp", 
                "text.bmp", "numbers.bmp", "volume.bmp", 
                "balance.bmp", "monoster.bmp", "playpaus.bmp",
                "pledit.bmp", "eqmain.bmp", "eq_ex.bmp", "shufrep.bmp",
                "posbar.bmp", "numfont.png", "nums_ex.bmp", "genex.bmp"
            };
            
            int loadedCount = 0;
            for (String name : bitmapNames) {
                File bmpFile = allFilesMap.get(name.toLowerCase());
                
                if (bmpFile != null && bmpFile.exists()) {
                    try {
                        Bitmap bitmap = BitmapFactory.decodeFile(bmpFile.getAbsolutePath());
                        if (bitmap != null) {
                            skinBitmaps.put(name, bitmap);
                            loadedCount++;
                            addDebugMessage("Loaded: " + name + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                        } else {
                            addDebugMessage("ERROR: Failed to decode " + name);
                        }
                    } catch (Exception e) {
                        addDebugMessage("ERROR loading " + name + ": " + e.getMessage());
                    }
                } else {
                    addDebugMessage("Missing: " + name);
                }
            }
        
            Bitmap mb = skinBitmaps.get("main.bmp");
            if (mb != null) {
                mainOrig = mb;
                mainOrigW = mainOrig.getWidth();
                mainOrigH = mainOrig.getHeight();
                addDebugMessage("Main bitmap: " + mainOrigW + "x" + mainOrigH);
            } else {
                addDebugMessage("ERROR: No main.bmp found");
            }
            
            post(this::invalidate);
        }
        
        private void loadXMLConfiguration() {
            String[] xmlFiles = {
                "wacup-classic-elements.xml",
                "classic-genex.xml", 
                "classic-colors.xml"
            };
            
            for (String xmlFile : xmlFiles) {
                try {
                    InputStream xmlStream = getContext().getAssets().open(xmlFile);
                    parseXMLFile(xmlStream, xmlFile);
                    xmlStream.close();
                    addDebugMessage("Loaded XML: " + xmlFile);
                } catch (IOException e) {
                    addDebugMessage("ERROR: Cannot load " + xmlFile + " - " + e.getMessage());
                }
            }
            
            addDebugMessage("Total XML bitmaps: " + xmlBitmaps.size());
            
            // Показываем первые 10 XML bitmap'ов для отладки
            int count = 0;
            for (String key : xmlBitmaps.keySet()) {
                if (count < 10) {
                    BitmapElement elem = xmlBitmaps.get(key);
                    addDebugMessage("XML: " + key + " -> " + elem.file + " (" + elem.x + "," + elem.y + "," + elem.w + "," + elem.h + ")");
                }
                count++;
            }
            if (xmlBitmaps.size() > 10) {
                addDebugMessage("... and " + (xmlBitmaps.size() - 10) + " more XML bitmaps");
            }
        }
        
        private void parseXMLFile(InputStream xmlStream, String fileName) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(xmlStream));
                StringBuilder xmlContent = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    xmlContent.append(line).append("\n");
                }
                reader.close();
                
                parseBitmapTags(xmlContent.toString());
                
            } catch (Exception e) {
                addDebugMessage("ERROR parsing " + fileName + ": " + e.getMessage());
            }
        }
        
        private void parseBitmapTags(String xmlContent) {
            String[] lines = xmlContent.split("\n");
            int parsedCount = 0;
            
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("<bitmap ") && line.contains("id=") && line.contains("file=")) {
                    try {
                        BitmapElement element = parseBitmapTag(line);
                        if (element != null) {
                            xmlBitmaps.put(element.id, element);
                            parsedCount++;
                        }
                    } catch (Exception e) {
                        addDebugMessage("ERROR parsing line: " + line + " - " + e.getMessage());
                    }
                }
            }
            
            addDebugMessage("Parsed " + parsedCount + " bitmap tags");
        }
        
        private BitmapElement parseBitmapTag(String line) {
            try {
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
            // Обновляем ID элементов согласно структуре XML
            createButtonStates("play-pause", "wacup.play", "wacup.play.pressed");
            createButtonStates("previous", "wacup.prev", "wacup.prev.pressed");
            createButtonStates("next", "wacup.next", "wacup.next.pressed");
            createButtonStates("stop", "wacup.stop", "wacup.stop.pressed");
            
            createTogglerStates("equalizer-button", "wacup.toggler.eq");
            createTogglerStates("playlist-button", "wacup.toggler.pl");
            
            createButtonStates("repeat", "wacup.repeat", "wacup.repeat.pressed", "wacup.repeat.active");
            createButtonStates("shuffle", "wacup.shuffle", "wacup.shuffle.pressed", "wacup.shuffle.active");
            
            addDebugMessage("Element states created with wacup.* identifiers");
        }
            
            addDebugMessage("Element states created: " + elementStates.size());
            
            // Debug info для каждого элемента
            for (String elementId : elementStates.keySet()) {
                ElementStates states = elementStates.get(elementId);
                String stateInfo = elementId + ": ";
                if (states.normal != null) stateInfo += "normal ";
                if (states.pressed != null) stateInfo += "pressed ";
                if (states.active != null) stateInfo += "active ";
                if (states.enabled != null) stateInfo += "enabled ";
                if (states.disabled != null) stateInfo += "disabled ";
                addDebugMessage(stateInfo);
            }
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
                addDebugMessage("Created states for " + elementId + " (normal:" + (states.normal != null) + " pressed:" + (states.pressed != null) + ")");
            } else {
                addDebugMessage("ERROR: No states found for " + elementId + " (looking for " + normalId + ", " + pressedId + ")");
            }
        }
        
        private void createTogglerStates(String elementId, String baseId) {
            ElementStates states = new ElementStates();
            states.disabled = xmlBitmaps.get(baseId + ".disabled");
            states.enabled = xmlBitmaps.get(baseId + ".enabled");
            states.pressed = xmlBitmaps.get(baseId + ".pressed");
            
            if (states.disabled != null || states.enabled != null || states.pressed != null) {
                elementStates.put(elementId, states);
                addDebugMessage("Created toggler states for " + elementId);
            } else {
                addDebugMessage("ERROR: No toggler states found for " + elementId + " (looking for " + baseId + ".*)");
            }
        }

        private void loadLayoutFromJSON(File skinDir) {
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
                addDebugMessage("Copied layout.json from assets");
            } catch (IOException e) {
                addDebugMessage("ERROR: Failed to copy layout.json - " + e.getMessage());
                return;
            }
            
            try {
                File layoutFile = new File(skinDir, "layout.json");
                if (!layoutFile.exists()) {
                    addDebugMessage("ERROR: layout.json not found");
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(layoutFile)));
                StringBuilder stringBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    stringBuilder.append(line);
                }
                reader.close();

                String jsonString = stringBuilder.toString();
                JSONObject jsonObject = new JSONObject(jsonString);
                JSONObject elements = jsonObject.getJSONObject("elements");
                Iterator<String> keys = elements.keys();

                int elementCount = 0;
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
                    elementCount++;

                    if ("main-window".equals(element.id)) {
                        mainWindowWidth = element.width;
                        mainWindowHeight = element.height;
                        addDebugMessage("Main window: " + mainWindowWidth + "x" + mainWindowHeight);
                    }

                    if (isClickableElement(element.id)) {
                        buttonRegions.put(element.id, 
                            new Rect(element.left, element.top, 
                                    element.left + element.width, 
                                    element.top + element.height));
                        addDebugMessage("Clickable: " + element.id + " at (" + element.left + "," + element.top + ")");
                    }
                }

                addDebugMessage("Loaded " + elementCount + " JSON elements");

            } catch (Exception e) {
                addDebugMessage("ERROR loading JSON: " + e.getMessage());
            }
        }

        private boolean isClickableElement(String elementId) {
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
            addDebugMessage("SHOW ERROR: " + message);
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            post(this::invalidate);
        }
        
        public void setMediaService(IMediaPlaybackService service) {
            mService = service;
            updateDisplay();
        }
        
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
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
                    addDebugMessage("ERROR: OOM scaling main bitmap");
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
                
                if (showDebugInfo || skinBitmaps.isEmpty()) {
                    drawDebugInfo(canvas, viewW, viewH);
                    return;
                }
                
                // Рисуем главное окно как фон
                if (mainScaled != null) {
                    canvas.drawBitmap(mainScaled, 0, 0, paint);
                } else if (mainOrig != null) {
                    Rect src = new Rect(0, 0, mainOrig.getWidth(), mainOrig.getHeight());
                    Rect dest = new Rect(0, 0, viewW, Math.max(1, Math.round((float)mainOrig.getHeight() * ((float)viewW / (float)mainOrig.getWidth()))));
                    canvas.drawBitmap(mainOrig, src, dest, paint);
                }
                
                // Рисуем элементы поверх фона
                if (!skinElements.isEmpty()) {
                    drawUsingJSONLayout(canvas, viewW, viewH);
                }
                
            } catch (Exception e) {
                addDebugMessage("ERROR in onDraw: " + e.getMessage());
                showDebugInfo = true;
                invalidate();
            }
        }
        
        private void drawDebugInfo(Canvas canvas, int viewW, int viewH) {
            paint.setColor(0xFF000000);
            canvas.drawRect(0, 0, viewW, viewH, paint);
            paint.setColor(0xFF00FF00);
            paint.setTextSize(12);
            
            int y = 20;
            int lineHeight = 15;
            
            // Показываем последние сообщения
            int startIndex = Math.max(0, debugMessages.size() - 40); // Показываем последние 40 сообщений
            for (int i = startIndex; i < debugMessages.size(); i++) {
                String message = debugMessages.get(i);
                
                // Разбиваем длинные строки
                if (message.length() > 50) {
                    String[] parts = message.split(" ");
                    StringBuilder currentLine = new StringBuilder();
                    
                    for (String part : parts) {
                        if (currentLine.length() + part.length() > 50) {
                            canvas.drawText(currentLine.toString(), 5, y, paint);
                            y += lineHeight;
                            currentLine = new StringBuilder(part + " ");
                        } else {
                            currentLine.append(part).append(" ");
                        }
                    }
                    
                    if (currentLine.length() > 0) {
                        canvas.drawText(currentLine.toString(), 5, y, paint);
                        y += lineHeight;
                    }
                } else {
                    canvas.drawText(message, 5, y, paint);
                    y += lineHeight;
                }
                
                if (y > viewH - 20) break; // Не выходим за границы экрана
            }
            
            // Добавляем инструкцию
            paint.setColor(0xFFFFFF00);
            canvas.drawText("Tap screen to toggle debug/normal view", 5, viewH - 5, paint);
        }
        
        private void drawUsingJSONLayout(Canvas canvas, int viewW, int viewH) {
            float scaleX = (float) viewW / mainWindowWidth;
            float scaleY = (float) viewH / mainWindowHeight;
            
            addDebugMessage("=== DRAWING ELEMENTS ===");
            addDebugMessage("Scale: " + scaleX + " x " + scaleY);
            
            int drawnElements = 0;
            for (SkinElement element : skinElements.values()) {
                if ("main-window".equals(element.id)) {
                    continue;
                }
                
                if (element.width <= 0 || element.height <= 0) {
                    continue;
                }
                
                int scaledLeft = (int) (element.left * scaleX);
                int scaledTop = (int) (element.top * scaleY);
                int scaledWidth = (int) (element.width * scaleX);
                int scaledHeight = (int) (element.height * scaleY);
                
                Bitmap elementBitmap = getBitmapForElement(element);
                if (elementBitmap != null) {
                    Rect destRect = new Rect(scaledLeft, scaledTop, 
                                            scaledLeft + scaledWidth, 
                                            scaledTop + scaledHeight);
                    canvas.drawBitmap(elementBitmap, null, destRect, paint);
                    drawnElements++;
                    addDebugMessage("DRAWN: " + element.id + " at (" + scaledLeft + "," + scaledTop + ")");
                } else {
                    addDebugMessage("NO BITMAP: " + element.id);
                }
            }
            
            addDebugMessage("Drew " + drawnElements + " elements");
        }
        
        private Bitmap getBitmapForElement(SkinElement element) {
            ElementStates states = elementStates.get(element.id);
            if (states == null) {
                addDebugMessage("No states for: " + element.id);
                BitmapElement xmlElement = xmlBitmaps.get(element.id);
                if (xmlElement != null) {
                    addDebugMessage("Found direct XML match for: " + element.id);
                    return extractBitmapRegion(xmlElement);
                } else {
                    addDebugMessage("No direct XML match for: " + element.id);
                }
                return null;
            }
            
            BitmapElement currentElement = getCurrentElementState(element.id, states);
            if (currentElement != null) {
                addDebugMessage("Using state bitmap for: " + element.id);
                return extractBitmapRegion(currentElement);
            } else {
                addDebugMessage("No state bitmap for: " + element.id);
            }
            
            return null;
        }
        
        private BitmapElement getCurrentElementState(String elementId, ElementStates states) {
            switch (elementId) {
                case "play-pause":
                    return isPressed(elementId) ? states.pressed : states.normal;
                    
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
                    return isPressed(elementId) ? states.pressed : states.normal;
            }
        }
        
        private Bitmap extractBitmapRegion(BitmapElement element) {
            if (element == null) {
                addDebugMessage("extractBitmapRegion: element is null");
                return null;
            }
            
            String fileName = element.file;
            if (fileName.startsWith("skin/")) {
                fileName = fileName.substring(5);
            }
            
            Bitmap sourceBitmap = skinBitmaps.get(fileName);
            if (sourceBitmap == null) {
                addDebugMessage("Source bitmap not found: " + fileName);
                addDebugMessage("Available bitmaps: " + skinBitmaps.keySet().toString());
                return null;
            }
            
            if (element.w > 0 && element.h > 0) {
                try {
                    int x = Math.max(0, Math.min(element.x, sourceBitmap.getWidth() - 1));
                    int y = Math.max(0, Math.min(element.y, sourceBitmap.getHeight() - 1));
                    int w = Math.min(element.w, sourceBitmap.getWidth() - x);
                    int h = Math.min(element.h, sourceBitmap.getHeight() - y);
                    
                    if (w > 0 && h > 0) {
                        addDebugMessage("Extracted region from " + fileName + ": (" + x + "," + y + "," + w + "," + h + ")");
                        return Bitmap.createBitmap(sourceBitmap, x, y, w, h);
                    } else {
                        addDebugMessage("Invalid region size: " + w + "x" + h);
                    }
                } catch (Exception e) {
                    addDebugMessage("Error extracting region: " + e.getMessage());
                }
            } else {
                addDebugMessage("No region specified, using full bitmap: " + fileName);
                return sourceBitmap;
            }
            
            return null;
        }
        
        private boolean isPressed(String elementId) {
            return pressedButtons.contains(elementId);
        }
        
        private boolean isRepeatActive() {
            try {
                if (mService != null) {
                    return mService.getRepeatMode() != MediaPlaybackService.REPEAT_NONE;
                }
            } catch (RemoteException e) {
                addDebugMessage("Error getting repeat mode: " + e.getMessage());
            }
            return false;
        }
        
        private boolean isShuffleActive() {
            try {
                if (mService != null) {
                    return mService.getShuffleMode() != MediaPlaybackService.SHUFFLE_NONE;
                }
            } catch (RemoteException e) {
                addDebugMessage("Error getting shuffle mode: " + e.getMessage());
            }
            return false;
        }
        
        private float[] mapTouchToOriginal(float touchX, float touchY) {
            int viewW = getWidth();
            int viewH = getHeight();
            if (viewW == 0 || viewH == 0) return null;
            
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
                    // Двойное касание переключает debug режим
                    showDebugInfo = !showDebugInfo;
                    invalidate();
                    
                    try {
                        handleTouchDown(event.getX(), event.getY());
                    } catch (Exception e) {
                        addDebugMessage("Error handling touch down: " + e.getMessage());
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    try {
                        handleTouchUp(event.getX(), event.getY());
                    } catch (Exception e) {
                        addDebugMessage("Error handling touch up: " + e.getMessage());
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }
        
        private void handleTouchDown(float x, float y) {
            if (showDebugInfo) return; // В debug режиме не обрабатываем кнопки
            
            float[] orig = mapTouchToOriginal(x, y);
            if (orig == null) return;
            float origX = orig[0];
            float origY = orig[1];
            
            for (Map.Entry<String, Rect> entry : buttonRegions.entrySet()) {
                String buttonName = entry.getKey();
                Rect region = entry.getValue();
                if (region.contains((int)origX, (int)origY)) {
                    pressedButtons.add(buttonName);
                    addDebugMessage("Button pressed: " + buttonName);
                    invalidate();
                    return;
                }
            }
        }
        
        private void handleTouchUp(float x, float y) {
            if (showDebugInfo) return; // В debug режиме не обрабатываем кнопки
            
            float[] orig = mapTouchToOriginal(x, y);
            if (orig == null) {
                if (!pressedButtons.isEmpty()) {
                    pressedButtons.clear();
                    invalidate();
                }
                return;
            }
            float origX = orig[0];
            float origY = orig[1];
            
            String clickedButton = null;
            for (String buttonName : pressedButtons) {
                Rect region = buttonRegions.get(buttonName);
                if (region != null && region.contains((int)origX, (int)origY)) {
                    clickedButton = buttonName;
                    break;
                }
            }
            
            pressedButtons.clear();
            
            if (clickedButton != null) {
                handleButtonClick(clickedButton);
            }
            
            invalidate();
        }
        
        private void handleButtonClick(String buttonName) {
            addDebugMessage("Button clicked: " + buttonName);
            
            if (mService == null) {
                addDebugMessage("Cannot handle button click - service is null");
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
            } catch (RemoteException e) {
                addDebugMessage("RemoteException in handleButtonClick: " + e.getMessage());
            }
        }
        
        private void toggleShuffle() {
            try {
                if (mService != null) {
                    int shuffle = mService.getShuffleMode();
                    if (shuffle == MediaPlaybackService.SHUFFLE_NONE) {
                        mService.setShuffleMode(MediaPlaybackService.SHUFFLE_NORMAL);
                        addDebugMessage("Shuffle ON");
                    } else {
                        mService.setShuffleMode(MediaPlaybackService.SHUFFLE_NONE);
                        addDebugMessage("Shuffle OFF");
                    }
                }
            } catch (RemoteException e) {
                addDebugMessage("Error toggling shuffle: " + e.getMessage());
            }
        }
        
        private void toggleRepeat() {
            try {
                if (mService != null) {
                    int mode = mService.getRepeatMode();
                    if (mode == MediaPlaybackService.REPEAT_NONE) {
                        mService.setRepeatMode(MediaPlaybackService.REPEAT_ALL);
                        addDebugMessage("Repeat ALL");
                    } else if (mode == MediaPlaybackService.REPEAT_ALL) {
                        mService.setRepeatMode(MediaPlaybackService.REPEAT_CURRENT);
                        addDebugMessage("Repeat ONE");
                    } else {
                        mService.setRepeatMode(MediaPlaybackService.REPEAT_NONE);
                        addDebugMessage("Repeat OFF");
                    }
                }
            } catch (RemoteException e) {
                addDebugMessage("Error toggling repeat: " + e.getMessage());
            }
        }
        
        private void toggleEqualizer() {
            equalizerEnabled = !equalizerEnabled;
            addDebugMessage("Equalizer toggled: " + equalizerEnabled);
        }
        
        private void togglePlaylist() {
            playlistEnabled = !playlistEnabled;
            addDebugMessage("Playlist toggled: " + playlistEnabled);
        }
        
        public void updateDisplay() {
            try {
                post(this::invalidate);
            } catch (Exception e) {
                addDebugMessage("Error updating display: " + e.getMessage());
            }
        }
        
        private void safeRecycleBitmap(Bitmap bmp) {
            try {
                if (bmp != null && !bmp.isRecycled()) {
                    bmp.recycle();
                }
            } catch (Exception e) {
                addDebugMessage("Error recycling bitmap: " + e.getMessage());
            }
        }
    }
}
