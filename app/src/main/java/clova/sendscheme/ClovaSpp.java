package clova.sendscheme;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CLOVA のセットアップ用 Bluetooth SPP プロトコル。
 * フレーム: コマンド (ASCII 6 バイト) + 長さ (uint16 ビッグエンディアン) + ペイロード。
 * CLOVA からの応答は "AM" + 送ったコマンドと同じ 4 文字 (例: APGFRQ → AMGFRQ)。
 */
public class ClovaSpp {

    public interface Listener {
        void onConnected(BluetoothDevice device);

        void onDisconnected(String reason);

        void onFrame(String type, byte[] payload);

        void onSendFailed(String cmd, String reason);
    }

    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile BluetoothSocket socket;

    public ClovaSpp(Listener listener) {
        this.listener = listener;
    }

    public static boolean isClovaName(String name) {
        return name != null && (name.startsWith("CLOVA-") || name.startsWith("WAVE-"));
    }

    public static byte[] frame(String cmd, byte[] payload) {
        if (cmd.length() != 6) {
            throw new IllegalArgumentException("command must be 6 chars: " + cmd);
        }
        byte[] out = new byte[8 + payload.length];
        System.arraycopy(cmd.getBytes(StandardCharsets.US_ASCII), 0, out, 0, 6);
        out[6] = (byte) (payload.length >> 8);
        out[7] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    public boolean isConnected() {
        BluetoothSocket s = socket;
        return s != null && s.isConnected();
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothAdapter adapter, BluetoothDevice device) {
        close();
        new Thread(() -> {
            BluetoothSocket s = null;
            try {
                // 検索中は接続が不安定になるため止める
                adapter.cancelDiscovery();
                s = device.createRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
            } catch (IOException e) {
                closeQuietly(s);
                listener.onDisconnected("接続に失敗しました: " + e.getMessage());
                return;
            }
            socket = s;
            listener.onConnected(device);
            readLoop(s);
        }, "clova-spp-reader").start();
    }

    private void readLoop(BluetoothSocket s) {
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] header = new byte[8];
            while (true) {
                in.readFully(header);
                String type = new String(header, 0, 6, StandardCharsets.US_ASCII);
                int length = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                byte[] payload = new byte[length];
                in.readFully(payload);
                listener.onFrame(type, payload);
            }
        } catch (IOException e) {
            if (socket == s) {
                socket = null;
            }
            closeQuietly(s);
            listener.onDisconnected("切断されました: " + e.getMessage());
        }
    }

    public void send(String cmd, byte[] payload) {
        BluetoothSocket s = socket;
        if (s == null || !s.isConnected()) {
            listener.onSendFailed(cmd, "接続されていません");
            return;
        }
        byte[] data = frame(cmd, payload);
        writer.execute(() -> {
            try {
                OutputStream out = s.getOutputStream();
                out.write(data);
                out.flush();
            } catch (IOException e) {
                listener.onSendFailed(cmd, e.getMessage());
            }
        });
    }

    public void close() {
        BluetoothSocket s = socket;
        socket = null;
        closeQuietly(s);
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
