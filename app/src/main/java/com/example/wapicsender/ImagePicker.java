package com.example.wapicsender;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Picks a random image from the folder the user chose via Storage Access
 * Framework. Uses DocumentFile instead of raw file paths, since a SAF tree
 * URI does not map to a normal filesystem path.
 */
final class ImagePicker {

    private ImagePicker() {
    }

    static Uri pickRandomImage(Context context) {
        String saved = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getString(Prefs.KEY_FOLDER_URI, null);
        if (saved == null) return null;

        DocumentFile folder = DocumentFile.fromTreeUri(context, Uri.parse(saved));
        if (folder == null || !folder.isDirectory()) return null;

        List<Uri> images = new ArrayList<>();
        for (DocumentFile file : folder.listFiles()) {
            String type = file.getType();
            if (type != null && type.startsWith("image/")) {
                images.add(file.getUri());
            }
        }

        if (images.isEmpty()) return null;
        return images.get(new Random().nextInt(images.size()));
    }
}
