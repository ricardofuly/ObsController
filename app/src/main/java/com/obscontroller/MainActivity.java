package com.obscontroller;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements ObsClient.Listener {

    private static final long RECONNECT_DELAY_MS = 3000;
    private static final int PAGE_SIZE = 10;
    private static final long VOLUME_SEND_INTERVAL_MS = 60;

    private int cText, cMuted, cAccent, cAccentBg, cLive, cOnAirRing,
            cMutedFg, cMutedBg, cSurface, cWarn, cError;

    private ObsClient obs;
    private Updater updater;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GestureDetector swipe;

    // Views (re-ligadas a cada rotação)
    private View settingsOverlay, statusDot, audioScroll, pager;
    private TextView liveBadge;
    private TextView statusText, statusSub, settingsStatus, pageLabel, audioEmpty;
    private EditText hostInput, portInput, passInput;
    private CheckBox keepOn;
    private Button connectBtn;
    private ImageButton prevBtn, nextBtn, closeSettings;
    private FrameLayout sceneGrid;
    private LinearLayout dots, audioBox;

    // Estado
    private List<String> scenes = new ArrayList<>();
    private String currentScene = "";
    private List<ObsClient.AudioInput> inputs = new ArrayList<>();
    /** Fontes de áudio da cena no ar (null = ainda não carregado). */
    private List<String> sceneAudio;
    private int page;
    private boolean connected;
    private int statusColor;
    private String statusTitle = "Desconectado", statusDetail = "";
    private final Map<String, View> sceneTiles = new HashMap<>();
    private final Map<String, Chip> chips = new HashMap<>();

    // Saídas (teclas de função)
    private boolean streaming, recording, recPaused, vcamOn, replayOn;
    private long streamStart, recordStart;
    private final Map<String, View> fnKeys = new HashMap<>();
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            paintFnKeys();
            if (streaming || recording) handler.postDelayed(this, 1000);
        }
    };

    private boolean wantConnected, started;
    /** Conexão caiu e o app está tentando voltar: mantém a última tela, apagada, com aviso. */
    private boolean stale;
    private final Runnable reconnect = this::doConnect;

    private final Rect gridRect = new Rect();
    private boolean touchInGrid;

    private String draggingInput;
    private long lastVolumeSend;

    // Janela de opções da faixa de áudio
    private Dialog audioDialog;
    private String dialogInput;
    private TextView dialogDb;
    private FaderView dialogFader;
    private Button dialogMute;

    private static final class Chip {
        FaderView fader;
        ValueAnimator blink;
        ImageButton mute;
        TextView db;
        View chip;
    }

    private Dialog mixerDialog;
    private final Map<String, Long> localVolumeAt = new HashMap<>();
    private final Map<String, Double> lastSentVolume = new HashMap<>();
    private final Map<String, double[]> meterState = new HashMap<>(); // {dB exibido, instante}
    private final Map<String, MixCol> mixCols = new HashMap<>();

    // ================================================================ Ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cText = getColor(R.color.text);
        cMuted = getColor(R.color.text_muted);
        cAccent = getColor(R.color.accent);
        cAccentBg = getColor(R.color.accent_bg);
        cLive = getColor(R.color.live);
        cOnAirRing = 0xFFFF5A60;
        cMutedFg = getColor(R.color.muted);
        cMutedBg = getColor(R.color.muted_bg);
        cSurface = getColor(R.color.surface);
        cWarn = getColor(R.color.warn);
        cError = getColor(R.color.error);
        statusColor = cMuted;

        prefs = getSharedPreferences("obs", MODE_PRIVATE);
        wantConnected = prefs.getBoolean("autoConnect", false)
                && !TextUtils.isEmpty(prefs.getString("host", ""));
        obs = new ObsClient(this);
        updater = new Updater(this, prefs);
        handler.postDelayed(() -> updater.check(false), 4000); // checagem automática (no máximo a cada 6 h)

        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    changePage(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });

        setupContent();
        handleObsLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleObsLink(intent);
    }

    // ================================================================ QR Code / link obsws://

    private void handleObsLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data != null) applyObsUrl(data.toString());
        intent.setData(null);
    }

    private void scanQr() {
        GmsBarcodeScannerOptions opts = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        GmsBarcodeScanning.getClient(this, opts).startScan()
                .addOnSuccessListener(b -> applyObsUrl(b.getRawValue()))
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Não foi possível abrir o leitor de QR (" + e.getMessage() + "). Digite os dados ou use a câmera do celular.",
                        Toast.LENGTH_LONG).show());
    }

    /** QR do OBS: obsws://IP:PORTA ou obsws://IP:PORTA/SENHA. Pergunta antes de trocar a conexão. */
    private void applyObsUrl(String raw) {
        if (raw == null || !raw.trim().toLowerCase(Locale.ROOT).startsWith("obsws://")) {
            Toast.makeText(this, "Esse QR Code não é do OBS WebSocket.", Toast.LENGTH_LONG).show();
            return;
        }
        String rest = raw.trim().substring("obsws://".length());
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String pass = "";
        if (slash >= 0) {
            try {
                pass = URLDecoder.decode(rest.substring(slash + 1), "UTF-8");
            } catch (Exception e) {
                pass = rest.substring(slash + 1);
            }
        }
        String host = hostPort;
        int port = 4455;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && hostPort.indexOf(':') == colon) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        if (host.isEmpty()) {
            Toast.makeText(this, "QR Code sem endereço do OBS.", Toast.LENGTH_LONG).show();
            return;
        }
        final String fHost = host, fPass = pass;
        final int fPort = port;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Conectar ao OBS?")
                .setMessage(host + ":" + port + (pass.isEmpty() ? "\nSem senha" : "\nSenha incluída no QR Code"))
                .setPositiveButton("Conectar", (d, w) -> {
                    prefs.edit().putString("host", fHost).putInt("port", fPort).putString("pass", fPass)
                            .putBoolean("autoConnect", true).apply();
                    hostInput.setText(fHost);
                    portInput.setText(String.valueOf(fPort));
                    passInput.setText(fPass);
                    wantConnected = true;
                    hideKeyboard();
                    doConnect();
                    updateConnectButton();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Troca de layout (retrato ↔ horizontal) sem derrubar a conexão.
        boolean overlayOpen = settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE;
        setupContent();
        settingsOverlay.setVisibility(overlayOpen ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (wantConnected) {
            settingsOverlay.setVisibility(View.GONE);
            doConnect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updater.resumeInstallIfPending();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        handler.removeCallbacks(reconnect);
        handler.removeCallbacks(ticker);
        obs.disconnect();
    }

    @Override
    public void onBackPressed() {
        if (settingsOverlay.getVisibility() == View.VISIBLE && connected) {
            settingsOverlay.setVisibility(View.GONE);
        } else {
            super.onBackPressed();
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void setupContent() {
        Window w = getWindow();
        if (isLandscape()) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);
        settingsOverlay = findViewById(R.id.settingsOverlay);
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        statusSub = findViewById(R.id.statusSub);
        settingsStatus = findViewById(R.id.settingsStatus);
        pageLabel = findViewById(R.id.pageLabel);
        audioEmpty = findViewById(R.id.audioEmpty);
        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        passInput = findViewById(R.id.passInput);
        keepOn = findViewById(R.id.keepOn);
        connectBtn = findViewById(R.id.connectBtn);
        prevBtn = findViewById(R.id.prevBtn);
        nextBtn = findViewById(R.id.nextBtn);
        closeSettings = findViewById(R.id.closeSettings);
        sceneGrid = findViewById(R.id.sceneGrid);
        dots = findViewById(R.id.dots); // só existe no retrato
        audioBox = findViewById(R.id.audioBox);
        audioScroll = findViewById(R.id.audioScroll);
        pager = findViewById(R.id.pager);
        liveBadge = findViewById(R.id.liveBadge);

        passInput.setTypeface(Typeface.DEFAULT);
        prevBtn.setBackground(bg(cSurface, 10));
        nextBtn.setBackground(bg(cSurface, 10));
        prevBtn.setOnClickListener(v -> changePage(-1));
        nextBtn.setOnClickListener(v -> changePage(1));

        hostInput.setText(prefs.getString("host", ""));
        portInput.setText(String.valueOf(prefs.getInt("port", 4455)));
        passInput.setText(prefs.getString("pass", ""));
        keepOn.setChecked(prefs.getBoolean("keepOn", true));
        applyKeepScreenOn();
        keepOn.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("keepOn", checked).apply();
            applyKeepScreenOn();
        });

        connectBtn.setOnClickListener(v -> {
            if (wantConnected) {
                wantConnected = false;
                prefs.edit().putBoolean("autoConnect", false).apply();
                handler.removeCallbacks(reconnect);
                obs.disconnect();
            } else if (saveSettings()) {
                wantConnected = true;
                prefs.edit().putBoolean("autoConnect", true).apply();
                hideKeyboard();
                doConnect();
            }
            updateConnectButton();
        });
        View scan = findViewById(R.id.scanQrBtn);
        scan.setBackground(bg(cAccent, 16));
        scan.setContentDescription("Ler QR Code do OBS");
        scan.setOnClickListener(v -> scanQr());
        ImageButton passToggle = findViewById(R.id.passToggle);
        passToggle.setOnClickListener(v -> {
            boolean hidden = (passInput.getInputType() & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            int sel = passInput.getSelectionEnd();
            passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | (hidden ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passInput.setTypeface(Typeface.DEFAULT);
            passInput.setSelection(Math.max(0, sel));
            passToggle.setImageResource(hidden ? R.drawable.ic_eye_off : R.drawable.ic_eye);
            passToggle.setContentDescription(hidden ? "Ocultar senha" : "Mostrar senha");
        });
        ((TextView) findViewById(R.id.versionText)).setText("Versão " + Updater.installedVersion(this));
        Button updateBtn = findViewById(R.id.updateBtn);
        updateBtn.setBackground(bg(0xFF22252B, 12));
        updateBtn.setOnClickListener(v -> updater.check(true));
        View mixerBtn = findViewById(R.id.mixerBtn);
        mixerBtn.setBackground(bg(0xFF22252B, 12));
        mixerBtn.setOnClickListener(v -> showMixer());
        findViewById(R.id.settingsBtn).setOnClickListener(v -> settingsOverlay.setVisibility(View.VISIBLE));
        closeSettings.setOnClickListener(v -> {
            hideKeyboard();
            settingsOverlay.setVisibility(View.GONE);
        });

        Button resetHidden = findViewById(R.id.resetHiddenBtn);
        resetHidden.setBackground(bg(0xFF22252B, 12));
        resetHidden.setOnClickListener(v -> showHiddenList());
        updateResetHidden();

        settingsOverlay.setVisibility(wantConnected ? View.GONE : View.VISIBLE);
        updateConnectButton();
        renderStatus();
        renderScenes(0);
        renderAudio();
    }

    // ================================================================ Conexão

    private void doConnect() {
        handler.removeCallbacks(reconnect);
        if (!started || !wantConnected) return;
        obs.connect(prefs.getString("host", ""), prefs.getInt("port", 4455), prefs.getString("pass", ""));
    }

    private boolean saveSettings() {
        String host = hostInput.getText().toString().trim();
        if (host.startsWith("ws://")) host = host.substring(5);
        if (host.isEmpty()) {
            hostInput.setError("Informe o IP do computador");
            return false;
        }
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = 4455;
        }
        prefs.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("pass", passInput.getText().toString())
                .apply();
        return true;
    }

    // ================================================================ ObsClient.Listener

    @Override
    public void onState(ObsClient.State state, String message, boolean canRetry) {
        connected = state == ObsClient.State.CONNECTED;
        switch (state) {
            case CONNECTING:
                setStatus(cWarn, "Conectando…", message);
                break;
            case CONNECTED:
                setStatus(cLive, "Conectado", prefs.getString("host", ""));
                settingsOverlay.setVisibility(View.GONE);
                showTipsOnce();
                if (stale) {
                    stale = false;
                    renderScenes(0);
                    renderAudio();
                }
                break;
            case DISCONNECTED:
                if (audioDialog != null) audioDialog.dismiss();
                if (mixerDialog != null) mixerDialog.dismiss();
                stale = wantConnected && started && canRetry && !scenes.isEmpty();
                if (!stale) {
                    scenes = new ArrayList<>();
                    inputs = new ArrayList<>();
                    sceneAudio = null;
                    streaming = recording = recPaused = vcamOn = replayOn = false;
                }
                renderScenes(0);
                renderAudio();
                if (wantConnected && started && canRetry) {
                    setStatus(cError, "Reconectando…", message);
                    handler.postDelayed(reconnect, RECONNECT_DELAY_MS);
                } else {
                    if (!canRetry && wantConnected) {
                        wantConnected = false;
                        prefs.edit().putBoolean("autoConnect", false).apply();
                        settingsOverlay.setVisibility(View.VISIBLE);
                    }
                    setStatus(canRetry ? cMuted : cError, canRetry ? "Desconectado" : message, "");
                }
                break;
        }
        updateConnectButton();
    }

    @Override
    public void onScenes(List<String> list, String current) {
        scenes = list;
        currentScene = current;
        page = Math.min(page, Math.max(0, pageCount() - 1));
        renderScenes(0);
    }

    @Override
    public void onCurrentScene(String name) {
        currentScene = name;
        paintScenes();
    }

    @Override
    public void onAudioInputs(List<ObsClient.AudioInput> list) {
        inputs = list;
        renderAudio();
    }

    @Override
    public void onInputMute(String name, boolean muted) {
        ObsClient.AudioInput in = input(name);
        if (in != null) in.muted = muted;
        Chip c = chips.get(name);
        if (c != null) paintChip(c, name, muted, volumeOf(name));
        if (name.equals(dialogInput)) paintDialog();
        paintMixCol(name);
    }

    @Override
    public void onInputVolume(String name, double volumeMul) {
        if (name.equals(draggingInput)) return; // não briga com o dedo do usuário
        // Ecos atrasados dos valores intermediários que o app mandou durante o arraste: ignorar,
        // senão o fader "volta" para trás depois de soltar e só então pula para o valor final.
        Long t = localVolumeAt.get(name);
        Double sent = lastSentVolume.get(name);
        if (t != null && sent != null && SystemClock.uptimeMillis() - t < 1500
                && Math.abs(sent - volumeMul) > 0.0005) return;
        ObsClient.AudioInput in = input(name);
        if (in != null) in.volumeMul = volumeMul;
        Chip c = chips.get(name);
        if (c != null) paintChip(c, name, mutedOf(name), volumeMul);
        if (name.equals(dialogInput)) paintDialog();
        paintMixCol(name);
    }

    @Override
    public void onOutput(String key, boolean active, long durationMs) {
        long now = SystemClock.elapsedRealtime();
        switch (key) {
            case "stream":
                if (active && !streaming) streamStart = now - Math.max(0, durationMs);
                streaming = active;
                break;
            case "record":
                if (active && !recording) recordStart = now - Math.max(0, durationMs);
                recording = active;
                break;
            case "pause": recPaused = active; break;
            case "vcam": vcamOn = active; break;
            case "replay": replayOn = active; break;
        }
        handler.removeCallbacks(ticker);
        ticker.run();
        repaintChips();
    }

    @Override
    public void onSceneAudio(String scene, List<String> sourceNames) {
        sceneAudio = sourceNames;
        if (audioDialog != null && dialogInput != null && !sourceNames.contains(dialogInput)) audioDialog.dismiss();
        renderAudio();
        if (mixerDialog != null) showMixer();
    }

    @Override
    public void onVolumeMeters(Map<String, Float> peaks) {
        if (stale) return;
        for (Map.Entry<String, Float> e : peaks.entrySet()) {
            Chip c = chips.get(e.getKey());
            MixCol mc = mixCols.get(e.getKey());
            if (c == null && mc == null && !e.getKey().equals(dialogInput)) continue;
            float p = e.getValue() == null ? 0f : e.getValue();
            double db = p <= 0.00001f ? -100 : 20 * Math.log10(p);
            // Queda suave (como o medidor do OBS): sobe na hora, desce no máximo ~25 dB/s.
            long nowMs = SystemClock.uptimeMillis();
            double[] st = meterState.get(e.getKey());
            if (st == null) st = new double[]{-100, nowMs};
            double dt = Math.max(0, (nowMs - st[1]) / 1000.0);
            double shown = db >= st[0] ? db : Math.max(db, st[0] - 25 * dt);
            st[0] = shown;
            st[1] = nowMs;
            meterState.put(e.getKey(), st);
            db = shown;
            // Mesmo eixo do fader: um sinal no máximo encosta na marca do volume (o medidor é pós-fader).
            float frac = (float) toPos(db <= -96 ? 0 : Math.pow(10, db / 20));
            int color = db > -9 ? 0xFFFF5A60 : db > -20 ? 0xFFF5A524 : 0xFF3DD68C;
            if (c != null) c.fader.setMeter(frac, color);
            if (mc != null) mc.fader.setMeter(frac, color);
            if (e.getKey().equals(dialogInput) && dialogFader != null) dialogFader.setMeter(frac, color);
        }
    }

    @Override
    public void onReplaySaved() {
        Toast.makeText(this, "Replay salvo", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showTipsOnce() {
        if (prefs.getBoolean("tipsShown", false)) return;
        prefs.edit().putBoolean("tipsShown", true).apply();
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Dicas rápidas")
                .setMessage("• Segure uma cena para trocar o ícone dela.\n\n"
                        + "• Arraste uma faixa de áudio para os lados para mudar o volume; toque nela para mais opções; segure para ocultá-la. O botão Mixer abre faders grandes.\n\n"
                        + "• Deslize as cenas para o lado quando houver mais de 10.\n\n"
                        + "• O microfone pisca em vermelho se estiver mutado durante a live ou gravação.")
                .setPositiveButton("Entendi", null)
                .show();
    }

    // ================================================================ Deck: cenas + funções

    private int pageCount() {
        return Math.max(1, (scenes.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void changePage(int delta) {
        int target = page + delta;
        if (target < 0 || target >= pageCount()) return;
        page = target;
        renderScenes(delta);
    }

    /** Monta a página atual do deck. direction != 0 anima a entrada das cenas (deslize). */
    private void renderScenes(int direction) {
        sceneGrid.removeAllViews();
        sceneTiles.clear();
        fnKeys.clear();
        int pages = pageCount();
        pageLabel.setText((page + 1) + " / " + pages);
        prevBtn.setEnabled(page > 0);
        prevBtn.setAlpha(page > 0 ? 1f : 0.35f);
        nextBtn.setEnabled(page < pages - 1);
        nextBtn.setAlpha(page < pages - 1 ? 1f : 0.35f);
        // Com uma página só, setas e "1 / 1" são ruído.
        pager.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
        renderDots(pages);

        boolean land = isLandscape();
        int cols = land ? 5 : 2, rows = land ? 2 : 5;
        LinearLayout deck = new LinearLayout(this);
        deck.setOrientation(LinearLayout.VERTICAL);

        LinearLayout sceneArea = new LinearLayout(this);
        sceneArea.setOrientation(LinearLayout.VERTICAL);
        if (scenes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(connected ? "Nenhuma cena encontrada." : "Conecte ao OBS para ver as cenas.");
            empty.setTextColor(0xFF6B7079);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            empty.setGravity(Gravity.CENTER);
            sceneArea.addView(empty, new LinearLayout.LayoutParams(-1, -1));
        } else {
            int start = page * PAGE_SIZE;
            // Só as linhas que têm cena: as teclas crescem em vez de sobrar espaço vazio.
            int onPage = Math.min(PAGE_SIZE, scenes.size() - start);
            rows = Math.max(1, (onPage + cols - 1) / cols);
            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(this);
                for (int c = 0; c < cols; c++) {
                    int idx = start + r * cols + c;
                    View cell = idx < scenes.size() && idx < start + PAGE_SIZE ? makeSceneKey(scenes.get(idx)) : new View(this);
                    row.addView(cell, keyParams());
                }
                sceneArea.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
            }
        }
        // Horizontal: 3 linhas iguais (2 de cenas + 1 de funções). Retrato: 5 de cenas + 1 de funções.
        deck.addView(sceneArea, new LinearLayout.LayoutParams(-1, 0, rows));

        LinearLayout fnRow = new LinearLayout(this);
        fnRow.addView(makeFnKey("stream", R.drawable.ic_live), keyParams());
        fnRow.addView(makeFnKey("record", R.drawable.ic_rec), keyParams());
        fnRow.addView(makeFnKey("pause", R.drawable.ic_pause), keyParams());
        fnRow.addView(makeFnKey("replay", R.drawable.ic_replay), keyParams());
        fnRow.addView(makeFnKey("vcam", R.drawable.ic_cam), keyParams());
        deck.addView(fnRow, new LinearLayout.LayoutParams(-1, 0, land ? 1f : 0.9f));

        sceneGrid.addView(deck, new FrameLayout.LayoutParams(-1, -1));
        paintScenes();
        paintFnKeys();

        if (stale) {
            deck.setAlpha(0.35f);
            TextView banner = new TextView(this);
            banner.setText("Sem conexão com o OBS — reconectando…");
            banner.setTextColor(Color.WHITE);
            banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            banner.setTypeface(Typeface.DEFAULT_BOLD);
            banner.setPadding(dp(16), dp(10), dp(16), dp(10));
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(dp(99));
            bb.setColor(0xF0B3261E);
            banner.setBackground(bb);
            banner.setElevation(dp(6));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
            sceneGrid.addView(banner, blp);
        }

        if (direction != 0) {
            sceneArea.setTranslationX(direction * dp(48));
            sceneArea.setAlpha(0f);
            sceneArea.animate().translationX(0).alpha(1f).setDuration(160).start();
        }
    }

    private LinearLayout.LayoutParams keyParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private void renderDots(int pages) {
        if (dots == null) return;
        dots.removeAllViews();
        if (pages <= 1) return;
        for (int i = 0; i < pages; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(3));
            g.setColor(i == page ? cText : 0xFF3A3E46);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == page ? 18 : 6), dp(6));
            lp.setMargins(dp(3), 0, dp(3), 0);
            final int target = i;
            d.setOnClickListener(v -> changePage(target - page));
            dots.addView(d, lp);
        }
    }

    /** Tecla do deck: ícone no topo, selo opcional à direita, nome embaixo. */
    private LinearLayout makeKey(int iconRes, String emoji, String label) {
        LinearLayout key = new LinearLayout(this);
        key.setOrientation(LinearLayout.VERTICAL);
        boolean land = isLandscape();
        key.setPadding(dp(12), dp(land ? 7 : 10), dp(10), dp(land ? 7 : 10));
        key.setClickable(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (emoji != null) {
            TextView e = new TextView(this);
            e.setText(emoji);
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            e.setIncludeFontPadding(false);
            top.addView(e, new LinearLayout.LayoutParams(-2, dp(26)));
        } else {
            ImageView icon = new ImageView(this);
            icon.setTag("icon");
            icon.setImageResource(iconRes);
            top.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView pill = new TextView(this);
        pill.setTag("pill");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setLetterSpacing(0.06f);
        pill.setFontFeatureSettings("tnum");
        pill.setPadding(dp(7), dp(2), dp(7), dp(2));
        GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(dp(99));
        pb.setColor(0x38FFFFFF);
        pill.setBackground(pb);
        pill.setVisibility(View.GONE);
        top.addView(pill);
        key.addView(top, new LinearLayout.LayoutParams(-1, -2));

        key.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        TextView name = new TextView(this);
        name.setTag("name");
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(land ? 1 : 2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setIncludeFontPadding(false);
        key.addView(name, new LinearLayout.LayoutParams(-1, -2));
        return key;
    }

    private void styleKey(View key, int fill, boolean ring, boolean dim, int fg) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(18));
        shape.setColor(fill);
        if (ring) shape.setStroke(dp(3), cOnAirRing);
        else if (dim) shape.setStroke(dp(1), 0xFF2C3038);
        key.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null));
        View icon = key.findViewWithTag("icon");
        if (icon instanceof ImageView) ((ImageView) icon).setImageTintList(ColorStateList.valueOf(fg));
        ((TextView) key.findViewWithTag("name")).setTextColor(fg);
    }

    private String iconKeyFor(String sceneName) {
        String chosen = prefs.getString("icon:" + sceneName, "");
        return chosen.isEmpty() ? SceneIcons.guess(sceneName) : chosen;
    }

    private View makeSceneKey(String sceneName) {
        String[] emoji = SceneIcons.splitEmoji(sceneName);
        boolean useEmoji = emoji != null && prefs.getString("icon:" + sceneName, "").isEmpty();
        String label = emoji != null ? emoji[1] : sceneName;
        LinearLayout key = makeKey(SceneIcons.resFor(iconKeyFor(sceneName)), useEmoji ? emoji[0] : null, label);
        key.setContentDescription("Cena " + sceneName);
        key.setOnClickListener(v -> {
            if (!connected) {
                Toast.makeText(this, "Sem conexão com o OBS", Toast.LENGTH_SHORT).show();
                return;
            }
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            obs.setScene(sceneName);
        });
        key.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showIconPicker(sceneName);
            return true;
        });
        sceneTiles.put(sceneName, key);
        return key;
    }

    private void paintScenes() {
        for (Map.Entry<String, View> e : sceneTiles.entrySet()) {
            boolean live = e.getKey().equals(currentScene);
            View key = e.getValue();
            styleKey(key, SceneIcons.colorFor(iconKeyFor(e.getKey())), live, false, Color.WHITE);
            TextView pill = key.findViewWithTag("pill");
            pill.setText("NO AR");
            pill.setVisibility(live ? View.VISIBLE : View.GONE);
            key.setAlpha(live || currentScene.isEmpty() ? 1f : 0.88f);
        }
    }

    /** Tecla de função estreita (retrato): ícone em cima e nome curto centralizado, sempre em uma linha. */
    private LinearLayout makeCompactKey(int iconRes) {
        LinearLayout key = new LinearLayout(this);
        key.setOrientation(LinearLayout.VERTICAL);
        key.setGravity(Gravity.CENTER);
        key.setPadding(dp(4), dp(6), dp(4), dp(6));
        key.setClickable(true);
        key.setTag("compact");
        ImageView icon = new ImageView(this);
        icon.setTag("icon");
        icon.setImageResource(iconRes);
        key.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView name = new TextView(this);
        name.setTag("name");
        name.setGravity(Gravity.CENTER);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setFontFeatureSettings("tnum");
        name.setIncludeFontPadding(false);
        name.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, dp(18));
        nlp.topMargin = dp(6);
        key.addView(name, nlp);
        TextView pill = new TextView(this);
        pill.setTag("pill");
        pill.setVisibility(View.GONE);
        key.addView(pill);
        return key;
    }

    private View makeFnKey(String fn, int iconRes) {
        LinearLayout key = isLandscape() ? makeKey(iconRes, null, "") : makeCompactKey(iconRes);
        key.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            pressFn(fn);
        });
        fnKeys.put(fn, key);
        return key;
    }

    private void pressFn(String fn) {
        if (!connected) {
            Toast.makeText(this, "Conecte ao OBS primeiro", Toast.LENGTH_SHORT).show();
            return;
        }
        switch (fn) {
            case "stream":
                if (streaming) confirm("Encerrar a transmissão?", "Encerrar", obs::toggleStream);
                else confirm("Iniciar a transmissão agora?", "Transmitir", obs::toggleStream);
                break;
            case "record":
                if (recording) confirm("Parar a gravação?", "Parar", obs::toggleRecord);
                else obs.toggleRecord();
                break;
            case "pause":
                if (recording) obs.toggleRecordPause();
                else Toast.makeText(this, "Nenhuma gravação em andamento", Toast.LENGTH_SHORT).show();
                break;
            case "replay":
                if (replayOn) obs.saveReplayBuffer();
                else {
                    obs.startReplayBuffer();
                    Toast.makeText(this, "Buffer de replay ligado. Toque de novo para salvar.", Toast.LENGTH_LONG).show();
                }
                break;
            case "vcam":
                obs.toggleVirtualCam();
                break;
        }
    }

    private void confirm(String question, String action, Runnable onYes) {
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(question)
                .setPositiveButton(action, (x, y) -> onYes.run())
                .setNegativeButton("Cancelar", null)
                .create();
        d.show();
    }

    /** Selo no cabeçalho, visível o tempo todo: AO VIVO / REC com o tempo. */
    private void paintLiveBadge() {
        if (liveBadge == null) return;
        long now = SystemClock.elapsedRealtime();
        String text = null;
        int color = 0;
        if (streaming) {
            text = "● AO VIVO " + shortClock(now - streamStart) + (recording ? " · REC" : "");
            color = 0xFFC8262C;
        } else if (recording) {
            text = recPaused ? "❚❚ REC PAUSADO" : "● REC " + shortClock(now - recordStart);
            color = recPaused ? 0xFF9A5B00 : 0xFFB4245A;
        }
        liveBadge.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (text == null) return;
        liveBadge.setText(text);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(99));
        g.setColor(color);
        liveBadge.setBackground(g);
    }

    private void paintFnKeys() {
        paintLiveBadge();
        long now = SystemClock.elapsedRealtime();
        if (isLandscape()) {
            paintFn("stream", streaming, streaming ? "Ao vivo" : "Transmitir", 0xFFC8262C, streaming ? clock(now - streamStart) : null);
            paintFn("record", recording, recording ? (recPaused ? "Pausado" : "Gravando") : "Gravar", 0xFFB4245A,
                    recording ? clock(now - recordStart) : null);
            paintFn("pause", recPaused, recPaused ? "Retomar" : "Pausar", 0xFF9A5B00, null);
            paintFn("replay", replayOn, replayOn ? "Salvar replay" : "Replay", 0xFF0B7A6E, replayOn ? "ON" : null);
            paintFn("vcam", vcamOn, "Câmera virtual", 0xFF1F5FB0, vcamOn ? "ON" : null);
        } else {
            // Retrato: nomes curtos; quando ativo, Live/Gravar mostram o tempo no lugar do nome.
            paintFn("stream", streaming, streaming ? shortClock(now - streamStart) : "Live", 0xFFC8262C, null);
            paintFn("record", recording, recording ? (recPaused ? "Pausado" : shortClock(now - recordStart)) : "Gravar", 0xFFB4245A, null);
            paintFn("pause", recPaused, recPaused ? "Retomar" : "Pausar", 0xFF9A5B00, null);
            paintFn("replay", replayOn, replayOn ? "Salvar" : "Replay", 0xFF0B7A6E, null);
            paintFn("vcam", vcamOn, "Câmera", 0xFF1F5FB0, null);
        }
    }

    private static String shortClock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    private void paintFn(String fn, boolean on, String label, int onColor, String badge) {
        View key = fnKeys.get(fn);
        if (key == null) return;
        styleKey(key, on ? onColor : 0xFF1C1F25, false, !on, on ? Color.WHITE : cText);
        ((TextView) key.findViewWithTag("name")).setText(label);
        String[] full = {"stream", "Transmitir", "record", "Gravar", "pause", "Pausar gravação", "replay", "Replay", "vcam", "Câmera virtual"};
        String desc = label;
        for (int i = 0; i < full.length; i += 2) if (full[i].equals(fn)) desc = full[i + 1] + (on ? ", ativo" : "");
        key.setContentDescription(desc);
        TextView pill = key.findViewWithTag("pill");
        pill.setText(badge == null ? "" : badge);
        pill.setVisibility(badge == null ? View.GONE : View.VISIBLE);
        boolean usable = connected && (!fn.equals("pause") || recording);
        key.setAlpha(usable ? 1f : 0.45f);
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private void showIconPicker(String sceneName) {
        Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean land = isLandscape();
        LinearLayout box = sheet();

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(sheetTitles("ÍCONE DA CENA", sceneName), new LinearLayout.LayoutParams(0, -2, 1f));
        Button auto = smallButton("Automático");
        auto.setOnClickListener(v -> {
            prefs.edit().remove("icon:" + sceneName).apply();
            dlg.dismiss();
            renderScenes(0);
        });
        head.addView(auto, new LinearLayout.LayoutParams(-2, dp(44)));
        box.addView(head);

        String current = iconKeyFor(sceneName);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(land ? 8 : 4);
        for (int i = 0; i < SceneIcons.KEYS.length; i++) {
            String key = SceneIcons.KEYS[i];
            boolean sel = key.equals(current);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setClickable(true);
            cell.setContentDescription(SceneIcons.LABELS[i]);
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(dp(14));
            cb.setColor(SceneIcons.COLORS[i]);
            if (sel) cb.setStroke(dp(3), Color.WHITE);
            cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), cb, null));
            ImageView iv = new ImageView(this);
            iv.setImageResource(SceneIcons.RES[i]);
            iv.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            cell.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView tl = new TextView(this);
            tl.setText(SceneIcons.LABELS[i]);
            tl.setTextColor(Color.WHITE);
            tl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tl.setGravity(Gravity.CENTER);
            tl.setPadding(0, dp(4), 0, 0);
            cell.addView(tl);
            cell.setOnClickListener(v -> {
                prefs.edit().putString("icon:" + sceneName, key).apply();
                dlg.dismiss();
                renderScenes(0);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(land ? 64 : 72);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(cell, lp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(12);
        box.addView(grid, glp);
        showSheet(dlg, box);
    }

    // ================================================================ Áudio: faixas da cena no ar

    /** Faixas mostradas = fontes de áudio da cena no ar, na ordem do OBS (áudio global primeiro). */
    private List<ObsClient.AudioInput> visibleInputs() {
        List<ObsClient.AudioInput> out = new ArrayList<>();
        if (sceneAudio == null) return out;
        Set<String> hidden = hiddenAudio();
        for (String n : sceneAudio) {
            ObsClient.AudioInput in = input(n);
            if (in != null && !hidden.contains(n)) out.add(in);
        }
        return out;
    }

    /** Faixas que o usuário ocultou no app (o OBS não informa o "Ocultar" do mixer pelo WebSocket). */
    private Set<String> hiddenAudio() {
        return new HashSet<>(prefs.getStringSet("hiddenAudio", new HashSet<>()));
    }

    private void setAudioHidden(String name, boolean hide) {
        Set<String> h = hiddenAudio();
        if (hide) h.add(name);
        else h.remove(name);
        prefs.edit().putStringSet("hiddenAudio", h).apply();
        renderAudio();
        updateResetHidden();
    }

    private void askHideAudio(String name) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("Ocultar a faixa “" + name + "” no app?\n\nPara mostrar de novo: Conexão → Faixas ocultas no app.")
                .setPositiveButton("Ocultar", (d, w) -> {
                    if (audioDialog != null) audioDialog.dismiss();
                    setAudioHidden(name, true);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Lista as faixas ocultas; desmarcar uma volta a mostrá-la. */
    private void showHiddenList() {
        List<String> names = new ArrayList<>(hiddenAudio());
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) return;
        String[] items = names.toArray(new String[0]);
        boolean[] checked = new boolean[items.length];
        java.util.Arrays.fill(checked, true);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Faixas ocultas no app")
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Salvar", (d, w) -> {
                    Set<String> keep = new HashSet<>();
                    for (int i = 0; i < items.length; i++) if (checked[i]) keep.add(items[i]);
                    prefs.edit().putStringSet("hiddenAudio", keep).apply();
                    renderAudio();
                    updateResetHidden();
                })
                .setNeutralButton("Mostrar todas", (d, w) -> {
                    prefs.edit().remove("hiddenAudio").apply();
                    renderAudio();
                    updateResetHidden();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updateResetHidden() {
        Button b = findViewById(R.id.resetHiddenBtn);
        if (b == null) return;
        int n = hiddenAudio().size();
        b.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        b.setText("Faixas ocultas no app (" + n + ")");
    }

    private void renderAudio() {
        stopBlinks();
        audioBox.removeAllViews();
        chips.clear();
        List<ObsClient.AudioInput> list = visibleInputs();
        audioEmpty.setVisibility(connected && sceneAudio != null && list.isEmpty() ? View.VISIBLE : View.GONE);
        boolean land = isLandscape();
        // Fonte grande do sistema: menos faixas por linha para o nome caber.
        boolean bigText = getResources().getConfiguration().fontScale > 1.15f;
        // Faixas ocupam a linha inteira quando são poucas (mais espaço para o nome).
        int perRow = land ? Math.max(1, Math.min(bigText ? 4 : 5, list.size())) : 2;
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                audioBox.addView(row, new LinearLayout.LayoutParams(-1, dp(68)));
            }
            ObsClient.AudioInput in = list.get(i);
            Chip c = makeChip(in.name, in.volumeMul);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1f);
            lp.setMargins(dp(4), dp(3), dp(4), dp(3));
            row.addView(c.chip, lp);
            chips.put(in.name, c);
            paintChip(c, in.name, in.muted, in.volumeMul);
        }
        if (row != null) {
            for (int k = list.size() % perRow; k != 0 && k < perRow; k++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        // Horizontal mostra até 2 linhas de faixas, retrato até 3; acima disso rola dentro do painel.
        audioBox.setAlpha(stale ? 0.35f : 1f);
        int rows = (list.size() + perRow - 1) / perRow;
        int maxRows = land ? 2 : 3;
        ViewGroup.LayoutParams lp = audioScroll.getLayoutParams();
        lp.height = dp(68) * Math.max(1, Math.min(rows, maxRows));
        audioScroll.setLayoutParams(lp);
    }

    /**
     * Faixa: botão de mudo + nome/dB. O corpo inteiro da faixa é o controle de volume:
     * arraste para os lados (a partir do volume atual, sem "pular"), toque para mais opções,
     * segure para ocultar. O preenchimento mostra o volume e a linha de baixo o nível do som.
     */
    private Chip makeChip(String name, double mul) {
        Chip c = new Chip();
        FrameLayout chip = new FrameLayout(this);
        c.chip = chip;

        c.fader = new FaderView(this, false);
        c.fader.setStripStyle(1);
        c.fader.setPosition(toPos(mul));
        c.fader.setContentDescription("Volume de " + name + ". Arraste para os lados para ajustar, toque para opções.");
        c.fader.setListener(faderListener(name, c.fader, () -> showAudioDialog(name), () -> askHideAudio(name)));
        chip.addView(c.fader, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(7), 0, dp(10), dp(4));

        c.mute = new ImageButton(this);
        c.mute.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.mute.setScaleType(ImageView.ScaleType.FIT_CENTER);
        c.mute.setOnClickListener(v -> {
            if (!connected) {
                Toast.makeText(this, "Sem conexão com o OBS", Toast.LENGTH_SHORT).show();
                return;
            }
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            obs.toggleMute(name);
        });
        row.addView(c.mute, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, 0, 0);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(cText);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_more);
        more.setImageTintList(ColorStateList.valueOf(cMuted));
        more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(more, new LinearLayout.LayoutParams(dp(18), dp(18)));
        col.addView(line, new LinearLayout.LayoutParams(-1, -2));
        c.db = new TextView(this);
        c.db.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c.db.setFontFeatureSettings("tnum");
        c.db.setMaxLines(1);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(2);
        col.addView(c.db, dlp);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        // O conteúdo não intercepta toques (só o botão de mudo); o resto cai no fader embaixo.
        chip.addView(row, new FrameLayout.LayoutParams(-1, -1));
        return c;
    }

    /** Liga um fader ao volume de uma fonte: atualiza a tela na hora e envia ao OBS com limite de taxa. */
    private FaderView.Listener faderListener(String name, FaderView self, Runnable onTap, Runnable onLong) {
        return new FaderView.Listener() {
            @Override
            public void onStart() {
                if (!connected) return;
                draggingInput = name;
            }

            @Override
            public void onChange(double pos) {
                if (!connected) return;
                double mul = fromPos(pos);
                setLocalVolume(name, mul, self);
                long now = SystemClock.uptimeMillis();
                if (now - lastVolumeSend >= VOLUME_SEND_INTERVAL_MS) {
                    lastVolumeSend = now;
                    sendVolume(name, mul);
                }
            }

            @Override
            public void onEnd(double pos) {
                draggingInput = null;
                if (!connected) {
                    Toast.makeText(MainActivity.this, "Sem conexão com o OBS", Toast.LENGTH_SHORT).show();
                    setLocalVolume(name, volumeOf(name), null);
                    return;
                }
                double mul = fromPos(pos);
                setLocalVolume(name, mul, self);
                sendVolume(name, mul);
            }

            @Override
            public void onTap() {
                if (onTap != null) onTap.run();
            }

            @Override
            public void onLongPress() {
                if (onLong != null) onLong.run();
            }
        };
    }

    private void sendVolume(String name, double mul) {
        localVolumeAt.put(name, SystemClock.uptimeMillis());
        lastSentVolume.put(name, mul);
        obs.setVolume(name, mul);
    }

    /** Passo fino de volume em dB (botões − / +). */
    private void stepDb(String name, double deltaDb) {
        if (!connected) return;
        double mul = volumeOf(name);
        double db = mul <= 0.00001 ? -60 : 20 * Math.log10(mul);
        db = Math.min(0, Math.max(-60, Math.round(db) + deltaDb));
        double nm = db <= -60 ? 0 : Math.pow(10, db / 20);
        setLocalVolume(name, nm, null);
        sendVolume(name, nm);
    }

    /** Microfone mutado enquanto está ao vivo ou gravando: a faixa pisca em vermelho. */
    private boolean micAlert(String name, boolean muted) {
        ObsClient.AudioInput in = input(name);
        return in != null && in.isMic && muted && (streaming || recording);
    }

    private void paintChip(Chip c, String name, boolean muted, double mul) {
        boolean alert = micAlert(name, muted);
        int fill = muted ? 0x225A5E66 : 0x2E4C9EFF;
        int mark = muted ? 0xFF6B7079 : cAccent;
        if (alert) {
            c.fader.setColors(0xFF2A1416, 0xFFFF5A60, 2, 0x22FF5A60, 0xFFFF8A8E);
            if (c.blink == null) {
                c.blink = ValueAnimator.ofObject(new ArgbEvaluator(), 0xFF2A1416, 0xFF8E1F25);
                c.blink.setDuration(550);
                c.blink.setRepeatMode(ValueAnimator.REVERSE);
                c.blink.setRepeatCount(ValueAnimator.INFINITE);
                c.blink.addUpdateListener(a -> c.fader.setBgColor((int) a.getAnimatedValue()));
                c.blink.start();
            }
        } else {
            if (c.blink != null) {
                c.blink.cancel();
                c.blink = null;
            }
            c.fader.setColors(muted ? 0xFF241C10 : cSurface, muted ? 0xFF5A4318 : 0xFF262930, 1, fill, mark);
        }
        c.fader.setPosition(toPos(mul));
        c.mute.setImageResource(muted ? R.drawable.ic_vol_off : R.drawable.ic_vol_on);
        c.mute.setImageTintList(ColorStateList.valueOf(alert ? Color.WHITE : muted ? cMutedFg : cAccent));
        c.mute.setBackground(bg(alert ? 0xFFC8262C : muted ? cMutedBg : cAccentBg, 10));
        c.mute.setContentDescription(muted ? "Ativar áudio de " + name : "Mutar " + name);
        boolean drag = name.equals(draggingInput);
        c.db.setText(alert ? "MUTADO!" : muted ? "mudo · " + formatDb(mul) : formatDb(mul));
        c.db.setTextColor(alert ? 0xFFFF8A8E : muted ? cMutedFg : drag ? cAccent : cMuted);
        c.db.setTypeface(muted || drag ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void repaintChips() {
        for (Map.Entry<String, Chip> e : chips.entrySet()) {
            paintChip(e.getValue(), e.getKey(), mutedOf(e.getKey()), volumeOf(e.getKey()));
        }
        for (String n : mixCols.keySet()) paintMixCol(n);
    }

    private void stopBlinks() {
        for (Chip c : chips.values()) {
            if (c.blink != null) {
                c.blink.cancel();
                c.blink = null;
            }
        }
    }

    // ================================================================ Janela da faixa

    private void showAudioDialog(String name) {
        if (audioDialog != null) audioDialog.dismiss();
        Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        audioDialog = dlg;
        dialogInput = name;
        dlg.setOnDismissListener(d -> {
            if (audioDialog == dlg) {
                audioDialog = null;
                dialogInput = null;
            }
        });

        LinearLayout box = sheet();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(sheetTitles("FAIXA DE ÁUDIO", name), new LinearLayout.LayoutParams(0, -2, 1f));
        dialogDb = new TextView(this);
        dialogDb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        dialogDb.setTypeface(Typeface.DEFAULT_BOLD);
        dialogDb.setFontFeatureSettings("tnum");
        dialogDb.setPadding(dp(10), dp(6), dp(10), dp(6));
        dialogDb.setBackground(bg(0xFF22252B, 10));
        dialogDb.setContentDescription("Digitar valor exato em dB");
        dialogDb.setOnClickListener(v -> askExactDb(name));
        LinearLayout.LayoutParams dbl = new LinearLayout.LayoutParams(-2, dp(48));
        dbl.setMarginEnd(dp(10));
        dialogDb.setGravity(Gravity.CENTER);
        head.addView(dialogDb, dbl);
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(cText));
        close.setBackground(bg(0xFF22252B, 12));
        close.setContentDescription("Fechar");
        close.setOnClickListener(v -> dlg.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        box.addView(head);

        dialogFader = new FaderView(this, false);
        dialogFader.setRadiusDp(16);
        dialogFader.setPosition(toPos(volumeOf(name)));
        dialogFader.setContentDescription("Volume de " + name);
        dialogFader.setListener(faderListener(name, dialogFader, null, null));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, dp(64));
        flp.topMargin = dp(16);
        box.addView(dialogFader, flp);

        LinearLayout steps = new LinearLayout(this);
        steps.addView(stepButton("−1 dB", () -> stepDb(name, -1)), new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        sp.setMarginStart(dp(8));
        steps.addView(stepButton("+1 dB", () -> stepDb(name, 1)), sp);
        LinearLayout.LayoutParams zp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        zp.setMarginStart(dp(8));
        steps.addView(stepButton("0 dB", () -> {
            if (!connected) return;
            setLocalVolume(name, 1.0, null);
            sendVolume(name, 1.0);
        }), zp);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = dp(10);
        box.addView(steps, stp);

        dialogMute = new Button(this);
        dialogMute.setAllCaps(false);
        dialogMute.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        dialogMute.setTypeface(Typeface.DEFAULT_BOLD);
        dialogMute.setStateListAnimator(null);
        dialogMute.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            obs.toggleMute(name);
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(52));
        mlp.topMargin = dp(10);
        box.addView(dialogMute, mlp);

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        Button mixer = smallButton("Abrir mixer");
        mixer.setOnClickListener(v -> {
            dlg.dismiss();
            showMixer();
        });
        foot.addView(mixer, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button hide = smallButton("Ocultar no app");
        hide.setTextColor(cMuted);
        hide.setOnClickListener(v -> askHideAudio(name));
        LinearLayout.LayoutParams hbp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        hbp.setMarginStart(dp(8));
        foot.addView(hide, hbp);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(10);
        box.addView(foot, fp);

        paintDialog();
        showSheet(dlg, box);
    }

    /** Digitar o volume exato em dB (ex.: -9,7). */
    private void askExactDb(String name) {
        if (!connected) return;
        EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789-−,."));
        double cur = volumeOf(name);
        et.setText(cur <= 0.00001 ? "-60" : String.format(new Locale("pt", "BR"), "%.1f", 20 * Math.log10(cur)));
        et.setSelectAllOnFocus(true);
        et.setTextColor(cText);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(et);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Volume de " + name + " (dB)")
                .setMessage("De -60 a 0. Ex.: -9,7")
                .setView(wrap)
                .setPositiveButton("Aplicar", (d, w) -> {
                    try {
                        double db = Double.parseDouble(et.getText().toString().trim().replace('−', '-').replace(',', '.'));
                        db = Math.max(-60, Math.min(0, db));
                        double mul = db <= -60 ? 0 : Math.pow(10, db / 20);
                        setLocalVolume(name, mul, null);
                        sendVolume(name, mul);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
        et.requestFocus();
    }

    private Button stepButton(String text, Runnable action) {
        Button b = smallButton(text);
        b.setFontFeatureSettings("tnum");
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            action.run();
        });
        return b;
    }

    /** Atualiza a tela na hora (faixa, janela e mixer) sem esperar o OBS. source = fader que o usuário está arrastando. */
    private void setLocalVolume(String name, double mul, FaderView source) {
        ObsClient.AudioInput in = input(name);
        if (in != null) in.volumeMul = mul;
        Chip c = chips.get(name);
        if (c != null) paintChip(c, name, mutedOf(name), mul);
        if (name.equals(dialogInput)) {
            if (dialogFader != source) dialogFader.setPosition(toPos(mul));
            paintDialog();
        }
        paintMixCol(name);
    }

    private void paintDialog() {
        if (dialogInput == null) return;
        boolean muted = mutedOf(dialogInput);
        double mul = volumeOf(dialogInput);
        dialogDb.setText(formatDb(mul));
        dialogDb.setTextColor(muted ? cMutedFg : cText);
        dialogMute.setText(muted ? "Ativar áudio" : "Mutar");
        dialogMute.setTextColor(muted ? 0xFF0E0F12 : cMutedFg);
        dialogMute.setBackground(bg(muted ? cAccent : cMutedBg, 14));
        dialogFader.setColors(0xFF22252B, 0xFF2C3038, 1, muted ? 0x335A5E66 : 0x404C9EFF, muted ? 0xFF8A8E96 : cAccent);
        dialogFader.setPosition(toPos(mul));
    }

    // ================================================================ Mixer (tela cheia, faders verticais)

    private static final class MixCol {
        FaderView fader;
        TextView db;
        ImageButton mute;
    }

    private void showMixer() {
        if (mixerDialog != null) mixerDialog.dismiss();
        List<ObsClient.AudioInput> list = visibleInputs();
        if (list.isEmpty()) {
            Toast.makeText(this, "Nenhuma fonte de áudio nesta cena", Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dlg = new Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        mixerDialog = dlg;
        mixCols.clear();
        dlg.setOnDismissListener(d -> {
            if (mixerDialog == dlg) {
                mixerDialog = null;
                mixCols.clear();
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(4), dp(8), dp(4));
        TextView title = new TextView(this);
        title.setText("Mixer");
        title.setTextColor(cText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title);
        TextView sub = new TextView(this);
        sub.setText("cena " + currentScene + " · arraste os faders para cima ou para baixo");
        sub.setTextColor(cMuted);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(dp(12), 0, 0, 0);
        bar.addView(sub, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(cText));
        close.setBackground(bg(0xFF22252B, 12));
        close.setContentDescription("Fechar mixer");
        close.setOnClickListener(v -> dlg.dismiss());
        bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setFillViewport(true);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout cols = new LinearLayout(this);
        cols.setPadding(dp(8), 0, dp(8), dp(12));
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int colW = Math.max(dp(92), Math.min(dp(150), (screenW - dp(16)) / list.size()));
        for (ObsClient.AudioInput in : list) {
            cols.addView(makeMixCol(in.name), new LinearLayout.LayoutParams(colW, -1));
        }
        hs.addView(cols, new FrameLayout.LayoutParams(-2, -1));
        root.addView(hs, new LinearLayout.LayoutParams(-1, 0, 1f));

        dlg.setContentView(root);
        dlg.show();
        for (String n : mixCols.keySet()) paintMixCol(n);
    }

    private View makeMixCol(String name) {
        MixCol mc = new MixCol();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(6), dp(8), dp(6), dp(8));
        GradientDrawable cb = new GradientDrawable();
        cb.setCornerRadius(dp(18));
        cb.setColor(cSurface);
        cb.setStroke(dp(1), 0xFF262930);
        LinearLayout inner = col;
        inner.setBackground(cb);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(cText);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(label, new LinearLayout.LayoutParams(-1, -2));
        mc.db = new TextView(this);
        mc.db.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mc.db.setFontFeatureSettings("tnum");
        mc.db.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(2);
        dlp.bottomMargin = dp(6);
        col.addView(mc.db, dlp);

        mc.fader = new FaderView(this, true);
        mc.fader.setRadiusDp(16);
        mc.fader.setPosition(toPos(volumeOf(name)));
        mc.fader.setContentDescription("Volume de " + name + ". Arraste para cima ou para baixo.");
        mc.fader.setListener(faderListener(name, mc.fader, null, null));
        col.addView(mc.fader, new LinearLayout.LayoutParams(dp(64), 0, 1f));

        LinearLayout steps = new LinearLayout(this);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(44), 1f);
        steps.addView(stepButton("−", () -> stepDb(name, -1)), a);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(44), 1f);
        b.setMarginStart(dp(6));
        steps.addView(stepButton("+", () -> stepDb(name, 1)), b);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(8);
        col.addView(steps, slp);

        mc.mute = new ImageButton(this);
        mc.mute.setPadding(dp(12), dp(12), dp(12), dp(12));
        mc.mute.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mc.mute.setOnClickListener(v -> {
            if (!connected) return;
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            obs.toggleMute(name);
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(48));
        mp.topMargin = dp(6);
        col.addView(mc.mute, mp);

        mixCols.put(name, mc);
        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-1, -1);
        wl.setMargins(dp(4), 0, dp(4), 0);
        wrap.addView(col, wl);
        return wrap;
    }

    private void paintMixCol(String name) {
        MixCol mc = mixCols.get(name);
        if (mc == null) return;
        boolean muted = mutedOf(name);
        boolean alert = micAlert(name, muted);
        double mul = volumeOf(name);
        mc.fader.setColors(0xFF22252B, alert ? 0xFFFF5A60 : 0xFF2C3038, alert ? 2 : 1,
                muted ? 0x335A5E66 : 0x404C9EFF, muted ? 0xFF8A8E96 : cAccent);
        mc.fader.setPosition(toPos(mul));
        mc.db.setText(alert ? "MUTADO!" : muted ? "mudo · " + formatDb(mul) : formatDb(mul));
        mc.db.setTextColor(alert ? 0xFFFF8A8E : muted ? cMutedFg : cText);
        mc.mute.setImageResource(muted ? R.drawable.ic_vol_off : R.drawable.ic_vol_on);
        mc.mute.setImageTintList(ColorStateList.valueOf(alert ? Color.WHITE : muted ? cMutedFg : cAccent));
        mc.mute.setBackground(bg(alert ? 0xFFC8262C : muted ? cMutedBg : cAccentBg, 12));
        mc.mute.setContentDescription(muted ? "Ativar áudio de " + name : "Mutar " + name);
    }

    private ObsClient.AudioInput input(String name) {
        for (ObsClient.AudioInput in : inputs) if (in.name.equals(name)) return in;
        return null;
    }

    private boolean mutedOf(String name) {
        ObsClient.AudioInput in = input(name);
        return in != null && in.muted;
    }

    private double volumeOf(String name) {
        ObsClient.AudioInput in = input(name);
        return in == null ? 1.0 : in.volumeMul;
    }

    /*
     * Mesma curva do fader do OBS (modo logarítmico de obs_fader), para a posição no app
     * bater com a posição no mixer do OBS: faixa de −96 dB a 0 dB com deslocamento de 6 dB.
     */
    private static final double LOG_OFFSET_DB = 6.0, LOG_RANGE_DB = 96.0;
    private static final double LOG_OFFSET_VAL = -Math.log10(LOG_OFFSET_DB);
    private static final double LOG_RANGE_VAL = -Math.log10(LOG_RANGE_DB + LOG_OFFSET_DB);

    private static double toPos(double mul) {
        if (mul <= 0) return 0;
        double db = 20 * Math.log10(mul);
        if (db >= 0) return 1;
        if (db <= -LOG_RANGE_DB) return 0;
        return (-Math.log10(-db + LOG_OFFSET_DB) - LOG_RANGE_VAL) / (LOG_OFFSET_VAL - LOG_RANGE_VAL);
    }

    private static double fromPos(double p) {
        if (p >= 1) return 1;
        if (p <= 0) return 0;
        double db = -(LOG_RANGE_DB + LOG_OFFSET_DB) * Math.pow((LOG_RANGE_DB + LOG_OFFSET_DB) / LOG_OFFSET_DB, -p) + LOG_OFFSET_DB;
        return Math.pow(10, db / 20);
    }

    private static String formatDb(double mul) {
        if (mul <= 0.00001) return "−∞ dB";
        double db = 20 * Math.log10(mul);
        if (db > -0.05) return "0 dB";
        return String.format(new Locale("pt", "BR"), "%.1f dB", db).replace('-', '−');
    }

    // ================================================================ Janelas (bottom sheet)

    private LinearLayout sheet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(20));
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(cSurface);
        sheet.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        box.setBackground(sheet);
        View handle = new View(this);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xFF3A3E46);
        hb.setCornerRadius(dp(2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(12);
        box.addView(handle, hlp);
        return box;
    }

    private LinearLayout sheetTitles(String caption, String title) {
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView cap = new TextView(this);
        cap.setText(caption);
        cap.setTextColor(cMuted);
        cap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cap.setTypeface(Typeface.DEFAULT_BOLD);
        cap.setLetterSpacing(0.08f);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(cText);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(cap);
        titles.addView(t);
        return titles;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(cText);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setStateListAnimator(null);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackground(bg(0xFF22252B, 12));
        return b;
    }

    private void showSheet(Dialog dlg, View content) {
        dlg.setContentView(content);
        Window w = dlg.getWindow();
        if (w != null) {
            boolean land = isLandscape();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(land ? dp(640) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (land) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        dlg.show();
    }

    // ================================================================ Gestos

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (sceneGrid != null && settingsOverlay.getVisibility() != View.VISIBLE) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                sceneGrid.getGlobalVisibleRect(gridRect);
                touchInGrid = gridRect.contains((int) ev.getRawX(), (int) ev.getRawY());
            }
            if (touchInGrid && swipe.onTouchEvent(ev)) {
                // Foi um deslize: cancela o toque para a tecla não ser acionada.
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                touchInGrid = false;
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // ================================================================ UI util

    private void setStatus(int color, String title, String detail) {
        statusColor = color;
        statusTitle = title;
        statusDetail = detail == null ? "" : detail;
        renderStatus();
    }

    private void renderStatus() {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(statusColor);
        statusDot.setBackground(dot);
        statusText.setText(statusTitle);
        statusSub.setText(statusDetail);
        statusSub.setVisibility(statusDetail.isEmpty() ? View.GONE : View.VISIBLE);
        settingsStatus.setText("●  " + statusTitle + (statusDetail.isEmpty() ? "" : " · " + statusDetail));
        int sc = statusColor == cLive ? cLive : statusColor == cError ? cError : statusColor == cWarn ? cWarn : cMuted;
        settingsStatus.setTextColor(sc);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(99));
        pill.setColor((sc & 0x00FFFFFF) | 0x22000000);
        settingsStatus.setBackground(pill);
        closeSettings.setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
        paintFnKeys();
    }

    private void updateConnectButton() {
        connectBtn.setText(wantConnected ? "Desconectar" : "Conectar");
        connectBtn.setTextColor(wantConnected ? 0xFFFF8A8E : Color.WHITE);
        connectBtn.setBackground(bg(wantConnected ? 0xFF3A1A1C : 0xFF2E333D, 14));
        closeSettings.setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
    }

    private void applyKeepScreenOn() {
        if (keepOn.isChecked()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
            f.clearFocus();
        }
    }

    private Drawable bg(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
