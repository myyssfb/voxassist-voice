package cn.shaohsmoni.voxassist;

import java.math.RoundingMode;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Sec-MS-GEC v2 token generator for the Edge Read-Aloud TTS service.
 *
 * Faithful Kotlin/Java port of rany2/edge-tts drm.py:
 *  - custom SHA-256 variant (salt-skipped init, sigma adjustments,
 *    constant swap at index 14, rotation-via-multiply trick)
 *  - CS64 polynomial digest
 *  - token = "<CS64 8-byte LE hex>-<SHA256 hex>" over
 *    str(floor5min(ticks)*1e7) + TrustedClientToken
 *
 * All modular arithmetic uses Java int wrap-around (= & 0xFFFFFFFF in Python);
 * accumulators that Python keeps unmasked use Java long.
 * Self-verified in CI against Python-generated vectors.
 */
public final class EdgeDrm {

    public static final double WIN_EPOCH = 11644473600.0;
    public static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    private static final int MASK = 0xFFFFFFFF;

    private static final int[] SHA256_INITIAL = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };
    private static final int[] SHA256_STATE = {
            0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
            0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2
    };
    private static final int[] SHA256_SCHEDULE = {
            0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
            0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
            0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
            0xD258B060, 0x72766564
    };
    private static final int[] K = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5,
            0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
            0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3,
            0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
            0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC,
            0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
            0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
            0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
            0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
            0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
            0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3,
            0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
            0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
            0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208,
            0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2
    };
    private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;
    private static final int SIGMA0_ADJUSTMENT = 0x059C0B80;
    private static final int SIGMA1_ADJUSTMENT = 0x000B92C0;

    private static final int[][] CS64_COEFFS = {
            {0xE82E91B1, 0xB32700D0}, {0x44DE24C6, 0x88DFF51D},
            {0x3BEB65BC, 0x29514038}, {0xC89C5933, 0xB328CBDF},
            {0x8D1EC211, 0xD405B4D5}, {0x314EF5D5, 0x26E55F3C},
            {0xE92491B1, 0x607801C6}, {0xF0A5B9D5, 0x82B2C1FB},
            {0x06064CF3, 0xFA51A3DA}, {0xDA79FA60, 0x207CC464},
            {0xBED20560, 0x3C3C74F3}, {0x89F9C9F8, 0xD2D14B61},
            {0x35FA38C6, 0xBB04A2C1}, {0xCB49E3C6, 0xC5626167}
    };
    private static final int CS64_FACTOR = 0x82893933;
    private static final int CS64_ADJ0 = 0xF2F90B1A;
    private static final int CS64_ADJ1 = 0x0B1A0000;

    private EdgeDrm() {}

    /** Generate the Sec-MS-GEC token for "now" (with optional clock-skew seconds). */
    public static String generateToken() {
        return generateToken(System.currentTimeMillis() / 1000.0);
    }

    /** Generate the token for an explicit unix timestamp (tests / skew correction). */
    public static String generateToken(double unixTimestamp) {
        // Same double arithmetic order as Python to reproduce its rounding exactly.
        double ticks = unixTimestamp + WIN_EPOCH;
        ticks -= ticks % 300;
        ticks *= 1e9 / 100.0; // S_TO_NS / 100 == 1e7
        String strToHash = new BigDecimal(ticks)
                .setScale(0, RoundingMode.FLOOR).toBigInteger().toString()
                + TRUSTED_CLIENT_TOKEN;
        return tokenHash(asciiBytes(strToHash));
    }

    private static byte[] asciiBytes(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = (byte) s.charAt(i);
        return out;
    }

    // ================= custom SHA-256 variant =================

    private static int sigma1(int v) {
        return (v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10);
    }

    private static int sigma0(int v) {
        return (v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3);
    }

    /**
     * Python: rot_e = e * 0x100000001  (a 64-bit value whose low 32 bits repeat e).
     * (rot_e >> s) in Python == (rotE >>> s) on the same 64-bit bit pattern in Java,
     * because Python's value is positive and < 2^64 and >>> is a logical shift.
     */
    private static long rot64(long valueTimesPlusOne, int shift) {
        return valueTimesPlusOne >>> shift;
    }

    private static long mulRep(int v) {
        return (v & 0xFFFFFFFFL) * 0x100000001L;
    }

    /** One "compact" round: a,b,..,h = t1+t2, a, b, c, d+t1, e, f, g */
    private static void roundCompact(int[] s, int[] words, int[] constants, int i) {
        int a = s[0], b = s[1], c = s[2], d = s[3];
        int e = s[4], f = s[5], g = s[6], h = s[7];
        long rotE = mulRep(e), rotA = mulRep(a);
        int t1 = h
                + (int) ((rot64(rotE, 6) ^ rot64(rotE, 11) ^ rot64(rotE, 25)) & MASK)
                + (g ^ (e & (f ^ g)))
                + constants[i]
                + words[i];
        int t2 = (int) ((rot64(rotA, 2) ^ rot64(rotA, 13) ^ rot64(rotA, 22)) & MASK)
                + ((a & b) | (c & (a | b)));
        int na = t1 + t2;
        int ne = d + t1;
        s[0] = na;         // new a = t1 + t2
        s[1] = a;          // new b = old a
        s[2] = b;          // new c = old b
        s[3] = c;          // new d = old c
        s[4] = ne;         // new e = old d + t1
        s[5] = e;          // new f = old e
        s[6] = f;          // new g = old f
        s[7] = g;          // new h = old g
    }

    private static int[] sha256Rounds(int[] state, int[] words, int firstRound, int[] constants) {
        int[] s = state.clone();
        int aligned = Math.min(64, ((firstRound + 7) / 8) * 8);
        for (int i = firstRound; i < aligned; i++) {
            roundCompact(s, words, constants, i);
        }
        for (int i = aligned; i < 64; i += 8) {
            int a = s[0], b = s[1], c = s[2], d = s[3];
            int e = s[4], f = s[5], g = s[6], h = s[7];

            // round 1: t1 = h + S1(e) + Ch;  d += t1;  h = t1 + S0(a) + Maj
            long rE = mulRep(e), rA = mulRep(a);
            int t1 = h + (int) ((rot64(rE, 6) ^ rot64(rE, 11) ^ rot64(rE, 25)) & MASK)
                    + (g ^ (e & (f ^ g))) + constants[i] + words[i];
            d = d + t1;
            h = t1 + (int) ((rot64(rA, 2) ^ rot64(rA, 13) ^ rot64(rA, 22)) & MASK)
                    + ((a & b) | (c & (a | b)));

            // round 2: roles rotated by one
            rE = mulRep(d); rA = mulRep(h);
            t1 = g + (int) ((rot64(rE, 6) ^ rot64(rE, 11) ^ rot64(rE, 25)) & MASK)
                    + (f ^ (d & (e ^ f))) + constants[i + 1] + words[i + 1];
            c = c + t1;
            g = t1 + (int) ((rot64(rA, 2) ^ rot64(rA, 13) ^ rot64(rA, 22)) & MASK)
                    + ((h & a) | (b & (h | a)));

            // round 3
            rE = mulRep(c); rA = mulRep(g);
            t1 = f + (int) ((rot64(rE, 6) ^ rot64(rE, 11) ^ rot64(rE, 25)) & MASK)
                    + (e ^ (c & (d ^ e))) + constants[i + 2] + words[i + 2];
            b = b + t1;
            f = t1 + (int) ((rot64(rA, 2) ^ rot64(rA, 13) ^ rot64(rA, 22)) & MASK)
                    + ((g & h) | (a & (g | h)));

            // round 4
            rE = mulRep(b); rA = mulRep(f);
            t1 = e + (int) ((rot64(rE, 6) ^ rot64(rE, 11) ^ rot64(rE, 25)) & MASK)
                    + (d ^ (b & (c ^ d))) + constants[i + 3] + words[i + 3];
            a = a + t1;
            e = t1 + (int) ((rot64(rA, 2) ^ rot64(rA, 13) ^ rot64(rA, 22)) & MASK)
                    + ((f & g) | (h & (f | g)));

            // round 5
            rE = mulRep(a); rA = mulRep(e);
            t1 = d + (int) ((rot64(rE, 6) ^ rot64(rE, 11) ^ rot64(rE, 25)) & MASK)
                    + (c ^ (a & (b ^ c))) + constants[i + 4] + words[i + 4];
            h = h + t1;
            d = t1 + (int) ((rot64(rA, 2) ^ rot64(rA, 13) ^ rot64(rA, 22)) & MASK)
                    + ((e & f) | (g & (e | f)));

            // round 6
            rE = mulRep(h); rA = mulRep(d);
            t1 = c + (int) ((rot64(rE, 6) ^ rot64(rE, 11) ^ rot64(rE, 25)) & MASK)
                    + (b ^ (h & (a ^ b))) + constants[i + 5] + words[i + 5];
            g = g + t1;
            c = t1 + (int) ((rot64(rA, 2) ^ rot64(rA, 13) ^ rot64(rA, 22)) & MASK)
                    + ((d & e) | (f & (d | e)));

            // round 7
            rE = mulRep(g); rA = mulRep(c);
            t1 = b + (int) ((rot64(rE, 6) ^ rot64(rE, 11) ^ rot64(rE, 25)) & MASK)
                    + (a ^ (g & (h ^ a))) + constants[i + 6] + words[i + 6];
            f = f + t1;
            b = t1 + (int) ((rot64(rA, 2) ^ rot64(rA, 13) ^ rot64(rA, 22)) & MASK)
                    + ((c & d) | (e & (c | d)));

            // round 8
            rE = mulRep(f); rA = mulRep(b);
            t1 = a + (int) ((rot64(rE, 6) ^ rot64(rE, 11) ^ rot64(rE, 25)) & MASK)
                    + (h ^ (f & (g ^ h))) + constants[i + 7] + words[i + 7];
            e = e + t1;
            a = t1 + (int) ((rot64(rA, 2) ^ rot64(rA, 13) ^ rot64(rA, 22)) & MASK)
                    + ((b & c) | (d & (b | c)));

            s[0] = a; s[1] = b; s[2] = c; s[3] = d;
            s[4] = e; s[5] = f; s[6] = g; s[7] = h;
        }
        return s;
    }

    private static void expandSchedule(int[] words, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            int x = words[i - 2], y = words[i - 15];
            int s1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            int s0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            words[i] = s1 + words[i - 7] + s0 + words[i - 16];
        }
    }

    private static int[] blockConstants() {
        int[] bc = K.clone();
        bc[14] = SHA256_ADJUSTMENT;
        return bc;
    }

    private static int[] sha256Init(byte[] salt7) {
        int[] words = new int[64]; // indices 0..30 filled here, 31..63 by expandSchedule
        words[14] = ((salt7[0] & 0xFF) << 16) | ((salt7[1] & 0xFF) << 8) | (salt7[2] & 0xFF);
        words[15] = ((salt7[3] & 0xFF) << 24) | ((salt7[4] & 0xFF) << 16)
                | ((salt7[5] & 0xFF) << 8) | (salt7[6] & 0xFF);
        for (int i = 16; i < 30; i++) {
            int value = SHA256_SCHEDULE[i - 16];
            if (i - 2 >= 14) {
                value += (i == 16) ? (sigma1(words[i - 2]) ^ SIGMA1_ADJUSTMENT)
                                   : sigma1(words[i - 2]);
            }
            if (i - 7 >= 14) value += words[i - 7];
            if (i - 15 >= 14) {
                value += (i == 29) ? (sigma0(words[i - 15]) ^ SIGMA0_ADJUSTMENT)
                                   : sigma0(words[i - 15]);
            }
            words[i] = value;
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14]
                + SHA256_ADJUSTMENT - K[14];
        expandSchedule(words, 31);
        int[] working = sha256Rounds(SHA256_STATE, words, 14, blockConstants());
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = SHA256_INITIAL[i] + working[i];
        return out;
    }

    private static byte[] sha256Digest(byte[] data) {
        if (data.length < 7) throw new IllegalArgumentException("salt too short");
        int length = 57 + data.length;
        byte[] tail = new byte[data.length - 7];
        System.arraycopy(data, 7, tail, 0, tail.length);
        int padZeros = Math.floorMod(55 - length, 64);
        byte[] padded = new byte[tail.length + 1 + padZeros + 8];
        System.arraycopy(tail, 0, padded, 0, tail.length);
        padded[tail.length] = (byte) 0x80;
        long bits = (long) length * 8;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
        }
        int[] state = sha256Init(data);
        for (int off = 0; off < padded.length; off += 64) {
            int[] words = new int[64];
            for (int j = 0; j < 16; j++) {
                words[j] = ((padded[off + 4 * j] & 0xFF) << 24)
                        | ((padded[off + 4 * j + 1] & 0xFF) << 16)
                        | ((padded[off + 4 * j + 2] & 0xFF) << 8)
                        | (padded[off + 4 * j + 3] & 0xFF);
            }
            expandSchedule(words, 16);
            int[] working = sha256Rounds(state, words, 0, K);
            for (int i = 0; i < 8; i++) state[i] += working[i];
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (state[i] >>> 24);
            out[4 * i + 1] = (byte) (state[i] >>> 16);
            out[4 * i + 2] = (byte) (state[i] >>> 8);
            out[4 * i + 3] = (byte) state[i];
        }
        return out;
    }

    // ================= CS64 =================

    private static int rol16(int v) {
        return Integer.rotateLeft(v, 16);
    }

    /** little-endian 32-bit words, zero-padded to an even count */
    private static int[] wordsLe4Even(byte[] data) {
        int pad = Math.floorMod(-data.length, 4);
        byte[] p = new byte[data.length + pad];
        System.arraycopy(data, 0, p, 0, data.length);
        int n = p.length / 4;
        if (n % 2 != 0) n += 1;
        int[] words = new int[n];
        for (int i = 0; i < p.length / 4; i++) {
            words[i] = (p[4 * i] & 0xFF) | ((p[4 * i + 1] & 0xFF) << 8)
                    | ((p[4 * i + 2] & 0xFF) << 16) | ((p[4 * i + 3] & 0xFF) << 24);
        }
        return words;
    }

    /** returns {cs64LowWord, cs64HighWord} (little-endian pair) */
    private static int[] cs64Digest(byte[] data, byte[] digest) {
        int keyA = ((digest[0] & 0xFF) | ((digest[1] & 0xFF) << 8)
                | ((digest[2] & 0xFF) << 16) | ((digest[3] & 0xFF) << 24)) | 1;
        int keyB = ((digest[4] & 0xFF) | ((digest[5] & 0xFF) << 8)
                | ((digest[6] & 0xFF) << 16) | ((digest[7] & 0xFF) << 24)) | 1;
        int factorA = keyA * CS64_FACTOR;
        int factorB = keyB * CS64_FACTOR;

        int nCoeffs = CS64_COEFFS.length;
        // reference feeds b"\x00" + data into the word splitter (little-endian alignment)
        byte[] zeroData = new byte[data.length + 1];
        System.arraycopy(data, 0, zeroData, 1, data.length);
        int[] ws = wordsLe4Even(zeroData);
        int total = nCoeffs + ws.length;   // terms count (even since ws even)
        int[] terms = new int[total];
        int[] offsets = new int[total];
        for (int i = 0; i < nCoeffs; i++) {
            terms[i] = CS64_COEFFS[i][0] * (i % 2 == 0 ? factorA : factorB);
            offsets[i] = CS64_COEFFS[i][1];
        }
        for (int i = 0; i < ws.length; i++) {
            int word = ws[i];
            int term = word * (i % 2 == 0 ? keyA : keyB);
            int offset = rol16(word) * (i % 2 == 0 ? (int) 0xEF0569FBL : (int) 0xC31713DBL);
            if (i == 0) {
                term += CS64_ADJ0 * factorA;
                offset += CS64_ADJ1;
            }
            terms[nCoeffs + i] = term;
            offsets[nCoeffs + i] = offset;
        }

        // Accumulators follow the Python reference exactly: swapAcc/swapLast masked
        // at each step, acc/swapLast accumulate raw (int wrap), final XOR on raw values.
        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < total; i += 2) {
            int x = terms[i], y = terms[i + 1];

            int t = rol16(offsets[i] + x);
            int q = t * 0x76F50F34;
            swapAcc += rol16(q) * 0xC3EFEA97 + q;

            t = rol16(offsets[i + 1] + y);
            q = t * 0x1881CE3C;
            swapLast = rol16(q) * 0x35BD1EC9 + q;
            swapAcc = swapAcc + swapLast;

            state = state * keyA + x;
            state = rol16(state) * 0xCF98B111;
            state = rol16(state) * 0x87085B9F;
            state = rol16(state) * 0x12CEB96D;
            state = rol16(state) * 0x257E1D83;
            acc += state;
            int t2 = state * keyB + y;
            t2 = rol16(t2) * 0xA27416F5;
            t2 = rol16(t2) * 0xD38396FF;
            state = rol16(rol16(t2) * 0x7C932B89) * 0xBFA49F69;
            acc = acc + state;
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }

    private static String tokenHash(byte[] data) {
        byte[] digest = sha256Digest(data);
        int[] cs = cs64Digest(data, digest);
        // reference: struct.pack("<II", w0, w1).hex().upper() — little-endian bytes per word
        byte[] csBytes = new byte[8];
        for (int w = 0; w < 2; w++) {
            csBytes[4 * w]     = (byte) (cs[w]);
            csBytes[4 * w + 1] = (byte) (cs[w] >>> 8);
            csBytes[4 * w + 2] = (byte) (cs[w] >>> 16);
            csBytes[4 * w + 3] = (byte) (cs[w] >>> 24);
        }
        StringBuilder sb = new StringBuilder(81);
        for (byte b : csBytes) sb.append(String.format(Locale.ROOT, "%02X", b));
        sb.append('-');
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02X", b));
        return sb.toString();
    }
}
