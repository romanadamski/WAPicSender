package com.example.wapicsender;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;

import java.util.List;

/**
 * Core service: pastes a random image (from a user-chosen folder) into the
 * currently focused field and auto-clicks the resulting "Send" button.
 * Reused by two triggers - a floating bubble shown only while inside a
 * WhatsApp conversation, and a Quick Settings tile (RandomImageTileService)
 * that calls sendRandomImage() directly on the running instance.
 *
 * Bubble visibility uses a self-starting/self-stopping poll: a window-state
 * event mentioning WhatsApp starts polling, and polling stops itself after a
 * few consecutive checks confirm WhatsApp is gone - so there is no
 * continuous cost while WhatsApp isn't in use at all.
 *
 * While the notification shade / Quick Settings panel itself is the active
 * window, bubble visibility is left untouched rather than hidden: on this
 * device, hiding the bubble removes an OS-generated "overlay running"
 * notification, and that content change makes the shade snap back closed
 * immediately - so we simply don't react while the shade is what's on top.
 */
public class WaBubbleAccessibilityService extends AccessibilityService {

    private static final String TAG = "WaBubbleA11y";
    private static final String WHATSAPP_PACKAGE = "com.whatsapp";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final long POLL_INTERVAL_MS = 1000;
    private static final int MAX_CONSECUTIVE_MISSES = 5;
    private static final int MAX_SEND_CLICK_ATTEMPTS = 6;
    private static final long SEND_CLICK_RETRY_DELAY_MS = 250;
    private static final int MAX_PASTE_FIELD_ATTEMPTS = 5;
    private static final long PASTE_FIELD_RETRY_DELAY_MS = 200;

    private static WaBubbleAccessibilityService instance;

    private WindowManager windowManager;
    private View bubbleView;
    private boolean awaitingSendScreen = false;
    private boolean pollingActive = false;
    private int consecutiveMisses = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable foregroundChecker = new Runnable() {
        @Override
        public void run() {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            CharSequence activePkg = activeRoot != null ? activeRoot.getPackageName() : null;

            if (activePkg != null && SYSTEM_UI_PACKAGE.contentEquals(activePkg)) {
                // shade/panel is on top - leave bubble state exactly as is
                handler.postDelayed(this, POLL_INTERVAL_MS);
                return;
            }

            boolean onScreen = isWhatsAppOnScreen();

            if (!onScreen) {
                consecutiveMisses++;
                hideBubble();
            } else {
                consecutiveMisses = 0;
                // only show inside an actual conversation, not the chat list -
                // see findMessageComposeField() for how those are told apart
                boolean insideConversation = findMessageComposeField(activeRoot) != null;
                if (insideConversation) {
                    showBubble();
                } else {
                    hideBubble();
                }
            }

            if (consecutiveMisses >= MAX_CONSECUTIVE_MISSES) {
                pollingActive = false;
                return; // do not reschedule - loop stays stopped until woken up again
            }

            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Log.d(TAG, "onServiceConnected");
    }

    static WaBubbleAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null || getPackageName().contentEquals(pkg)) return;

        boolean mentionsWhatsApp = WHATSAPP_PACKAGE.contentEquals(pkg);

        if (mentionsWhatsApp && awaitingSendScreen) {
            awaitingSendScreen = false;
            tryClickSend(1);
        }

