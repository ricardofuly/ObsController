package com.obscontroller;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Cliente mínimo do protocolo obs-websocket v5 (embutido no OBS 28+).
 * Todos os callbacks do Listener são entregues na thread principal.
 */
public final class ObsClient extends WebSocketListener {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    public interface Listener {
        void onState(State state, String message, boolean canRetry);
        void onScenes(List<String> scenes, String current);
        void onCurrentScene(String name);
        void onAudioInputs(List<AudioInput> inputs);
        void onInputMute(String name, boolean muted);
        void onInputVolume(String name, double volumeMul);
        /** key: "stream", "record", "pause", "vcam", "replay". durationMs: tempo já decorrido (-1 se desconhecido). */
        void onOutput(String key, boolean active, long durationMs);
        void onReplaySaved();
        /** Fontes que tocam na cena no ar: áudio global (Desktop/Mic) + itens visíveis da cena (inclui grupos e cenas aninhadas). */
        void onSceneAudio(String scene, List<String> sourceNames);
        /** Nível de pico atual (0..1, linear) de cada fonte de áudio, ~20 vezes por segundo. */
        void onVolumeMeters(Map<String, Float> peaks);
        void onRequestError(String message);
    }

    public static final class AudioInput {
        public final String name;
        public boolean muted;
        /** Captura de entrada de áudio (microfone): wasapi/coreaudio/pulse/alsa_input_capture. */
        public boolean isMic;
        public double volumeMul = 1.0;

        AudioInput(String name) {
            this.name = name;
        }
    }

    private interface Callback {
        void done(boolean ok, JSONObject data, String comment) throws JSONException;
    }

