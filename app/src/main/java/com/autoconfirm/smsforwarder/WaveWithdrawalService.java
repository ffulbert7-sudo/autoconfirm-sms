package com.autoconfirm.smsforwarder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import okhttp3.*;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;

public class WaveWithdrawalService extends AccessibilityService {
    private static final String TAG = "WaveWithdrawal";
    private static final String WAVE_PKG = "com.wave.personal";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final int STATE_IDLE = 0;
    private static final int STATE_HOME = 1;
    private static final int STATE_SEND_MONEY = 2;
    private static final int STATE_NEW_NUMBER = 3;
    private static final int STATE_FILL_NAME = 4;
    private static final int STATE_SELECT_COUNTRY = 9;
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

    private static final String[][] COUNTRY_CODES = {
        {"226", "Burkina Faso"},
        {"225", "Côte d'Ivoire"},
        {"223", "Mali"},
        {"227", "Niger"},
        {"221", "Sénégal"}
    };
    private String targetCountryName = "";
    private String localPhoneDigits = "";

    public static WaveWithdrawalService instance;
    public static String pendingPhone = "";
    public static String pendingNom = "";
    public static long pendingMontant = 0;
    public static String pendingWithdrawalId = "";
    public static String pendingUserId = "";
    public static long pendingSubagentId = 0;
    public static long pendingRefId = 0;
    public static boolean triggerWithdrawal = false;

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

        if (true) { // MODE TEST: log tous les ecrans
            final String st = "State=" + state + " | " + screenText.substring(0, Math.min(200, screenText.length()));
            new Thread(() -> {
                try {
                    SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
                    String tok = p.getString("token", "");
                    OkHttpClient cl = new OkHttpClient();
                    JSONObject j = new JSONObject();
                    j.put("token", tok); j.put("sender", "WAVE-SCREEN"); j.put("message", st);
                    RequestBody b = RequestBody.create(j.toString(), JSON_TYPE);
                    cl.newCall(new Request.Builder().url("https://autoconfirm.online/webhook/debug-notif").addHeader("x-token", tok).addHeader("Content-Type","application/json").post(b).build()).execute().close();
                } catch(Exception e) {}
            }).start();
        }

        if (triggerWithdrawal && state == STATE_IDLE) {
            currentPhone = pendingPhone;
            currentNom = pendingNom;
            currentMontant = pendingMontant;
            currentWithdrawalId = pendingWithdrawalId;
            currentUserId = pendingUserId;
            currentSubagentId = pendingSubagentId;
            currentRefId = pendingRefId;
            triggerWithdrawal = false;
            resolveCountryAndLocalDigits();
            state = STATE_PIN;
            Log.d(TAG, "Retrait demarre: " + currentPhone + " " + currentMontant + "F pays=" + targetCountryName + " local=" + localPhoneDigits);
        }

        if (state == STATE_IDLE) return;

        if (screenText.contains("code secret") || screenText.contains("Code secret")) {
            SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
            String pin = prefs.getString("wave_pin", "");
            if (!pin.isEmpty()) {
                typePin(root, pin);
                state = STATE_IDLE; // MODE TEST: arret apres PIN, manuel ensuite
                Log.d(TAG, "PIN saisi - ARRET MODE TEST");
            }
            return;
        }

        if (screenText.contains("Scanner") && screenText.contains("Transfert")) {
            if (state == STATE_PIN || state == STATE_HOME) {
                state = STATE_HOME;
                extractSolde(root);
                Log.d(TAG, "Solde: " + soldeWave + "F, besoin: " + currentMontant + "F");
                if (soldeWave > 0 && soldeWave < currentMontant) {
                    Log.d(TAG, "Solde insuffisant!");
                    notifyServer("solde_insuffisant");
                    state = STATE_IDLE;
                    return;
                }
                clickFirstButton(root, "Transfert");
                state = STATE_SEND_MONEY;
                Log.d(TAG, "Click Transfert -> SEND_MONEY");
            } else if (state == STATE_SEND_MONEY) {
                Log.d(TAG, "Retour accueil depuis SEND_MONEY - reclicker");
                clickFirstButton(root, "Transfert");
            }
            return;
        }

