package com.example.wapicsender;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * Picks a random image from the folder the user chose via Storage Access
 * Framework. Uses DocumentFile instead of raw file paths, since a SAF tree
 * URI does not map to a normal filesystem path.
 *
 * Keeps track of the last HISTORY_SIZE picks (in memory - resets if the
 * accessibility service restarts) and excludes them from the candidate
 * pool, so the same image doesn't repeat too soon. If excluding history
 * would leave no candidates at all (a folder smaller than the history
 * size), falls back to the full list for that one pick instead of failing.
 */
final class ImagePicker {

    private static final Random random = new Random();

    private ImagePicker() {
    }

    static Uri pickRandomImage(Context context) {
        String saved = context.getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .getString(Prefs.KEY_FOLDER_URI, null);
        if (saved == null) return null;

        DocumentFile folder = DocumentFile.fromTreeUri(context, Uri.parse(saved));
        if (folder == null || !folder.isDirectory()) return null;

        // Load from prefs
        SharedPreferences prefs = context.getSharedPreferences("app_prefs", MODE_PRIVATE);
        var recentlyPicked = LoadRecentlyPicked(context, prefs);

        List<Uri> images = new ArrayList<>();
        for (DocumentFile file : folder.listFiles()) {
            String type = file.getType();
            String name = file.getName();
            if (type != null && type.startsWith("image/")
                    && name != null && !name.contains(".trashed")) {
                images.add(file.getUri());
            }
        }


        if (images.isEmpty()) return null;
        int count = images.size();

        List<Uri> candidates = new ArrayList<>(images);
        candidates.removeAll(recentlyPicked);
        if (candidates.isEmpty()) {
            candidates = images; // folder smaller than the history - allow a repeat just this once
        }

        Uri picked = candidates.get(random.nextInt(candidates.size()));

        // Save last picked, remove older than HISTORY_SIZE
        recentlyPicked.add(picked);
        var historySize = count / 3;
        if (recentlyPicked.size() > historySize) {
            recentlyPicked.removeFirst();
        }

        // Save to prefs
        JSONArray array = new JSONArray();
        for (Uri uri : recentlyPicked) {
            array.put(uri.toString());
        }
        prefs.edit()
                .putString("recentlyPicked", array.toString())
                .apply();

        return picked;
    }

    private static LinkedList<Uri> LoadRecentlyPicked(Context context, SharedPreferences prefs) {
        String json = prefs.getString("recentlyPicked", "[]");
        LinkedList<Uri> recentlyPicked = new LinkedList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                recentlyPicked.add(Uri.parse(array.getString(i)));
            }

            return recentlyPicked;
        }
        catch (Exception e) {
            Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
            return recentlyPicked;
        }
    }
}