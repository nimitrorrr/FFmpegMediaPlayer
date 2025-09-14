package wseemann.media.fmpdemo.helper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

public class FileExplorerHelper {

    public static final int REQUEST_CODE_OPEN_FILE = 1;

    public static void openFilePicker(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        // Устанавливаем фильтр для видео и аудио файлов
        String[] mimeTypes = {
            "video/*",
            "audio/*",
            "application/ogg",
            "application/x-extension-mp4"
        };
        
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        
        activity.startActivityForResult(intent, REQUEST_CODE_OPEN_FILE);
    }

    public static String getFileName(Activity activity, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (var cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
}