        if (state == STATE_SEND_MONEY && screenText.contains("Saisir un nouveau")) {
            clickButton(root, "Saisir un nouveau");
            state = STATE_NEW_NUMBER;
            return;
        }

        if (state == STATE_SELECT_COUNTRY && (screenText.contains("Sélectionnez un pays") || screenText.contains("Selectionnez un pays"))) {
            if (!targetCountryName.isEmpty() && clickButton(root, targetCountryName)) {
                Log.d(TAG, "Pays choisi: " + targetCountryName);
                state = STATE_FILL_PHONE;
            }
            return;
        }

        if (state != STATE_SELECT_COUNTRY && (screenText.contains("Sélectionnez un pays") || screenText.contains("Selectionnez un pays"))) {
            clickButton(root, "Fermer");
            Log.d(TAG, "Fermeture selecteur pays (inattendu)");
            return;
        }

        if (state == STATE_NEW_NUMBER && screenText.contains("Nom complet")) {
            fillField(root, "Nom complet", currentNom.isEmpty() ? "Client" : currentNom);
            state = STATE_FILL_NAME;
            return;
        }
        if (state == STATE_FILL_NAME && screenText.contains("Téléphone")) {
            if (!targetCountryName.isEmpty()) {
                clickCountrySelector(root);
                state = STATE_SELECT_COUNTRY;
            } else {
                state = STATE_FILL_PHONE;
            }
            return;
        }
        if (state == STATE_FILL_PHONE && screenText.contains("Téléphone")) {
            String toType = !localPhoneDigits.isEmpty() ? localPhoneDigits : currentPhone;
            fillField(root, "Téléphone", toType);
            try { Thread.sleep(500); } catch(Exception e) {}
            clickButton(root, "Suivant");
            state = STATE_FILL_AMOUNT;
            return;
        }

        if (state == STATE_FILL_AMOUNT && screenText.contains("Montant Reçu")) {
            fillField(root, "Montant Reçu", String.valueOf(currentMontant));
            try { Thread.sleep(500); } catch(Exception e) {}
            clickButton(root, "Envoyer");
            state = STATE_CONFIRM;
            return;
        }

        if (state == STATE_CONFIRM && screenText.contains("Confirmer la Transaction")) {
            clickButton(root, "Confirmer");
            return;
        }

