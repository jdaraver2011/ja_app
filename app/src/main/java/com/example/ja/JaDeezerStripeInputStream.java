package com.example.ja;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Independent streaming implementation of Deezer's BF_CBC_STRIPE framing.
 * Only used with a FULL media URL returned by the signed-in account's license.
 * Input must begin on a 6144-byte boundary; no complete decrypted song is stored.
 */
public final class JaDeezerStripeInputStream extends InputStream {
    public static final int STRIPE = 6144;
    private final InputStream source;
    private final byte[] key;
    private final byte[] buffer = new byte[STRIPE];
    private int offset, size;
    private boolean closed;

    public JaDeezerStripeInputStream(InputStream source, byte[] key) {
        this.source = source;
        this.key = key.clone();
    }
    public static byte[] trackKey(String trackId) throws GeneralSecurityException {
        if (!trackId.matches("-?[0-9]{1,19}")) throw new IllegalArgumentException("Invalid track ID");
        byte[] hash = MessageDigest.getInstance("MD5").digest(trackId.getBytes(StandardCharsets.US_ASCII));
        char[] hex = "0123456789abcdef".toCharArray();
        byte[] digest = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            digest[i * 2] = (byte) hex[(hash[i] >>> 4) & 15];
            digest[i * 2 + 1] = (byte) hex[hash[i] & 15];
        }
        byte[] salt = "g4el58wc0zvf9na1".getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[16];
        for (int i = 0; i < 16; i++) result[i] = (byte) (digest[i] ^ digest[i + 16] ^ salt[i]);
        return result;
    }
    private boolean fill() throws IOException {
        if (closed) throw new IOException("Stream closed");
        offset = 0;
        size = 0;
        while (size < buffer.length) {
            int n = source.read(buffer, size, buffer.length - size);
            if (n < 0) break;
            if (n == 0) {
                int one = source.read();
                if (one < 0) break;
                buffer[size++] = (byte) one;
            } else size += n;
        }
        if (size >= 2048) {
            try {
                Cipher cipher = Cipher.getInstance("Blowfish/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "Blowfish"),
                    new IvParameterSpec(new byte[]{0,1,2,3,4,5,6,7}));
                byte[] clear = cipher.doFinal(buffer, 0, 2048);
                System.arraycopy(clear, 0, buffer, 0, clear.length);
                Arrays.fill(clear, (byte) 0);
            } catch (GeneralSecurityException e) { throw new IOException("Audio decoder unavailable", e); }
        }
        return size != 0;
    }
    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }
    @Override public int read(byte[] out, int start, int length) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (start < 0 || length < 0 || start > out.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        if (offset == size && !fill()) return -1;
        int count = Math.min(length, size - offset);
        System.arraycopy(buffer, offset, out, start, count);
        offset += count;
        return count;
    }
    @Override public void close() throws IOException {
        closed = true;
        Arrays.fill(key, (byte) 0);
        Arrays.fill(buffer, (byte) 0);
        source.close();
    }
}
