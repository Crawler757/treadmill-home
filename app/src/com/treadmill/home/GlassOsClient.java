package com.treadmill.home;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Client for iFit GlassOS's local gRPC service (port 54321): reads metrics, and sets target
 * speed/incline and starts/stops workouts through the motor service. The hardware Stop key and
 * the safety key always override it.
 *
 * A deliberately tiny gRPC-over-HTTP/2 implementation so the app needs no libraries:
 * unary calls only, request headers sent as HPACK literals, response headers never decoded.
 * Mutual TLS with the client cert QZ uses (APK assets), plus the client_id header GlassOS expects.
 */
final class GlassOsClient implements Closeable {

    private static final String CLIENT_ID = "com.ifit.eriador";
    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int TYPE_DATA = 0, TYPE_HEADERS = 1, TYPE_RST_STREAM = 3, TYPE_SETTINGS = 4,
            TYPE_PING = 6, TYPE_GOAWAY = 7, TYPE_WINDOW_UPDATE = 8;
    private static final int FLAG_END_STREAM = 0x1, FLAG_ACK = 0x1, FLAG_END_HEADERS = 0x4, FLAG_PADDED = 0x8;

    private final SSLSocketFactory factory;
    private final String host;
    private final int port;

    private SSLSocket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextStreamId;

    GlassOsClient(Context context, String host, int port) throws Exception {
        this.factory = buildSocketFactory(context);
        this.host = host;
        this.port = port;
    }

    /** Calls a unary method such as "com.ifit.glassos.SpeedService/GetSpeed" with an empty request. */
    byte[] call(String method) throws IOException {
        return call(method, new byte[0]);
    }

    /**
     * Asks the motor service for a new target speed, exactly as iFit's own apps do. The motor
     * service applies its own limits and ignores this unless a workout is already running.
     */
    void setSpeedKph(double kph) throws IOException {
        call("com.ifit.glassos.SpeedService/SetSpeed", doubleField1(kph)); // SpeedRequest{kph = 1}
    }

    /** Asks the motor service for a new target incline (%), within its own limits. */
    void setInclinePercent(double percent) throws IOException {
        call("com.ifit.glassos.InclineService/SetIncline", doubleField1(percent)); // InclineRequest{percent = 1}
    }

    /** Starts a manual workout, like iFit's Quick start: the belt comes up at a slow walk. */
    void startWorkout() throws IOException {
        call("com.ifit.glassos.WorkoutService/StartNewWorkout");
    }

    /** Pauses the running workout: the belt stops, but time, distance and laps are kept. */
    void pauseWorkout() throws IOException {
        call("com.ifit.glassos.WorkoutService/Pause");
    }

    /** Resumes a paused workout. */
    void resumeWorkout() throws IOException {
        call("com.ifit.glassos.WorkoutService/Resume");
    }

    /** Ends the workout; the belt stops. */
    void stopWorkout() throws IOException {
        call("com.ifit.glassos.WorkoutService/Stop");
    }