        if (mentionsWhatsApp) {
            startPollingIfNeeded();
        }
    }

    private void startPollingIfNeeded() {
        if (pollingActive) return;
        pollingActive = true;
        consecutiveMisses = 0;
        handler.post(foregroundChecker);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(foregroundChecker);
        hideBubble();
        instance = null;
        super.onDestroy();
    }

    private boolean isWhatsAppOnScreen() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;

        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;

            CharSequence pkg = root.getPackageName();
            if (pkg != null && WHATSAPP_PACKAGE.contentEquals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWhatsAppTheActiveWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        return pkg != null && WHATSAPP_PACKAGE.contentEquals(pkg);
    }

    private void showBubble() {
        if (bubbleView != null) return; // already showing

        ImageView view = new ImageView(this);
        view.setImageResource(android.R.drawable.ic_menu_gallery); // swap for a custom icon later if you want
        view.setOnClickListener(v -> sendRandomImage());
        bubbleView = view;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                150, 150,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // must not steal focus from WhatsApp's own field
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 24;
        params.y = 250;

        windowManager.addView(bubbleView, params);
    }

    private void hideBubble() {
        if (bubbleView == null) return;
        windowManager.removeView(bubbleView);
        bubbleView = null;
    }

    /**
     * Picks a random image, puts it on the clipboard, and starts trying to
     * paste it into the currently focused field. Called both from the
     * bubble's own tap and from the Quick Settings tile.
     */
    void sendRandomImage() {
        Uri imageUri = ImagePicker.pickRandomImage(this);
        if (imageUri == null) {
            Log.d(TAG, "no image available in the chosen folder");
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "image", imageUri));

        findFieldAndPaste(1);
    }

    private void findFieldAndPaste(int attempt) {
        AccessibilityNodeInfo field = findComposeField();

        if (field != null) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean pasted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            if (!pasted) {
                Log.d(TAG, "paste action was rejected by the field");
            }
            if (pasted) {
                awaitingSendScreen = true;
            }
            return;
        }

        if (attempt == 1 && !isWhatsAppTheActiveWindow()) {
            // something else (e.g. the Quick Settings panel we were just tapped from)
            // is covering WhatsApp - collapse it and try again
            performGlobalAction(GLOBAL_ACTION_BACK);
        }

        if (attempt >= MAX_PASTE_FIELD_ATTEMPTS) {
            Log.d(TAG, "no compose field found - probably not inside a chat");
            return;
        }

        handler.postDelayed(() -> findFieldAndPaste(attempt + 1), PASTE_FIELD_RETRY_DELAY_MS);
    }

    private AccessibilityNodeInfo findComposeField() {
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && isLikelyMessageField(focused)) return focused;

        return findMessageComposeField(getRootInActiveWindow());
    }

    /**
     * Distinguishes WhatsApp's actual message field from other editable
     * fields (like the chat list's search/Meta AI box once tapped) using two
     * traits observed to differ between them on this device/version:
     * the message field has a non-null stateDescription (WhatsApp seems to
     * label it specifically for accessibility, unlike the search field) and
     * no maxTextLength cap (-1), while the search field is capped at 1024.
     * Deliberately not matching on visible text/tooltip, since those are
     * localized strings that change with the phone's language or an update.
     */
    private AccessibilityNodeInfo findMessageComposeField(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && isLikelyMessageField(node)) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findMessageComposeField(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private boolean isLikelyMessageField(AccessibilityNodeInfo node) {
        return node.getStateDescription() != null && node.getMaxTextLength() == -1;
    }

    private void tryClickSend(int attempt) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo sendButton = root != null ? findByDescriptionOrText(root, "send", "wyślij") : null;

        if (sendButton != null && sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return;
        }

        if (attempt >= MAX_SEND_CLICK_ATTEMPTS) {
            Log.d(TAG, "could not find or click the Send button after " + attempt + " attempts");
            return;
        }

        handler.postDelayed(() -> tryClickSend(attempt + 1), SEND_CLICK_RETRY_DELAY_MS);
    }

    private AccessibilityNodeInfo findByDescriptionOrText(AccessibilityNodeInfo node, String... keywords) {
        if (node == null) return null;

        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        String text = node.getText() != null ? node.getText().toString() : "";
        String combined = (desc + " " + text).toLowerCase();

        if (node.isClickable()) {
            for (String keyword : keywords) {
                if (combined.contains(keyword)) return node;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findByDescriptionOrText(node.getChild(i), keywords);
            if (result != null) return result;
        }
        return null;
    }
}