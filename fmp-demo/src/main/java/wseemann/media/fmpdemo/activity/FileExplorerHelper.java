package wseemann.media.fmpdemo.helper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

public class FileExplorerHelper {

    public static final int REQUEST_CODE_OPEN_DIRECTORY = 1;

    public static void openDirectoryPicker(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        activity.startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
    }

    public static List<DocumentFile> listFiles(Activity activity, Uri treeUri) {
        List<DocumentFile> files = new ArrayList<>();
        DocumentFile directory = DocumentFile.fromTreeUri(activity, treeUri);
        
        if (directory != null && directory.exists()) {
            for (DocumentFile file : directory.listFiles()) {
                if (file.isFile() && isMediaFile(file.getName())) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private static boolean isMediaFile(String fileName) {
        if (fileName == null) return false;
        
        String[] mediaExtensions = {".mp3", ".mp4", ".avi", ".mkv", ".flac", ".wav", ".aac", ".mov"};
        for (String ext : mediaExtensions) {
            if (fileName.toLowerCase().endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