    /** Protobuf message with one double in field 1: tag 0x09 (field 1, 64-bit) + 8 bytes little-endian. */
    private static byte[] doubleField1(double value) {
        byte[] b = new byte[9];
        b[0] = 0x09;
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) b[1 + i] = (byte) (bits >>> (8 * i));
        return b;
    }

    /** Calls a unary method with an already-encoded protobuf request. */
    synchronized byte[] call(String method, byte[] request) throws IOException {
        if (socket == null || nextStreamId > (1 << 30)) {
            connect();
        }
        try {
            return unary("/" + method, request);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
    }

    private void connect() throws IOException {
        close();
        SSLSocket s = (SSLSocket) factory.createSocket(host, port);
        s.setSoTimeout(5000);
        requestHttp2(s);
        s.startHandshake();
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        nextStreamId = 1;
        out.write(PREFACE);
        writeFrame(TYPE_SETTINGS, 0, 0, new byte[0]);
        out.flush();
    }

    private byte[] unary(String path, byte[] message) throws IOException {
        int streamId = nextStreamId;
        nextStreamId += 2;

        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        literalHeader(headers, ":method", "POST");
        literalHeader(headers, ":scheme", "https");
        literalHeader(headers, ":path", path);
        literalHeader(headers, ":authority", host + ":" + port);
        literalHeader(headers, "content-type", "application/grpc");
        literalHeader(headers, "te", "trailers");
        literalHeader(headers, "client_id", CLIENT_ID);
        writeFrame(TYPE_HEADERS, FLAG_END_HEADERS, streamId, headers.toByteArray());

        byte[] body = new byte[5 + message.length];
        putInt(body, 1, message.length); // byte 0 = not compressed
        System.arraycopy(message, 0, body, 5, message.length);
        writeFrame(TYPE_DATA, FLAG_END_STREAM, streamId, body);
        out.flush();

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean gotData = false;
        while (true) {
            int length = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            int type = in.readUnsignedByte();
            int flags = in.readUnsignedByte();
            int id = in.readInt() & 0x7fffffff;
            byte[] payload = new byte[length];
            in.readFully(payload);

            if (type == TYPE_SETTINGS && (flags & FLAG_ACK) == 0) {
                writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                out.flush();
            } else if (type == TYPE_PING && (flags & FLAG_ACK) == 0) {
                writeFrame(TYPE_PING, FLAG_ACK, 0, payload);
                out.flush();
            } else if (type == TYPE_GOAWAY) {
                throw new IOException("GOAWAY from GlassOS");
            } else if (id == streamId && type == TYPE_RST_STREAM) {
                throw new IOException("stream reset by GlassOS");
            } else if (id == streamId && type == TYPE_DATA) {
                int start = 0, end = length;
                if ((flags & FLAG_PADDED) != 0) {
                    start = 1;
                    end = length - (payload[0] & 0xff);
                }
                data.write(payload, start, end - start);
                gotData = true;
                if (length > 0) {
                    // Give the connection window back so long sessions never stall.
                    byte[] increment = new byte[4];
                    putInt(increment, 0, length);
                    writeFrame(TYPE_WINDOW_UPDATE, 0, 0, increment);
                    out.flush();
                }
                if ((flags & FLAG_END_STREAM) != 0) {
                    break;
                }
            } else if (id == streamId && type == TYPE_HEADERS && (flags & FLAG_END_STREAM) != 0) {
                break; // trailers
            }
        }

        byte[] framed = data.toByteArray();
        if (!gotData || framed.length < 5) {
            throw new IOException("no message from GlassOS for " + path);
        }
        int messageLength = ((framed[1] & 0xff) << 24) | ((framed[2] & 0xff) << 16)
                | ((framed[3] & 0xff) << 8) | (framed[4] & 0xff);
        byte[] result = new byte[messageLength];
        System.arraycopy(framed, 5, result, 0, messageLength);
        return result;
    }

    private void writeFrame(int type, int flags, int streamId, byte[] payload) throws IOException {
        byte[] header = new byte[9];
        header[0] = (byte) (payload.length >>> 16);
        header[1] = (byte) (payload.length >>> 8);
        header[2] = (byte) payload.length;
        header[3] = (byte) type;
        header[4] = (byte) flags;
        putInt(header, 5, streamId);
        out.write(header);
        out.write(payload);
    }

    /** HPACK "literal header field without indexing, new name", no Huffman coding. */
    private static void literalHeader(ByteArrayOutputStream block, String name, String value) {
        block.write(0x00);
        hpackString(block, name);
        hpackString(block, value);
    }

    private static void hpackString(ByteArrayOutputStream block, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        int n = bytes.length;
        if (n < 127) {
            block.write(n);
        } else {
            block.write(127);
            n -= 127;
            while (n >= 128) {
                block.write((n & 0x7f) | 0x80);
                n >>>= 7;
            }
            block.write(n);
        }
        block.write(bytes, 0, bytes.length);
    }

    private static void putInt(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >>> 24);
        b[offset + 1] = (byte) (value >>> 16);
        b[offset + 2] = (byte) (value >>> 8);
        b[offset + 3] = (byte) value;
    }

    /** GlassOS's gRPC server only speaks HTTP/2 when "h2" is negotiated via ALPN. */
    private static void requestHttp2(SSLSocket socket) throws IOException {
        try {
            // Android 9's Conscrypt socket; the same hidden method OkHttp uses on this API level.
            socket.getClass().getMethod("setAlpnProtocols", byte[].class)
                    .invoke(socket, (Object) new byte[] {2, 'h', '2'});
        } catch (ReflectiveOperationException e) {
            throw new IOException("cannot enable ALPN h2", e);
        }
    }

    private static SSLSocketFactory buildSocketFactory(Context context) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (InputStream certIn = context.getAssets().open("client_cert.pem")) {
            cert = (X509Certificate) cf.generateCertificate(certIn);
        }
        String pem;
        try (InputStream keyIn = context.getAssets().open("client_key.pem")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = keyIn.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            pem = new String(buf.toByteArray(), StandardCharsets.US_ASCII);
        }
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(base64, Base64.DEFAULT)));

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("client", key, new char[0], new Certificate[] {cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        // GlassOS uses a self-signed "testca" server cert on localhost; nothing to verify it against.
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), trustAll, new SecureRandom());
        return ssl.getSocketFactory();
    }

    /** Reads a double (wire type 1) or int (wire type 0) field from a protobuf message; 0 if absent. */
    static double field(byte[] msg, int fieldNumber) {
        int i = 0;
        while (i < msg.length) {
            long tag = 0;
            int shift = 0;
            while (true) {
                int b = msg[i++] & 0xff;
                tag |= (long) (b & 0x7f) << shift;
                if (b < 0x80) break;
                shift += 7;
            }
            int number = (int) (tag >>> 3);
            int wireType = (int) (tag & 7);
            if (wireType == 0) {
                long v = 0;
                shift = 0;
                while (true) {
                    int b = msg[i++] & 0xff;
                    v |= (long) (b & 0x7f) << shift;
                    if (b < 0x80) break;
                    shift += 7;
                }
                if (number == fieldNumber) return v;
            } else if (wireType == 1) {
                long bits = 0;
                for (int k = 0; k < 8; k++) {
                    bits |= (long) (msg[i + k] & 0xff) << (8 * k);
                }
                i += 8;
                if (number == fieldNumber) return Double.longBitsToDouble(bits);
            } else if (wireType == 2) {
                int len = 0;
                shift = 0;
                while (true) {
                    int b = msg[i++] & 0xff;
                    len |= (b & 0x7f) << shift;
                    if (b < 0x80) break;
                    shift += 7;
                }
                i += len;
            } else if (wireType == 5) {
                i += 4;
            } else {
                break;
            }
        }
        return 0;
    }
}
