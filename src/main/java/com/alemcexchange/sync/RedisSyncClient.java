package com.alemcexchange.sync;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 极简 Redis 客户端（RESP2 协议，纯 TCP，零第三方依赖）。
 * 仅实现跨服同步所需的三个命令：AUTH / SUBSCRIBE / PUBLISH。
 *
 * 结构：发布连接（懒重连，同步 publish）+ 订阅连接（独立线程 + 自动重连）。
 * 适用单机/主从 Redis；不支持 Cluster/Sentinel（协议路由不同）。
 */
public class RedisSyncClient {

    private static final Logger LOG = Logger.getLogger("ALEMCExchange");

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String channel;
    private final long reconnectMillis;
    private final Consumer<String> messageHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);

    // 发布连接（仅 flush 线程使用，串行）
    private Socket publishSocket;
    private OutputStream publishOut;

    // 订阅连接（独立线程）
    private Thread subscriberThread;

    public RedisSyncClient(String host, int port, String username, String password,
                           String channel, int reconnectSeconds, Consumer<String> messageHandler) {
        this.host = host;
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.channel = channel;
        this.reconnectMillis = Math.max(1L, reconnectSeconds) * 1000L;
        this.messageHandler = messageHandler;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        subscriberThread = new Thread(this::subscribeLoop, "ALEMCExchange-Redis-Sub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void stop() {
        running.set(false);
        closePublish();
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    /** 发布消息（线程安全；失败自动重连重试一次） */
    public synchronized void publish(String payload) {
        if (!running.get()) return;
        try {
            publish0(payload);
        } catch (IOException e) {
            closePublish();
            try {
                publish0(payload);
            } catch (IOException e2) {
                LOG.log(Level.FINE, "Redis publish failed after retry: " + e2.getMessage());
            }
        }
    }

    private void publish0(String payload) throws IOException {
        ensurePublishConnection();
        byte[] cmd = encode("PUBLISH", channel, payload);
        publishOut.write(cmd);
        publishOut.flush();
        // 消费回复，防止 TCP 缓冲区被未读回包填满导致阻塞
        try {
            publishSocket.setSoTimeout(100);
            int available = publishSocket.getInputStream().available();
            if (available > 0) {
                byte[] drain = new byte[available];
                //noinspection ResultOfMethodCallIgnored
                publishSocket.getInputStream().read(drain);
            }
        } catch (IOException ignored) {
        } finally {
            try {
                publishSocket.setSoTimeout(0);
            } catch (IOException ignored) {
            }
        }
    }

    private void ensurePublishConnection() throws IOException {
        if (publishSocket != null && publishSocket.isConnected() && !publishSocket.isClosed()) return;
        publishSocket = new Socket();
        publishSocket.connect(new InetSocketAddress(host, port), 5000);
        publishSocket.setTcpNoDelay(true);
        publishOut = publishSocket.getOutputStream();
        if (!password.isEmpty()) {
            publishOut.write(encodeAuth());
            publishOut.flush();
        }
    }

    private synchronized void closePublish() {
        try {
            if (publishSocket != null) publishSocket.close();
        } catch (IOException ignored) {
        }
        publishSocket = null;
        publishOut = null;
    }

    // ─────────────────────── 订阅线程 ───────────────────────

    private void subscribeLoop() {
        while (running.get()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setTcpNoDelay(true);
                OutputStream out = socket.getOutputStream();
                if (!password.isEmpty()) {
                    out.write(encodeAuth());
                    out.flush();
                }
                out.write(encode("SUBSCRIBE", channel));
                out.flush();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                while (running.get()) {
                    List<Object> reply = readReply(reader);
                    if (reply == null) break;   // 连接断开
                    // 订阅确认: [subscribe, channel, count]；消息: [message, channel, payload]
                    if (reply.size() == 3
                            && reply.get(0) instanceof String type
                            && "message".equalsIgnoreCase(type)
                            && reply.get(2) instanceof String payload) {
                        try {
                            messageHandler.accept(payload);
                        } catch (Exception e) {
                            LOG.log(Level.FINE, "Redis message handler error: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.FINE, "Redis subscribe connection lost: " + e.getMessage());
                }
            }
            if (!running.get()) break;
            try {
                Thread.sleep(reconnectMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ─────────────────────── RESP 编解码 ───────────────────────

    private byte[] encodeAuth() {
        return username.isEmpty()
                ? encode("AUTH", password)
                : encode("AUTH", username, password);
    }

    /** 编码 RESP 数组命令：*N\r\n$len\r\narg\r\n ... */
    private static byte[] encode(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 读取一个 RESP 回复；返回 null 表示流结束。仅解析同步所需的简单类型。 */
    private static List<Object> readReply(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) return null;
        char type = line.charAt(0);
        String body = line.substring(1);
        switch (type) {
            case '+': case '-': case ':': {
                List<Object> single = new ArrayList<>(1);
                single.add(body);
                return single;
            }
            case '$': {
                int len = Integer.parseInt(body);
                if (len < 0) return null;
                char[] buf = new char[len + 2];
                int read = 0;
                while (read < buf.length) {
                    int n = reader.read(buf, read, buf.length - read);
                    if (n < 0) return null;
                    read += n;
                }
                List<Object> bulk = new ArrayList<>(1);
                bulk.add(new String(buf, 0, len));
                return bulk;
            }
            case '*': {
                int count = Integer.parseInt(body);
                if (count < 0) return null;
                List<Object> array = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String item = reader.readLine();
                    if (item == null) return null;
                    if (item.startsWith("$")) {
                        int len = Integer.parseInt(item.substring(1));
                        if (len < 0) {
                            array.add(null);
                            continue;
                        }
                        char[] buf = new char[len + 2];
                        int read = 0;
                        while (read < buf.length) {
                            int n = reader.read(buf, read, buf.length - read);
                            if (n < 0) return null;
                            read += n;
                        }
                        array.add(new String(buf, 0, len));
                    } else {
                        array.add(item.substring(1));
                    }
                }
                return array;
            }
            default:
                return null;
        }
    }
}
