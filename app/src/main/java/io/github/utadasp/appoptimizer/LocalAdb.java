package io.github.utadasp.appoptimizer;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/** Local-only ADB transport, adapted from Free My TV (MIT; see THIRD-PARTY-LICENSE.txt).
 * Loopback legacy ADB only; noninteractive reconnect never offers a new key.
 * Protocol: android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 */
final class LocalAdb implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** Start of the PKCS#1 block of a SHA-1 signature (adbd treats the token as if it were the digest). */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    /** Notified when the TV is showing the permission prompt (so the screen can explain what to do). */
    interface Approval { void waiting(); }

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private LocalAdb(Socket socket) throws IOException {
        this.socket = socket;
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** Connects and authenticates. If the TV doesn't know our key yet, waits up to `approvalMs` for you to accept. */
    static LocalAdb connect(Context c, int approvalMs, Runnable approval) throws IOException {
        KeyPair keys = keys(c);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        } catch (IOException e) {
            s.close();
            throw new IOException(("debugging is off on the TV (port 5555 closed)"));
        }
        LocalAdb adb;
        try {
            s.setSoTimeout(10_000);
            adb = new LocalAdb(s);
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
        try {
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed = false, offeredKey = false;
            while (true) {
                Message m;
                try {
                    m = adb.read();
                } catch (EOFException | SocketTimeoutException e) {
                    throw new IOException(offeredKey ? ("debugging permission wasn't accepted on the TV") : ("the TV's ADB didn't respond"));
                }
                if (m.command == CNXN) {
                    s.setSoTimeout(20_000);
                    return adb;
                }
                if (m.command == STLS) throw new IOException(("the TV asked for TLS (wireless debugging); use USB debugging"));
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException(("unexpected reply from ADB"));
                if (!signed) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    signed = true;
                } else if (!offeredKey && approvalMs > 0) {
                    // Unknown key: send the public one; the TV shows "Allow USB debugging?".
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.getPublic()));
                    offeredKey = true;
                    if (approval != null) approval.run();
                    if (Build.VERSION.SDK_INT >= 30) {
                        s.setSoTimeout(approvalMs);
                    } else {
                        // Android 8 to 10 hand the answer to the first connection that offered a key (on 10, or signed
                        // in) while it stays open, often the computer's adb (#221). "Always allow" saves our key, so a
                        // new connection that only signs gets in.
                        long until = SystemClock.elapsedRealtime() + approvalMs;
                        while (adb.in.available() == 0) {
                            if (SystemClock.elapsedRealtime() > until) {
                                throw new IOException(("debugging permission wasn't accepted on the TV"));
                            }
                            SystemClock.sleep(2000);
                            LocalAdb fresh = signedIn(keys);
                            if (fresh != null) {
                                adb.close();
                                return fresh;
                            }
                        }
                    }
                } else {
                    throw new IOException(("Authorization is required. Choose Connect and approve Always allow on the TV."));
                }
            }
        } catch (IOException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    /** A new connection that only signs (it never offers the key, so no second prompt); null while the TV doesn't know it. */
    private static LocalAdb signedIn(KeyPair keys) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(5000);
            LocalAdb adb = new LocalAdb(s);
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Message m = adb.read();
            if (m.command == AUTH && m.arg0 == AUTH_TOKEN) {
                adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                m = adb.read();
            }
            if (m.command == CNXN) {
                s.setSoTimeout(20_000);
                return adb;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        try { s.close(); } catch (IOException ignored) {}
        return null;
    }

    /** Runs a command in the TV's shell and returns its output (stdout and stderr together). */
    String shell(String command, long timeoutMs, java.util.function.Consumer<String> onChunk) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new IOException("Command timed out; outcome unknown. Refresh before retrying.");
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remaining));
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                if (result.size() + m.data.length > 2 * 1024 * 1024) throw new IOException("Output limit exceeded; outcome unknown.");
                result.write(m.data);
                if (onChunk != null) onChunk.accept(new String(m.data, StandardCharsets.UTF_8));
                send(OKAY, local, m.arg0, null);
            } else if (m.command == CLSE) {
                if (remote == 0 && m.arg0 == 0) throw new IOException(("ADB refused the command"));
                send(CLSE, local, m.arg0, null);
                return result.toString("UTF-8");
            }
        }
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        b.getInt(); // checksum (newer adbd doesn't check it)
        int magic = b.getInt();
        if ((m.command ^ 0xffffffff) != magic) throw new IOException(("invalid ADB message"));
        if (length < 0 || length > 1024 * 1024) throw new IOException(("ADB message too large"));
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(command ^ 0xffffffff);
        if (data != null) b.put(data);
        out.write(b.array());
        out.flush();
    }

    // MARK: - The app's key (kept in private storage; never goes to the log)

    private static synchronized KeyPair keys(Context c) throws IOException {
        File file = new File(c.getNoBackupFilesDir(), "local-adb-key.enc");
        try {
            java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            final String alias = "app_optimizer_adb_wrapping_v1";
            if (!store.containsAlias(alias)) {
                javax.crypto.KeyGenerator gen = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore");
                gen.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build());
                gen.generateKey();
            }
            javax.crypto.SecretKey wrapping = (javax.crypto.SecretKey) store.getKey(alias, null);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (file.exists()) {
                byte[] blob = readFile(file);
                if (blob.length < 29) throw new IOException("Stored connection key incomplete");
                cipher.init(Cipher.DECRYPT_MODE, wrapping, new javax.crypto.spec.GCMParameterSpec(128, blob, 0, 12));
                byte[] plain = cipher.doFinal(blob, 12, blob.length - 12);
                DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(plain));
                int size = data.readInt();
                if (size < 1 || size > 8192 || size > plain.length - 4) throw new IOException("Invalid connection key");
                byte[] priv = new byte[size]; data.readFully(priv);
                byte[] pub = new byte[data.available()]; data.readFully(pub);
                KeyFactory factory = KeyFactory.getInstance("RSA");
                return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(pub)),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(priv)));
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA"); gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            java.io.DataOutputStream data = new java.io.DataOutputStream(raw);
            byte[] priv = pair.getPrivate().getEncoded(); data.writeInt(priv.length); data.write(priv); data.write(pair.getPublic().getEncoded());
            cipher.init(Cipher.ENCRYPT_MODE, wrapping);
            ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
            encrypted.write(cipher.getIV()); encrypted.write(cipher.doFinal(raw.toByteArray()));
            writeFile(file, encrypted.toByteArray());
            return pair;
        } catch (GeneralSecurityException e) {
            throw new IOException("Connection key unavailable. Clear App Optimizer storage and reconnect.", e);
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            byte[] block = new byte[256];
            block[1] = 1;
            int separator = block.length - SHA1_PREFIX.length - token.length - 1;
            for (int i = 2; i < separator; i++) block[i] = (byte) 0xff;
            System.arraycopy(SHA1_PREFIX, 0, block, separator + 1, SHA1_PREFIX.length);
            System.arraycopy(token, 0, block, separator + 1 + SHA1_PREFIX.length, token.length);
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException("ADB signature: " + e.getMessage());
        }
    }

    /** Public key in adbd's format (RSAPublicKey struct in base64, plus a name). */
    private static byte[] publicKeyBlob(PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        BigInteger n = rsa.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(rsa.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " app-optimizer@tv\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        byte[] little = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) little[i] = big[big.length - 1 - i];
        return little;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /** Writes all or nothing: to a file alongside, synced to disk, and only then renamed to the real name. */
    private static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("could not save " + f.getName());
    }
}