    private static final int OP_HELLO = 0, OP_IDENTIFY = 1, OP_IDENTIFIED = 2,
            OP_EVENT = 5, OP_REQUEST = 6, OP_RESPONSE = 7;
    private static final int SUB_SCENES = 1 << 2, SUB_INPUTS = 1 << 3, SUB_OUTPUTS = 1 << 6, SUB_SCENE_ITEMS = 1 << 7, SUB_VOLUME_METERS = 1 << 16;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS) // detecta Wi-Fi caído rapidamente
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Callback> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private final Listener listener;

    private volatile WebSocket ws;
    private volatile boolean identified;
    private volatile int inputsGeneration;
    private String password = "";
    private volatile String programScene = "";
    private volatile int sceneAudioGeneration;

    public ObsClient(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return ws != null && identified;
    }

    public void connect(String host, int port, String password) {
        closeSocket();
        this.password = password == null ? "" : password;
        post(() -> listener.onState(State.CONNECTING, host + ":" + port, true));
        Request req = new Request.Builder().url("ws://" + host + ":" + port).build();
        ws = http.newWebSocket(req, this);
    }

    public void disconnect() {
        if (closeSocket()) {
            post(() -> listener.onState(State.DISCONNECTED, "Desconectado", true));
        }
    }

    private boolean closeSocket() {
        WebSocket w = ws;
        ws = null;
        identified = false;
        pending.clear();
        if (w == null) return false;
        w.close(1000, null);
        return true;
    }

    public void setScene(String sceneName) {
        request("SetCurrentProgramScene", obj("sceneName", sceneName), this::reportError);
    }

    public void toggleMute(String inputName) {
        request("ToggleInputMute", obj("inputName", inputName), this::reportError);
    }

    /** Volume linear (0..1, como o "inputVolumeMul" do OBS). */
    public void setVolume(String inputName, double volumeMul) {
        JSONObject d = obj("inputName", inputName);
        try {
            d.put("inputVolumeMul", Math.max(0, Math.min(1, volumeMul)));
        } catch (JSONException ignored) {
        }
        request("SetInputVolume", d, this::reportError);
    }

    // ---------------------------------------------------------------- Saídas (botões de função)

    public void toggleStream() { request("ToggleStream", null, this::reportError); }
    public void toggleRecord() { request("ToggleRecord", null, this::reportError); }
    public void toggleRecordPause() { request("ToggleRecordPause", null, this::reportError); }
    public void toggleVirtualCam() { request("ToggleVirtualCam", null, this::reportError); }
    public void startReplayBuffer() { request("StartReplayBuffer", null, this::reportError); }
    public void saveReplayBuffer() { request("SaveReplayBuffer", null, this::reportError); }

    private void refreshOutputs() {
        request("GetStreamStatus", null, (ok, d, c) -> {
            if (ok) output("stream", d.optBoolean("outputActive"), d.optLong("outputDuration", -1));
        });
        request("GetRecordStatus", null, (ok, d, c) -> {
            if (!ok) return;
            output("record", d.optBoolean("outputActive"), d.optLong("outputDuration", -1));
            output("pause", d.optBoolean("outputPaused"), -1);
        });
        request("GetVirtualCamStatus", null, (ok, d, c) -> {
            if (ok) output("vcam", d.optBoolean("outputActive"), -1);
        });
        request("GetReplayBufferStatus", null, (ok, d, c) -> {
            if (ok) output("replay", d.optBoolean("outputActive"), -1);
        });
    }

    private void output(String key, boolean active, long durationMs) {
        post(() -> listener.onOutput(key, active, durationMs));
    }

    public void refreshAll() {
        refreshOutputs();
        refreshScenes();
        refreshInputs();
    }

    // ---------------------------------------------------------------- WebSocket

    @Override
    public void onMessage(WebSocket socket, String text) {
        if (socket != ws) return;
        try {
            JSONObject msg = new JSONObject(text);
            JSONObject d = msg.optJSONObject("d");
            if (d == null) d = new JSONObject();
            switch (msg.optInt("op", -1)) {
                case OP_HELLO: sendIdentify(socket, d); break;
                case OP_IDENTIFIED:
                    identified = true;
                    post(() -> listener.onState(State.CONNECTED, "Conectado", true));
                    refreshAll();
                    break;
                case OP_EVENT: handleEvent(d); break;
                case OP_RESPONSE: handleResponse(d); break;
                default: break;
            }
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onClosing(WebSocket socket, int code, String reason) {
        socket.close(1000, null);
        if (socket != ws) return;
        ws = null;
        identified = false;
        pending.clear();
        String msg;
        boolean retry = true;
        switch (code) {
            case 4009: msg = "Senha incorreta"; retry = false; break;
            case 4010: msg = "Versão do OBS WebSocket não suportada (use OBS 28+)"; retry = false; break;
            default: msg = (reason == null || reason.isEmpty()) ? "Conexão encerrada pelo OBS" : reason;
        }
        final String m = msg;
        final boolean r = retry;
        post(() -> listener.onState(State.DISCONNECTED, m, r));
    }

    @Override
    public void onFailure(WebSocket socket, Throwable t, Response response) {
        if (socket != ws) return;
        ws = null;
        identified = false;
        pending.clear();
        String detail;
        if (t instanceof java.net.SocketTimeoutException) detail = "o OBS não respondeu";
        else if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                || t instanceof java.net.NoRouteToHostException) detail = "OBS fora do alcance — confira o Wi-Fi e o IP";
        else if (t instanceof java.io.EOFException || t instanceof java.net.SocketException) detail = "a conexão caiu";
        else detail = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        post(() -> listener.onState(State.DISCONNECTED, "Sem conexão: " + detail, true));
    }

    // ---------------------------------------------------------------- Protocolo

    private void sendIdentify(WebSocket socket, JSONObject hello) throws JSONException {
        JSONObject d = new JSONObject()
                .put("rpcVersion", 1)
                .put("eventSubscriptions", SUB_SCENES | SUB_INPUTS | SUB_OUTPUTS | SUB_SCENE_ITEMS | SUB_VOLUME_METERS);
        JSONObject auth = hello.optJSONObject("authentication");
        if (auth != null) {
            String secret = sha256b64(password + auth.optString("salt"));
            d.put("authentication", sha256b64(secret + auth.optString("challenge")));
        }
        socket.send(new JSONObject().put("op", OP_IDENTIFY).put("d", d).toString());
    }

    private void handleEvent(JSONObject d) {
        JSONObject e = d.optJSONObject("eventData");
        if (e == null) e = new JSONObject();
        switch (d.optString("eventType")) {
            case "CurrentProgramSceneChanged": {
                String name = e.optString("sceneName");
                post(() -> listener.onCurrentScene(name));
                refreshSceneAudio(name);
                break;
            }
            case "SceneItemCreated":
            case "SceneItemRemoved":
            case "SceneItemEnableStateChanged":
                refreshSceneAudio(programScene);
                break;
            case "SceneListChanged":
            case "SceneNameChanged":
                refreshScenes();
                break;
            case "InputMuteStateChanged": {
                String name = e.optString("inputName");
                boolean muted = e.optBoolean("inputMuted");
                post(() -> listener.onInputMute(name, muted));
                break;
            }
            case "InputVolumeMeters": {
                JSONArray arr = e.optJSONArray("inputs");
                if (arr == null) break;
                Map<String, Float> peaks = new java.util.HashMap<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject in = arr.optJSONObject(i);
                    if (in == null) continue;
                    JSONArray ch = in.optJSONArray("inputLevelsMul");
                    float peak = 0;
                    if (ch != null) {
                        for (int c = 0; c < ch.length(); c++) {
                            JSONArray v = ch.optJSONArray(c);
                            if (v != null && v.length() > 1) peak = Math.max(peak, (float) v.optDouble(1, 0));
                        }
                    }
                    peaks.put(in.optString("inputName"), peak);
                }
                post(() -> listener.onVolumeMeters(peaks));
                break;
            }
            case "InputVolumeChanged": {
                String name = e.optString("inputName");
                double mul = e.optDouble("inputVolumeMul", 1.0);
                post(() -> listener.onInputVolume(name, mul));
                break;
            }
            case "StreamStateChanged":
                output("stream", e.optBoolean("outputActive"), 0);
                break;
            case "RecordStateChanged": {
                String st = e.optString("outputState");
                if (st.endsWith("_PAUSED")) output("pause", true, -1);
                else if (st.endsWith("_RESUMED")) output("pause", false, -1);
                else {
                    output("record", e.optBoolean("outputActive"), 0);
                    if (!e.optBoolean("outputActive")) output("pause", false, -1);
                }
                break;
            }
            case "VirtualcamStateChanged":
                output("vcam", e.optBoolean("outputActive"), -1);
                break;
            case "ReplayBufferStateChanged":
                output("replay", e.optBoolean("outputActive"), -1);
                break;
            case "ReplayBufferSaved":
                post(listener::onReplaySaved);
                break;
            case "InputCreated":
            case "InputRemoved":
            case "InputNameChanged":
                refreshInputs();
                break;
            default: break;
        }
    }

    private void handleResponse(JSONObject d) throws JSONException {
        Callback cb = pending.remove(d.optString("requestId"));
        if (cb == null) return;
        JSONObject status = d.optJSONObject("requestStatus");
        boolean ok = status != null && status.optBoolean("result");
        String comment = status == null ? "" : status.optString("comment", "");
        JSONObject data = d.optJSONObject("responseData");
        cb.done(ok, data == null ? new JSONObject() : data, comment);
    }

    private void request(String type, JSONObject data, Callback cb) {
        WebSocket w = ws;
        if (w == null || !identified) return;
        String id = Integer.toString(nextId.incrementAndGet());
        try {
            JSONObject d = new JSONObject().put("requestType", type).put("requestId", id);
            if (data != null) d.put("requestData", data);
            if (cb != null) pending.put(id, cb);
            w.send(new JSONObject().put("op", OP_REQUEST).put("d", d).toString());
        } catch (JSONException ignored) {
        }
    }

    private void refreshScenes() {
        request("GetSceneList", null, (ok, d, c) -> {
            if (!ok) return;
            JSONArray arr = d.optJSONArray("scenes");
            List<String> names = new ArrayList<>();
            // O OBS devolve a lista invertida em relação à interface; desinverte.
            if (arr != null) {
                for (int i = arr.length() - 1; i >= 0; i--) {
                    String name = arr.getJSONObject(i).optString("sceneName");
                    if (isSeparator(name)) {
                        // Só as cenas principais (acima da primeira divisória) aparecem.
                        if (!names.isEmpty()) break;
                        continue; // divisória logo no topo: ignora e segue
                    }
                    names.add(name);
                }
            }
            String current = d.optString("currentProgramSceneName", "");
            post(() -> listener.onScenes(names, current));
            refreshSceneAudio(current);
        });
    }

    /** Lista as fontes e mantém só as que têm áudio (as que respondem a GetInputMute). */
    private void refreshInputs() {
        final int gen = ++inputsGeneration;
        request("GetInputList", null, (ok, d, c) -> {
            if (!ok || gen != inputsGeneration) return;
            JSONArray arr = d.optJSONArray("inputs");
            List<String> names = new ArrayList<>();
            List<String> kinds = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    names.add(arr.getJSONObject(i).optString("inputName"));
                    kinds.add(arr.getJSONObject(i).optString("unversionedInputKind", arr.getJSONObject(i).optString("inputKind")));
                }
            }
            if (names.isEmpty()) {
                post(() -> listener.onAudioInputs(new ArrayList<>()));
                return;
            }
            // Para cada fonte: GetInputMute (só responde OK se tiver áudio) e GetInputVolume.
            AudioInput[] found = new AudioInput[names.size()];
            double[] vols = new double[names.size()];
            int[] remaining = {names.size() * 2};
            Runnable finish = () -> {
                if (--remaining[0] != 0 || gen != inputsGeneration) return;
                List<AudioInput> audio = new ArrayList<>();
                for (int j = 0; j < found.length; j++) {
                    if (found[j] != null) {
                        found[j].volumeMul = vols[j];
                        audio.add(found[j]);
                    }
                }
                post(() -> listener.onAudioInputs(audio));
            };
            for (int i = 0; i < names.size(); i++) {
                final int idx = i;
                vols[i] = 1.0;
                request("GetInputMute", obj("inputName", names.get(i)), (ok2, d2, c2) -> {
                    if (ok2) {
                        found[idx] = new AudioInput(names.get(idx));
                        found[idx].isMic = kinds.get(idx).endsWith("_input_capture");
                        found[idx].muted = d2.optBoolean("inputMuted");
                    }
                    finish.run();
                });
                request("GetInputVolume", obj("inputName", names.get(i)), (ok2, d2, c2) -> {
                    if (ok2) vols[idx] = d2.optDouble("inputVolumeMul", 1.0);
                    finish.run();
                });
            }
        });
    }

    /** Junta o áudio global e as fontes visíveis da cena (recursivo em grupos/cenas aninhadas). */
    private void refreshSceneAudio(String scene) {
        if (scene == null || scene.isEmpty()) return;
        programScene = scene;
        final int gen = ++sceneAudioGeneration;
        final List<String> global = new ArrayList<>();
        final List<String> fromScene = new ArrayList<>();
        final java.util.Set<String> visited = new java.util.HashSet<>();
        final int[] pendingReq = {1};
        final Runnable done = () -> {
            if (--pendingReq[0] != 0 || gen != sceneAudioGeneration) return;
            List<String> out = new ArrayList<>(global);
            for (String n : fromScene) if (!out.contains(n)) out.add(n);
            post(() -> listener.onSceneAudio(scene, out));
        };
        request("GetSpecialInputs", null, (ok, d, c) -> {
            if (ok) {
                for (String k : new String[]{"desktop1", "desktop2", "mic1", "mic2", "mic3", "mic4"}) {
                    String n = d.optString(k, "");
                    if (!n.isEmpty() && !"null".equals(n)) global.add(n);
                }
            }
            walkScene(scene, false, 0, visited, fromScene, pendingReq, done);
            done.run();
        });
    }

    private void walkScene(String scene, boolean isGroup, int depth, java.util.Set<String> visited,
                           List<String> out, int[] pendingReq, Runnable done) {
        if (depth > 4 || !visited.add(scene)) return;
        pendingReq[0]++;
        request(isGroup ? "GetGroupSceneItemList" : "GetSceneItemList", obj("sceneName", scene), (ok, d, c) -> {
            JSONArray items = ok ? d.optJSONArray("sceneItems") : null;
            if (items != null) {
                // A lista vem de baixo para cima; inverte para seguir a ordem da interface do OBS.
                for (int i = items.length() - 1; i >= 0; i--) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null || !it.optBoolean("sceneItemEnabled", true)) continue;
                    String name = it.optString("sourceName");
                    if (it.optBoolean("isGroup")) {
                        walkScene(name, true, depth + 1, visited, out, pendingReq, done);
                    } else if ("OBS_SOURCE_TYPE_SCENE".equals(it.optString("sourceType"))) {
                        walkScene(name, false, depth + 1, visited, out, pendingReq, done);
                    } else {
                        out.add(name);
                    }
                }
            }
            done.run();
        });
    }

    private void reportError(boolean ok, JSONObject d, String comment) {
        if (!ok) post(() -> listener.onRequestError(comment.isEmpty() ? "O OBS recusou o comando" : comment));
    }

    // ---------------------------------------------------------------- Util

    /**
     * Cenas usadas só como divisória na lista do OBS, ex.: "======= Source =======",
     * "----------", "### Câmeras ###". Começam com 3+ símbolos repetidos ou não têm letras/números.
     */
    static boolean isSeparator(String name) {
        String s = name.trim();
        if (s.isEmpty()) return true;
        if (s.matches("^([=\\-_~*#.+|<>—–•])\\1{2,}.*")) return true;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private void post(Runnable r) {
        main.post(r);
    }

    private static JSONObject obj(String key, String value) {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static String sha256b64(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
