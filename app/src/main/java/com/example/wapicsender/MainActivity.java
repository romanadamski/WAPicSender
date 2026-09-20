package com.example.wapicsender;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button overlayButton;
    private Button accessibilityButton;
    private Button folderButton;

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        overlayButton = findViewById(R.id.overlayButton);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        folderButton = findViewById(R.id.folderButton);

        overlayButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        folderButton.setOnClickListener(v -> folderPicker.launch(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void onFolderPicked(Uri uri) {
        if (uri == null) return; // user cancelled the picker

        getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);

        getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .edit()
                .putString(Prefs.KEY_FOLDER_URI, uri.toString())
                .apply();

        Toast.makeText(this, "Folder saved", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void updateStatus() {
        boolean canOverlay = Settings.canDrawOverlays(this);
        boolean a11yEnabled = isAccessibilityServiceEnabled();
        boolean folderSet = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .contains(Prefs.KEY_FOLDER_URI);

        if (!canOverlay) {
            statusText.setText("Overlay permission missing - tap the button below");
        } else if (!a11yEnabled) {
            statusText.setText("Overlay granted. Now enable the accessibility service below.");
        } else if (!folderSet) {
            statusText.setText("Almost there - choose a folder with images below.");
        } else {
            statusText.setText("All set - the bubble will appear inside WhatsApp.");
        }

        styleButton(overlayButton, canOverlay,
                "Grant overlay permission", "\u2713 Overlay permission granted");
        styleButton(accessibilityButton, a11yEnabled,
                "Open accessibility settings", "\u2713 Accessibility service enabled");
        styleButton(folderButton, folderSet,
                "Choose image folder", "\u2713 Image folder chosen");
    }

    private void styleButton(Button button, boolean granted, String defaultText, String grantedText) {
        button.setText(granted ? grantedText : defaultText);
        button.setBackgroundTintList(ColorStateList.valueOf(
                granted ? Color.parseColor("#4CAF50") : Color.parseColor("#9E9E9E")));
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        String myService = getPackageName() + "/" + WaBubbleAccessibilityService.class.getName();
        for (String service : enabledServices.split(":")) {
            if (service.equalsIgnoreCase(myService)) return true;
        }
        return false;
    }
}