        if (state == STATE_CONFIRM && screenText.contains("Scanner") && screenText.contains("Transfert")) {
            Log.d(TAG, "Transfert effectue avec succes (retour accueil)!");
            state = STATE_IDLE;
            notifyServer("success");
            return;
        }
    }

    private void resolveCountryAndLocalDigits() {
        targetCountryName = "";
        localPhoneDigits = "";
        String digits = currentPhone == null ? "" : currentPhone.replaceAll("[^0-9]", "");
        for (String[] entry : COUNTRY_CODES) {
            String code = entry[0];
            if (digits.startsWith(code)) {
                targetCountryName = entry[1];
                localPhoneDigits = digits.substring(code.length());
                return;
            }
        }
        localPhoneDigits = digits;
    }

    private void clickCountrySelector(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("+225");
        if (nodes.isEmpty()) nodes = root.findAccessibilityNodeInfosByText("+221");
        if (nodes.isEmpty()) nodes = root.findAccessibilityNodeInfosByText("+223");
        if (nodes.isEmpty()) nodes = root.findAccessibilityNodeInfosByText("+226");
        if (nodes.isEmpty()) nodes = root.findAccessibilityNodeInfosByText("+227");
        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo target = node;
            for (int i = 0; i < 3; i++) {
                if (target.isClickable()) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Clic selecteur pays ouvert");
                    return;
                }
                if (target.getParent() != null) target = target.getParent();
                else break;
            }
        }
    }

    private void extractSolde(AccessibilityNodeInfo root) {
        try {
            android.graphics.Point sz = new android.graphics.Point();
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getSize(sz);
            int maxY = (int) (sz.y * 0.18f);

            List<String> topTexts = new ArrayList<>();
            collectTextsInZone(root, maxY, topTexts);
            String zoneText = String.join(" ", topTexts);

            java.util.regex.Pattern p = java.util.regex.Pattern.compile("([0-9][0-9.,\\s]*[0-9])\\s*F");
            java.util.regex.Matcher m = p.matcher(zoneText);
            if (m.find()) {
                String s = m.group(1).replace(".", "").replace(",", "").replace(" ", "").trim();
                try {
                    soldeWave = Long.parseLong(s);
                    Log.d(TAG, "Solde Wave (zone haute): " + soldeWave + "F");
                    sendDebugSolde("Solde detecte=" + soldeWave + "F | zoneText=" + zoneText);
                } catch (NumberFormatException e2) {}
            } else {
                Log.d(TAG, "Solde non trouve dans la zone haute, texte capte: " + zoneText);
                sendDebugSolde("Solde NON TROUVE | zoneText=" + zoneText);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur extraction solde: " + e.getMessage());
        }
    }

    private void sendDebugSolde(String msg) {
        new Thread(() -> {
            try {
                SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
                String tok = p.getString("token", "");
                OkHttpClient cl = new OkHttpClient();
                JSONObject j = new JSONObject();
                j.put("token", tok); j.put("sender", "WAVE-SOLDE"); j.put("message", msg);
                RequestBody b = RequestBody.create(j.toString(), JSON_TYPE);
                cl.newCall(new Request.Builder().url("https://autoconfirm.online/webhook/debug-notif").addHeader("x-token", tok).addHeader("Content-Type","application/json").post(b).build()).execute().close();
            } catch(Exception e) {}
        }).start();
    }

    private void collectTextsInZone(AccessibilityNodeInfo node, int maxY, List<String> out) {
        if (node == null) return;
        android.graphics.Rect b = new android.graphics.Rect();
        node.getBoundsInScreen(b);
        if (b.top <= maxY && node.getText() != null) {
            out.add(node.getText().toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectTextsInZone(node.getChild(i), maxY, out);
        }
    }

    private void tapAt(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            android.accessibilityservice.GestureDescription.Builder gb = new android.accessibilityservice.GestureDescription.Builder();
            gb.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100));
            dispatchGesture(gb.build(), null, null);
        }
    }

    private void clickFirstButton(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo target = node;
            for (int i = 0; i < 3; i++) {
                if (target.isClickable()) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "ClickFirst: " + text);
                    return;
                }
                if (target.getParent() != null) target = target.getParent();
                else break;
            }
        }
    }

    private boolean clickButton(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Click: " + text);
                return true;
            }
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null && parent.isClickable()) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Click (parent): " + text);
                return true;
            }
        }
        return false;
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
        for (char digit : pin.toCharArray()) {
            clickExactDigit(root, String.valueOf(digit));
            try { Thread.sleep(300); } catch(Exception e) {}
        }
    }

    private void clickExactDigit(AccessibilityNodeInfo node, String digit) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && text.toString().trim().equals(digit)) {
            if (node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Click digit: " + digit);
                return;
            }
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null && parent.isClickable()) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            clickExactDigit(node.getChild(i), digit);
        }
    }

    private String getAllText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        if (node.getText() != null) sb.append(node.getText()).append(" ");
        if (node.getContentDescription() != null) sb.append(node.getContentDescription()).append(" ");
        for (int i = 0; i < node.getChildCount(); i++) sb.append(getAllText(node.getChild(i)));
        return sb.toString();
    }

    private void notifyServer(String statut) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
                String url = prefs.getString("url", "https://autoconfirm.online/webhook/saas")
                    .replace("/webhook/saas", "/api/withdrawals/confirm");
                String token = prefs.getString("token", "");
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
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
                RequestBody body = RequestBody.create(json.toString(), JSON_TYPE);
                Request req = new Request.Builder().url(url)
                    .addHeader("x-token", token)
                    .addHeader("Content-Type", "application/json")
                    .post(body).build();
                Response resp = client.newCall(req).execute();
                Log.d(TAG, "Notif serveur: " + resp.code() + " statut=" + statut);
                resp.close();
            } catch(Exception e) {
                Log.e(TAG, "Erreur notif: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onInterrupt() { state = STATE_IDLE; }
}
