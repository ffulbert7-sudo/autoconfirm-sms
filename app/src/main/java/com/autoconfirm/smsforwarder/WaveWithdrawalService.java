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

public class WaveWithdrawalService extends AccessibilityService {
    private static final String TAG = "WaveWithdrawal";
    private static final String WAVE_PKG = "com.wave.personal";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

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

    public static WaveWithdrawalService instance;
    public static String pendingPhone = "";
    public static String pendingNom = "";
    public static long pendingMontant = 0;
    public static String pendingWithdrawalId = "";
    public static String pendingUserId = "";
    public static long pendingSubagentId = 0;
    public static long pendingRefId = 0;
    public static boolean triggerWithdrawal = false;

    // Image PNG 1x1 pixel minimal
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

        // Debug vers serveur
        if (state != STATE_IDLE) {
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

        // Trigger retrait
        if (triggerWithdrawal && state == STATE_IDLE) {
            currentPhone = pendingPhone;
            currentNom = pendingNom;
            currentMontant = pendingMontant;
            currentWithdrawalId = pendingWithdrawalId;
            currentUserId = pendingUserId;
            currentSubagentId = pendingSubagentId;
            currentRefId = pendingRefId;
            triggerWithdrawal = false;
            state = STATE_PIN;
            Log.d(TAG, "Retrait demarre: " + currentPhone + " " + currentMontant + "F");
        }

        if (state == STATE_IDLE) return;

        // PIN detecte depuis n'importe quel etat
        if (screenText.contains("code secret") || screenText.contains("Code secret")) {
            SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
            String pin = prefs.getString("wave_pin", "");
            if (!pin.isEmpty()) {
                typePin(root, pin);
                state = STATE_HOME;
                Log.d(TAG, "PIN saisi");
            }
            return;
        }

        // Ecran accueil Wave
        if (screenText.contains("Scanner") && screenText.contains("Transfert")) {
            if (state == STATE_PIN || state == STATE_HOME) {
                state = STATE_HOME;
                extractSolde(screenText);
                Log.d(TAG, "Solde: " + soldeWave + "F, besoin: " + currentMontant + "F");
                if (soldeWave > 0 && soldeWave < currentMontant) {
                    Log.d(TAG, "Solde insuffisant!");
                    notifyServer("solde_insuffisant");
                    state = STATE_IDLE;
                    return;
                }
                // Cliquer le premier bouton Transfert (la grille, pas l historique)
                clickFirstButton(root, "Transfert");
                state = STATE_SEND_MONEY;
                Log.d(TAG, "Click Transfert -> SEND_MONEY");
            } else if (state == STATE_SEND_MONEY) {
                // On est revenu a l accueil depuis SEND_MONEY - reclicker
                Log.d(TAG, "Retour accueil depuis SEND_MONEY - reclicker");
                clickFirstButton(root, "Transfert");
            }
            return;
        }

        // Saisir nouveau numero
        if (state == STATE_SEND_MONEY && screenText.contains("Saisir un nouveau")) {
            clickButton(root, "Saisir un nouveau");
            state = STATE_NEW_NUMBER;
            return;
        }

        // Remplir nom
        if (state == STATE_NEW_NUMBER && screenText.contains("Nom complet")) {
            fillField(root, "Nom complet", currentNom.isEmpty() ? "Client" : currentNom);
            state = STATE_FILL_PHONE;
            return;
        }

        // Remplir telephone
        if (state == STATE_FILL_PHONE && screenText.contains("Téléphone")) {
            fillField(root, "Téléphone", currentPhone);
            try { Thread.sleep(500); } catch(Exception e) {}
            clickButton(root, "Suivant");
            state = STATE_FILL_AMOUNT;
            return;
        }

        // Remplir montant
        if (state == STATE_FILL_AMOUNT && screenText.contains("Montant Reçu")) {
            fillField(root, "Montant Reçu", String.valueOf(currentMontant));
            try { Thread.sleep(500); } catch(Exception e) {}
            clickButton(root, "Envoyer");
            state = STATE_CONFIRM;
            return;
        }

        // Confirmation
        if (state == STATE_CONFIRM && screenText.contains("Confirmer")) {
            clickButton(root, "Confirmer");
            return;
        }

        // Transfert effectue avec succes
        if (state == STATE_CONFIRM && (screenText.contains("Effectué") || screenText.contains("Effectue"))) {
            Log.d(TAG, "Transfert effectue avec succes!");
            state = STATE_IDLE;
            notifyServer("success");
            return;
        }

        // Fermer selecteur de pays si ouvert
        if (screenText.contains("Sélectionnez un pays") || screenText.contains("Selectionnez un pays")) {
            clickButton(root, "Fermer");
            Log.d(TAG, "Fermeture selecteur pays");
            return;
        }

        // PIN final apres confirmation
        if (state == STATE_CONFIRM && (screenText.contains("code secret") || screenText.contains("Code secret"))) {
            SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
            String pin = prefs.getString("wave_pin", "");
            if (!pin.isEmpty()) {
                typePin(root, pin);
                state = STATE_IDLE;
                new android.os.Handler().postDelayed(() -> notifyServer("success"), 4000);
            }
        }
    }

    private void extractSolde(String text) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("([0-9][0-9.,\\s]+[0-9])\\s*F");
            java.util.regex.Matcher m = p.matcher(text);
            while (m.find()) {
                String s = m.group(1).replace(".", "").replace(",", "").replace(" ", "").trim();
                try {
                    long val = Long.parseLong(s);
                    if (val > 100) { // Ignorer les petits nombres (ex: 18 du numero de telephone)
                        soldeWave = val;
                        Log.d(TAG, "Solde Wave: " + soldeWave + "F");
                        break;
                    }
                } catch(NumberFormatException e2) {}
            }
        } catch(Exception e) {
            Log.e(TAG, "Erreur extraction solde: " + e.getMessage());
        }
    }

    private void tapAboveText(AccessibilityNodeInfo root, String text) {
        // Trouver tous les noeuds avec ce texte et logger leurs positions
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        Log.d(TAG, "tapAboveText: " + nodes.size() + " noeuds trouves pour: " + text);
        android.graphics.Rect bestBounds = null;
        int bestY = Integer.MAX_VALUE;
        // Prendre le noeud le plus haut sur l ecran (premier dans la grille)
        for (AccessibilityNodeInfo node : nodes) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            Log.d(TAG, "  noeud: " + bounds + " clickable=" + node.isClickable());
            if (bounds.top < bestY && bounds.top > 0) {
                bestY = bounds.top;
                bestBounds = bounds;
            }
        }
        if (bestBounds != null) {
            int x = bestBounds.centerX();
            // Tapper au centre du texte (l icone et le texte forment un bloc)
            int y = bestBounds.centerY();
            Log.d(TAG, "tapAboveText: tap at " + x + "," + y + " bounds=" + bestBounds);
            tapAt(x, y);
        }
    }

    private void tapAt(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            android.accessibilityservice.GestureDescription.Builder gb = new android.accessibilityservice.GestureDescription.Builder();
            gb.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100));
            dispatchGesture(gb.build(), null, null);
            Log.d(TAG, "Tap at " + x + "," + y);
        }
    }

    private void clickFirstButton(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo parent = node.getParent();
            // Chercher un parent clickable proche (max 3 niveaux)
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
