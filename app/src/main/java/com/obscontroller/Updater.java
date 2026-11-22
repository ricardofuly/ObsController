package com.obscontroller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Atualização automática pelo GitHub Releases.
 * Procura a release mais recente de REPO, compara a tag (vX.Y.Z) com a versão instalada,
 * baixa o APK anexado e abre o instalador do Android.
 */
final class Updater {

    static final String REPO = "ricardofuly/ObsController";
    private static final long AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L; // no máximo a cada 6 h

    private final Activity act;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private File pendingApk;

    Updater(Activity act, SharedPreferences prefs) {
        this.act = act;
        this.prefs = prefs;
    }

    static String installedVersion(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** manual = o usuário tocou em "Verificar atualizações" (sempre checa e sempre dá retorno). */
    void check(boolean manual) {
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("lastUpdateCheck", 0) < AUTO_CHECK_INTERVAL_MS) return;
        prefs.edit().putLong("lastUpdateCheck", now).apply();
        if (manual) toast("Procurando atualizações…");
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url("https://api.github.com/repos/" + REPO + "/releases/latest")
                        .header("Accept", "application/vnd.github+json")
                        .build();
                try (Response r = http.newCall(req).execute()) {
                    if (r.code() == 404) {
                        if (manual) toast("Nenhuma versão publicada ainda.");
                        return;
                    }
                    if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
                    JSONObject rel = new JSONObject(r.body().string());
                    String tag = rel.optString("tag_name", "");
                    String notes = rel.optString("body", "");
                    String apkUrl = null;
                    long size = 0;
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name").toLowerCase().endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url");
                                size = a.optLong("size");
                                break;
                            }
                        }
                    }
                    String remote = tag.replaceFirst("^[vV]", "");
                    String local = installedVersion(act);
                    if (apkUrl != null && compare(remote, local) > 0) {
                        final String url = apkUrl;
                        final long sz = size;
                        main.post(() -> offer(remote, local, notes, url, sz));
                    } else if (manual) {
                        toast("Você já está na versão mais recente (" + local + ").");
                    }
                }
            } catch (Exception e) {
                if (manual) toast("Não foi possível verificar atualizações: " + e.getMessage());
            }
        }).start();
    }

    private void offer(String remote, String local, String notes, String url, long size) {
        if (act.isFinishing()) return;
        String mb = size > 0 ? String.format(java.util.Locale.ROOT, " (%.1f MB)", size / 1048576.0) : "";
        String msg = "Versão instalada: " + local + "\nNova versão: " + remote + mb
                + (notes.trim().isEmpty() ? "" : "\n\n" + notes.trim());
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Atualização disponível")
                .setMessage(msg)
                .setPositiveButton("Atualizar", (d, w) -> download(remote, url))
                .setNegativeButton("Depois", null)
                .show();
    }

    private void download(String version, String url) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = new TextView(act);
        label.setText("Baixando…");
        box.addView(bar);
        box.addView(label);
        AlertDialog dlg = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Baixando versão " + version)
                .setView(box)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            File dir = new File(act.getCacheDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File out = new File(dir, "ObsController-" + version + ".apk");
            try (Response r = http.newCall(new Request.Builder().url(url).build()).execute()) {
                ResponseBody body = r.body();
                if (!r.isSuccessful() || body == null) throw new Exception("HTTP " + r.code());
                long total = body.contentLength();
                try (InputStream in = body.byteStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final long d = done;
                                main.post(() -> {
                                    bar.setProgress(pct);
                                    label.setText(String.format(java.util.Locale.ROOT, "%.1f de %.1f MB",
                                            d / 1048576.0, total / 1048576.0));
                                });
                            }
                        }
                    }
                }
                main.post(() -> {
                    dlg.dismiss();
                    pendingApk = out;
                    install();
                });
            } catch (Exception e) {
                main.post(() -> {
                    dlg.dismiss();
                    toast("Falha ao baixar a atualização: " + e.getMessage());
                });
            }
        }).start();
    }

    /** Chamado também em onResume: se o usuário voltou da tela de permissão, continua a instalação. */
    void resumeInstallIfPending() {
        if (pendingApk != null && canInstall()) install();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || act.getPackageManager().canRequestPackageInstalls();
    }

    private void install() {
        if (pendingApk == null || !pendingApk.exists()) return;
        if (!canInstall()) {
            new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Permitir instalação")
                    .setMessage("Para se atualizar, o OBS Controle precisa da permissão \"Instalar apps desconhecidos\". "
                            + "Ative na próxima tela e volte para o app.")
                    .setPositiveButton("Abrir configuração", (d, w) -> act.startActivity(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName()))))
                    .setNegativeButton("Cancelar", (d, w) -> pendingApk = null)
                    .show();
            return;
        }
        File apk = pendingApk;
        pendingApk = null;
        Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".updates", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
    }

    /** Compara versões "1.2.10" numericamente. */
    static int compare(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(act, s, Toast.LENGTH_LONG).show());
    }
}
