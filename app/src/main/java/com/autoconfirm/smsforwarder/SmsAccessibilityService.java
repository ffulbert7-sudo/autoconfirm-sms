package com.autoconfirm.smsforwarder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import okhttp3.*;
import org.json.JSONObject;
import java.util.List;

public class SmsAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutoConfirmAccess";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String REDDY_URL = "https://autoconfirm.online/webhook/reddy";
    private static final String SAAS_URL_DEFAULT = "https://autoconfirm.online/webhook/saas";
    private static final String WAVE_PACKAGE = "com.wave.personal";
    private static long lastDumpSentAt = 0;
    private static final long DUMP_THROTTLE_MS = 3000;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // ── Debug: dump de l'arbre Wave (phase de mapping des ecrans) ──
        if (WAVE_PACKAGE.equals(pkg) &&
            (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
             event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            long now = System.currentTimeMillis();
            if (now - lastDumpSentAt > DUMP_THROTTLE_MS) {
                lastDumpSentAt = now;
                StringBuilder dumpSb = new StringBuilder();
                dumpNodeTree(getRootInActiveWindow(), 0, dumpSb);
                String dumpText = dumpSb.toString();
                SharedPreferences prefsForDump = getSharedPreferences("config", MODE_PRIVATE);
                String tokenForDump = prefsForDump.getString("token", "");
                String urlForDump = prefsForDump.getString("url", SAAS_URL_DEFAULT);
                send(urlForDump, tokenForDump, "WaveDump", dumpText);
            }
        }

        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;

        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null) sb.append(t).append(" ");
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String token = prefs.getString("token", "");
        String webhookUrl = prefs.getString("url", SAAS_URL_DEFAULT);

        Log.d(TAG, "pkg=" + pkg + " text=" + text.substring(0, Math.min(80, text.length())));

        // ── Reddy ────────────────────────────────────────────────
        if (pkg.contains("insystem") && text.contains("APPROVED") && !text.contains("Withdrawal")) {
            Log.d(TAG, ">>> REDDY APPROVED - envoi vers " + REDDY_URL);
            send(REDDY_URL, token, "Reddy", text);
            return;
        }

        // ── Wave ─────────────────────────────────────────────────
        if (pkg.contains("wave") || text.contains("avez recu") || text.contains("avez reçu") ||
            text.contains("Vous avez") || text.contains("a paye") || text.contains("Paiement A DISTANCE")) {
            Log.d(TAG, ">>> WAVE - envoi vers " + webhookUrl);
            send(webhookUrl, token, "Wave", text);
        }
    }

    private void dumpNodeTree(AccessibilityNodeInfo node, int depth, StringBuilder out) {
        if (node == null) return;
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");
        String txt = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
        out.append(indent).append("[").append(node.getClassName()).append("] id=").append(id)
           .append(" text=\"").append(txt).append("\" desc=\"").append(desc)
           .append("\" clickable=").append(node.isClickable()).append("\n");
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpNodeTree(node.getChild(i), depth + 1, out);
        }
    }

    @Override
    public void onInterrupt() {}

    private android.os.Handler dismissHandler = new android.os.Handler();
    private Runnable dismissRunnable = new Runnable() {
        @Override
        public void run() {
            SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
            if (prefs.getBoolean("auto_dismiss_notif", false)) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                Log.d(TAG, "Notifications balayees");
            }
            dismissHandler.postDelayed(this, 60 * 1000); // toutes les 60 secondes
        }
    };

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
        Log.d(TAG, "Service connecte!");
        dismissHandler.postDelayed(dismissRunnable, 60 * 1000);
    }

    private void send(String url, String token, String sender, String message) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                JSONObject json = new JSONObject();
                json.put("token", token);
                json.put("sender", sender);
                json.put("message", message);
                RequestBody body = RequestBody.create(json.toString(), JSON_TYPE);
                Request req = new Request.Builder()
                    .url(url).addHeader("x-token", token)
                    .addHeader("Content-Type", "application/json")
                    .post(body).build();
                Response resp = client.newCall(req).execute();
                Log.d(TAG, "Reponse " + url + ": " + resp.code());
                resp.close();
            } catch (Exception e) {
                Log.e(TAG, "Erreur: " + e.getMessage());
            }
        }).start();
    }
}
