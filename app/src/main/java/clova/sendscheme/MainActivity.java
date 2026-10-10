package clova.sendscheme;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements ClovaSpp.Listener {

    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_ENABLE_BT = 20;
    private static final String PREFS = "clova_setup";
    private static final String PREF_SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "https://192.168.0.162:8444/";
    private static final String FAKE_SETUP_DONE = "clovahome://home_response/1?result=1&auth=1&mode=1";

    private final ClovaSpp spp = new ClovaSpp(this);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BroadcastReceiver discoveryReceiver;
    private boolean waitingWifiList;

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        setContentView(buildLayout());
        requestMissingPermissions();
        setStatus("未接続");
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        spp.close();
        super.onDestroy();
    }

    // ---- 画面 ----

    private View buildLayout() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, 0, 0, dp(8));
        root.addView(statusView);

        root.addView(row(
                button("CLOVA を探して接続", v -> connectClova()),
                button("切断", v -> {
                    spp.close();
                    setStatus("未接続");
                })));
        root.addView(row(
                button("デバイス確認", v -> send("APCDRQ")),
                button("FW 情報", v -> send("APGFRQ")),
                button("プロトコル", v -> send("APPVRQ"))));
        root.addView(row(
                button("Wi-Fi 設定", v -> {
                    waitingWifiList = true;
                    send("APGNRQ");
                }),
                button("認証 (Set Auth)", v -> confirmAuth())));
        root.addView(row(
                button("サーバー変更", v -> showServerDialog()),
                button("スキーム送信", v -> showSchemeDialog())));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private LinearLayout row(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : buttons) {
            row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        ui.post(() -> statusView.setText(text));
    }

    private void log(String line) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        ui.post(() -> {
            logView.append(time + " " + line + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ---- 権限と接続 ----

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : requiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return true;
        }
        requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        return false;
    }

    @SuppressLint("MissingPermission")
    private void connectClova() {
        if (!requestMissingPermissions()) {
            log("権限を許可してから、もう一度押してください");
            return;
        }
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
            return;
        }
        // ペアリング済みの CLOVA があれば、検索せずに接続する
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (ClovaSpp.isClovaName(d.getName()) && d.getType() != BluetoothDevice.DEVICE_TYPE_LE) {
                startConnect(d);
                return;
            }
        }
        startDiscovery();
    }

    @SuppressLint("MissingPermission")
    private void startConnect(BluetoothDevice d) {
        setStatus(d.getName() + " に接続中…");
        log("connect " + d.getName() + " " + d.getAddress());
        spp.connect(adapter, d);
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery() {
        stopDiscovery();
        discoveryReceiver = new BroadcastReceiver() {
            @SuppressWarnings("deprecation") // 型指定版は API 33 以降のみ
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null && ClovaSpp.isClovaName(d.getName()) && d.getType() != BluetoothDevice.DEVICE_TYPE_LE) {
                        stopDiscovery();
                        startConnect(d);
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    stopDiscovery();
                    if (!spp.isConnected()) {
                        setStatus("CLOVA が見つかりませんでした");
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);
        if (adapter.startDiscovery()) {
            setStatus("CLOVA を検索中…");
        } else {
            stopDiscovery();
            setStatus("検索を開始できませんでした（位置情報がオフになっていませんか）");
        }
    }

    @SuppressLint("MissingPermission")
    private void stopDiscovery() {
        if (discoveryReceiver != null) {
            unregisterReceiver(discoveryReceiver);
            discoveryReceiver = null;
            adapter.cancelDiscovery();
        }
    }

    // ---- 送信 ----

    private void send(String cmd) {
        send(cmd, new byte[0]);
    }

    private void send(String cmd, byte[] payload) {
        if (!spp.isConnected()) {
            log("未接続です。先に「CLOVA を探して接続」を押してください");
            return;
        }
        log(">> " + cmd + " " + maskPsk(new String(payload, StandardCharsets.UTF_8)));
        spp.send(cmd, payload);
    }

    private void sendJson(String cmd, JSONObject json) {
        send(cmd, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendScheme(String url) {
        log("scheme: " + url);
        send("APSCRQ", Base64.encode(url.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private static String maskPsk(String text) {
        try {
            JSONObject o = new JSONObject(text);
            if (o.has("psk")) {
                o.put("psk", "***");
                return o.toString();
            }
        } catch (JSONException ignored) {
        }
        return text;
    }

    private void confirmAuth() {
        new AlertDialog.Builder(this)
                .setTitle("認証 (Set Auth)")
                .setMessage("ダミーの認証コードを送ります。CLOVA は接続先の認証サーバーの /token でコードをトークンに交換します。先に「サーバー変更」と「Wi-Fi 設定」を済ませてください。")
                .setPositiveButton("送信", (d, w) -> {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("clovaAuthCode", "dummy");
                        json.put("clovaAuthState", "dummy");
                        json.put("isClearUserData", false);
                        sendJson("APSPRQ", json);
                    } catch (JSONException e) {
                        log("error: " + e);
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showServerDialog() {
        String[] items = {"自前の認証サーバー（URL を入力）", "NAVER（韓国）サーバー", "LINE（日本）サーバー ※サービス終了"};
        new AlertDialog.Builder(this)
                .setTitle("サーバー変更")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        askServerUrl();
                    } else {
                        // 0 = naver_real, 3 = line_real（CLOVA 側の定義）
                        sendServerSetting(which == 1 ? "clovasetup://server_setting/0" : "clovasetup://server_setting/3");
                    }
                })
                .show();
    }

    private void askServerUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL));
        new AlertDialog.Builder(this)
                .setTitle("認証サーバーの URL")
                .setMessage("https で始まる URL（例: " + DEFAULT_SERVER_URL + "）")
                .setView(input)
                .setPositiveButton("送信", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.startsWith("https://")) {
                        log("URL は https:// で始めてください");
                        return;
                    }
                    if (!url.endsWith("/")) {
                        url += "/";
                    }
                    prefs.edit().putString(PREF_SERVER_URL, url).apply();
                    // clovasetup:// で送ると、セットアップウィザードと cdk service の両方に保存される
                    sendServerSetting("clovasetup://server_setting/10?cic=" + url + "&auth=" + url
                            + "&client_id=fake&client_secret_id=fake");
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void sendServerSetting(String scheme) {
        sendScheme(scheme);
        log("CLOVA に選択肢が出たら「cdk service」を選んでください。CLOVA が再起動します");
    }

    private void showSchemeDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("clovasetup://...");
        new AlertDialog.Builder(this)
                .setTitle("スキーム送信")
                .setView(input)
                .setPositiveButton("送信", (d, w) -> sendScheme(input.getText().toString().trim()))
                .setNeutralButton("完了通知を偽装", (d, w) -> sendScheme(FAKE_SETUP_DONE))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ---- 受信 ----

    @SuppressLint("MissingPermission")
    @Override
    public void onConnected(BluetoothDevice device) {
        setStatus(device.getName() + " に接続しました");
        log("connected");
    }

    @Override
    public void onDisconnected(String reason) {
        setStatus("未接続");
        log(reason);
    }

    @Override
    public void onSendFailed(String cmd, String reason) {
        log("送信失敗 " + cmd + ": " + reason);
    }

    @Override
    public void onFrame(String type, byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        log("<< " + type + " " + pretty(text));
        try {
            if (type.startsWith("AMGF")) {
                JSONObject fw = new JSONObject(text).getJSONObject("firmware");
                setStatus("FW " + fw.optString("ver") + " / トークン: " + (fw.optBoolean("hasClovaToken") ? "あり" : "なし"));
            } else if (type.startsWith("AMGN") && waitingWifiList) {
                waitingWifiList = false;
                JSONArray list = new JSONObject(text).getJSONArray("wifi");
                ui.post(() -> showWifiList(list));
            }
        } catch (JSONException e) {
            log("応答を解析できません: " + e.getMessage());
        }
    }

    private static String pretty(String text) {
        try {
            return new JSONObject(text).toString(1);
        } catch (JSONException e) {
            return text;
        }
    }

    private void showWifiList(JSONArray list) {
        if (list.length() == 0) {
            log("Wi-Fi が見つかりませんでした");
            return;
        }
        String[] labels = new String[list.length()];
        for (int i = 0; i < list.length(); i++) {
            JSONObject w = list.optJSONObject(i);
            labels[i] = w.optString("ssid") + "  (" + w.optInt("signal_level") + " dBm, " + keyMgmtLabel(w.optInt("key_mgmt")) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("CLOVA が接続する Wi-Fi")
                .setItems(labels, (d, which) -> askWifiPassword(list.optJSONObject(which)))
                .show();
    }

    private static String keyMgmtLabel(int keyMgmt) {
        switch (keyMgmt) {
            case 0:
                return "暗号化なし";
            case 2:
                return "EAP";
            case 4:
                return "PSK";
            case 8:
                return "WEP";
            default:
                return "key_mgmt=" + keyMgmt;
        }
    }

    private void askWifiPassword(JSONObject entry) {
        if (entry.optInt("key_mgmt") == 0) {
            sendWifi(entry, "");
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(entry.optString("ssid"))
                .setMessage("パスワード")
                .setView(input)
                .setPositiveButton("接続", (d, w) -> sendWifi(entry, input.getText().toString()))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void sendWifi(JSONObject entry, String psk) {
        try {
            JSONObject json = new JSONObject(entry.toString());
            json.put("psk", psk);
            json.put("currentTime", System.currentTimeMillis());
            sendJson("APSNRQ", json);
        } catch (JSONException e) {
            log("error: " + e);
        }
    }
}
