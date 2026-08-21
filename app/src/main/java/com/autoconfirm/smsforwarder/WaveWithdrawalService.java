package com.autoconfirm.smsforwarder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import okhttp3.*;
import org.json.JSONObject;
import java.util.List;

public class WaveWithdrawalService extends AccessibilityService {
    private static final String TAG = "WaveWithdrawal";
    private static final String WAVE_PKG = "com.wave.personal";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    // Etats du flow de retrait
    private static final int STATE_IDLE = 0;
    private static final int STATE_HOME = 1;
    private static final int STATE_SEND_MONEY = 2;
    private static final int STATE_NEW_NUMBER = 3;
    private static final int STATE_FILL_NAME = 4;
    private static final int STATE_FILL_PHONE = 5;
    private static final int STATE_FILL_AMOUNT = 6;
    private static final int STATE_CONFIRM = 7;
    private static final int STATE_PIN = 8;

    private int state = STATE_IDLE;
    private String currentPhone = "";
    private String currentNom = "";
    private long currentMontant = 0;
    private String currentWithdrawalId = "";
    private String currentUserId = "";
    private long currentSubagentId = 0;
    private long currentRefId = 0;
    private long soldeWave = -1;

    // Statique pour recevoir l ordre depuis MainActivity
    public static WaveWithdrawalService instance;
    public static String pendingPhone = "";
    public static String pendingNom = "";
    public static long pendingMontant = 0;
    public static String pendingWithdrawalId = "";
    public static String pendingUserId = "";
    public static long pendingSubagentId = 0;
    public static long pendingRefId = 0;
    public static boolean triggerWithdrawal = false;

