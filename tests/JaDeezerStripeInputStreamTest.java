package com.example.ja;

import java.io.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public final class JaDeezerStripeInputStreamTest {
    static void equal(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError("Audio bytes changed: " + expected.length + "/" + actual.length);
    }
    static byte[] encode(byte[] clear, byte[] key) throws Exception {
        byte[] encoded = clear.clone();
        for (int offset = 0; offset + 2048 <= clear.length; offset += 6144) {
            Cipher cipher = Cipher.getInstance("Blowfish/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "Blowfish"), new IvParameterSpec(new byte[]{0,1,2,3,4,5,6,7}));
            System.arraycopy(cipher.doFinal(clear, offset, 2048), 0, encoded, offset, 2048);
        }
        return encoded;
    }
    static InputStream fragmented(byte[] bytes, int start) {
        return new ByteArrayInputStream(bytes, start, bytes.length - start) {
            @Override public synchronized int read(byte[] out, int off, int len) { return super.read(out, off, Math.min(17, len)); }
        };
    }
    public static void main(String[] args) throws Exception {
        byte[] key = JaDeezerStripeInputStream.trackKey("3135556");
        // Expected key generated independently from Python's hashlib MD5, not the decoder under test.
        StringBuilder hex = new StringBuilder(); for (byte b : key) hex.append(String.format("%02x", b & 255));
        if (!hex.toString().equals("6c6c666b39662c37652575603c643439")) throw new AssertionError("Key derivation mismatch");
        Random random = new Random(251);
        int checks = 0;
        for (int length : new int[]{0,1,2047,2048,2049,4095,6143,6144,6145,8192,12313,50000}) {
            byte[] clear = new byte[length]; random.nextBytes(clear);
            byte[] encrypted = encode(clear, key);
            try (InputStream decoded = new JaDeezerStripeInputStream(fragmented(encrypted, 0), key)) {
                equal(clear, decoded.readAllBytes());
                if (decoded.read() != -1) throw new AssertionError("Expected EOF");
            }
            for (int attempt = 0; attempt < 30 && length > 0; attempt++) {
                int position = random.nextInt(length);
                int aligned = position / 6144 * 6144;
                try (InputStream decoded = new JaDeezerStripeInputStream(fragmented(encrypted, aligned), key)) {
                    if (decoded.skip(position - aligned) != position - aligned) throw new AssertionError("Seek lost bytes");
                    equal(Arrays.copyOfRange(clear, position, length), decoded.readAllBytes());
                }
                checks++;
            }
        }
        var closed = new JaDeezerStripeInputStream(InputStream.nullInputStream(), key); closed.close();
        try { closed.read(); throw new AssertionError("Closed stream accepted read"); } catch (IOException expected) { }
        System.out.println("PASS: full reads, fragmented network reads, partial stripes and " + checks + " random seeks");
    }
}
