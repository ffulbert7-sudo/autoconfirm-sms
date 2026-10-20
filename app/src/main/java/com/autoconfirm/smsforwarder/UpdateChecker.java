package com.autoconfirm.smsforwarder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StrictMode;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

public class UpdateChecker {
    private static final int CURRENT_VERSION = 44;

    public static void check(Activity activity) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = activity.getSharedPreferences("config", Context.MODE_PRIVATE);
                String serverUrl = prefs.getString("url", "https://autoconfirm.online/webhook/saas")
                    .replace("/webhook/saas", "");
                
                URL url = new URL(serverUrl + "/api/app-version");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                InputStream is = conn.getInputStream();
                byte[] buffer = new byte[1024];
                StringBuilder sb = new StringBuilder();
                int len;
                while ((len = is.read(buffer)) != -1) sb.append(new String(buffer, 0, len));
                
                JSONObject json = new JSONObject(sb.toString());
                int latestVersion = json.getInt("version");
                String apkUrl = json.getString("url");
                
                if (latestVersion > CURRENT_VERSION) {
                    activity.runOnUiThread(() -> {
                        new AlertDialog.Builder(activity)
                            .setTitle("Mise a jour disponible")
                            .setMessage("Version " + latestVersion + " disponible. Voulez-vous mettre a jour ?")
                            .setPositiveButton("Mettre a jour", (d, w) -> downloadAndInstall(activity, apkUrl))
                            .setNegativeButton("Plus tard", null)
                            .show();
                    });
                }
            } catch (Exception e) {
                // Silencieux si pas de connexion
            }
        }).start();
    }

    private static void downloadAndInstall(Activity activity, String apkUrl) {
        new Thread(() -> {
            try {
                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                InputStream is = conn.getInputStream();
                
                File apkFile = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AutoConfirmSMS_update.apk");
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) fos.write(buffer, 0, len);
                fos.close();
                
                activity.runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    Uri apkUri;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        apkUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apkFile);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        apkUri = Uri.fromFile(apkFile);
                    }
                    intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> android.widget.Toast.makeText(activity, "Erreur de téléchargement", android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