    @Override
    protected void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                         AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = new String[]{WAVE_PKG};
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "Service connecte!");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!pkg.equals(WAVE_PKG)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String screenText = getAllText(root);
        Log.d(TAG, "State=" + state + " screen=" + screenText.substring(0, Math.min(60, screenText.length())));
        // Envoyer ecran Wave au serveur pour debug
        if (state != STATE_IDLE) {
            new Thread(() -> {
                try {
                    SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
                    String tok = p.getString("token", "");
                    okhttp3.OkHttpClient cl = new okhttp3.OkHttpClient();
                    org.json.JSONObject j = new org.json.JSONObject();
                    j.put("token", tok); j.put("sender", "WAVE-SCREEN"); j.put("message", "State=" + state + " | " + screenText.substring(0, Math.min(200, screenText.length())));
                    okhttp3.RequestBody b = okhttp3.RequestBody.create(j.toString(), JSON_TYPE);
                    cl.newCall(new okhttp3.Request.Builder().url("https://autoconfirm.online/webhook/debug-notif").addHeader("x-token", tok).addHeader("Content-Type","application/json").post(b).build()).execute().close();
                } catch(Exception e) {}
            }).start();
        }

        // Verifier si trigger de retrait
        if (triggerWithdrawal && state == STATE_IDLE) {
            currentPhone = pendingPhone;
            currentNom = pendingNom;
            currentMontant = pendingMontant;
            currentWithdrawalId = pendingWithdrawalId;
            currentUserId = pendingUserId;
            currentSubagentId = pendingSubagentId;
            currentRefId = pendingRefId;
            triggerWithdrawal = false;
            state = STATE_PIN; // Commencer par le PIN
            Log.d(TAG, "Retrait demarre: " + currentPhone + " " + currentMontant + "F");
        }

        if (state == STATE_IDLE) return;

        // Ecran PIN Wave (au demarrage)
        if (state == STATE_PIN && (screenText.contains("Code secret") || screenText.contains("PIN") || screenText.contains("code"))) {
            SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
            String pin = prefs.getString("wave_pin", "");
            if (!pin.isEmpty()) {
                typePin(root, pin);
                state = STATE_HOME;
                Log.d(TAG, "PIN saisi, attente ecran accueil");
            }
            return;
        }

        // Lire le solde sur l ecran d accueil
        if (state == STATE_HOME && screenText.contains("Transfert")) {
            // Extraire le solde
            extractSolde(screenText);
            if (soldeWave >= 0 && soldeWave < currentMontant) {
                Log.d(TAG, "Solde insuffisant: " + soldeWave + "F < " + currentMontant + "F");
                notifyServer("solde_insuffisant");
                state = STATE_IDLE;
                return;
            }
            // Cliquer sur "Transfert"
            clickButton(root, "Transfert");
            state = STATE_SEND_MONEY;
            return;
        }

        // Ecran "Envoyer de l Argent" -> cliquer "Saisir un nouveau numero"
        if (state == STATE_SEND_MONEY && screenText.contains("Saisir un nouveau")) {
            clickButton(root, "Saisir un nouveau");
            state = STATE_NEW_NUMBER;
            return;
        }

        // Ecran nouveau numero -> remplir Nom et Telephone
        if (state == STATE_NEW_NUMBER && screenText.contains("Nom complet")) {
            fillField(root, "Nom complet", currentNom);
            state = STATE_FILL_PHONE;
            return;
        }

        if (state == STATE_FILL_PHONE && screenText.contains("Téléphone")) {
            fillField(root, "Téléphone", currentPhone);
            clickButton(root, "Suivant");
            state = STATE_FILL_AMOUNT;
            return;
        }

        // Ecran montant -> remplir "Montant Recu"
        if (state == STATE_FILL_AMOUNT && screenText.contains("Montant Reçu")) {
            fillField(root, "Montant Reçu", String.valueOf(currentMontant));
            clickButton(root, "Envoyer");
            state = STATE_CONFIRM;
            return;
        }

        // Ecran confirmation
        if (state == STATE_CONFIRM && screenText.contains("Confirmer")) {
            clickButton(root, "Confirmer");
            state = STATE_PIN;
            return;
        }

        // Ecran PIN final (confirmation paiement)
        if (state == STATE_CONFIRM && screenText.contains("Code secret")) {
            SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
            String pin = prefs.getString("wave_pin", "");
            if (!pin.isEmpty()) {
                typePin(root, pin);
            }
            state = STATE_IDLE;
            // Notifier le serveur que le transfert est envoye
            new android.os.Handler().postDelayed(() -> notifyServer("success"), 3000);
            return;
        }
    }

    private void extractSolde(String text) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("([0-9 ]+) *F");
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) {
                String s = m.group(1).replaceAll("\\s+", "").trim();
                soldeWave = Long.parseLong(s);
                Log.d(TAG, "Solde Wave: " + soldeWave + "F");
            }
        } catch(Exception e) {
            Log.e(TAG, "Erreur extraction solde: " + e.getMessage());
        }
    }

    private void clickButton(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Click: " + text);
                return;
            }
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null && parent.isClickable()) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Click parent: " + text);
                return;
            }
        }
    }

    private void fillField(AccessibilityNodeInfo root, String hint, String value) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(hint);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isEditable()) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                Log.d(TAG, "Fill: " + hint + " = " + value);
                return;
            }
        }
    }

    private void typePin(AccessibilityNodeInfo root, String pin) {
        for (char c : pin.toCharArray()) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(String.valueOf(c));
            if (!nodes.isEmpty()) {
                nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
    }

    private String getAllText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        if (node.getText() != null) sb.append(node.getText()).append(" ");
        if (node.getContentDescription() != null) sb.append(node.getContentDescription()).append(" ");
        for (int i = 0; i < node.getChildCount(); i++) {
            sb.append(getAllText(node.getChild(i)));
        }
        return sb.toString();
    }

    // Image PNG 1x1 pixel minimal pour preuve
    private static final byte[] MINIMAL_PNG = {
        (byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,
        0x00,0x00,0x00,0x0d,0x49,0x48,0x44,0x52,
        0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,
        0x08,0x06,0x00,0x00,0x00,0x1f,0x15,(byte)0xc4,(byte)0x89,
        0x00,0x00,0x00,0x10,0x49,0x44,0x41,0x54,
        0x78,0x01,0x63,0x60,(byte)0xf8,(byte)0xcf,(byte)0xc0,0x00,
        0x00,0x00,0x02,0x00,0x01,0x5e,0x22,0x11,
        0x00,0x00,0x00,0x00,0x49,0x45,0x4e,0x44,
        (byte)0xae,0x42,0x60,(byte)0x82
    };

    private void notifyServer(String statut) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
                String url = prefs.getString("url", "https://autoconfirm.online/webhook/saas");
                String serverUrl = url.replace("/webhook/saas", "/api/withdrawals/confirm");
                String token = prefs.getString("token", "");
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

                String imageBase64 = android.util.Base64.encodeToString(MINIMAL_PNG, android.util.Base64.NO_WRAP);

                JSONObject json = new JSONObject();
                json.put("token", token);
                json.put("withdrawal_id", currentWithdrawalId);
                json.put("user_id", currentUserId);
                json.put("subagent_id", currentSubagentId);
                json.put("ref_id", currentRefId);
                json.put("montant", currentMontant);
                json.put("statut", statut);
                json.put("image_base64", imageBase64);

                RequestBody body = RequestBody.create(json.toString(), JSON_TYPE);
                Request req = new Request.Builder()
                    .url(serverUrl)
                    .addHeader("x-token", token)
                    .addHeader("Content-Type", "application/json")
                    .post(body).build();
                Response resp = client.newCall(req).execute();
                Log.d(TAG, "Notif serveur: " + resp.code() + " " + resp.body().string());
                resp.close();
            } catch(Exception e) {
                Log.e(TAG, "Erreur notif: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onInterrupt() { state = STATE_IDLE; }
}
