package cn.shaohsmoni.voxassist;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Streams TTS audio from Microsoft Edge Read-Aloud (speech.platform.bing.com).
 *
 * Port of rany2/edge-tts protocol:
 *   wss://.../edge/v1?TrustedClientToken=..&ConnectionId=..&Sec-MS-GEC=..&Sec-MS-GEC-Version=..
 *   1) text frame: Path:speech.config (json)
 *   2) text frame: Path:ssml (ssml payload)
 *   3) binary frames: "Path:audio" -> mp3 bytes; "Path:turn.end" -> done
 *
 * The mp3 stream is handed to a sink callback as it arrives.
 */
public class EdgeTtsClient {

    public interface Sink {
        /** Called with each mp3 chunk; last call has isFinal=true. */
        void onAudio(byte[] mp3, boolean isFinal);
        void onError(String message);
    }

    private static final String BASE_URL =
            "speech.platform.bing.com/consumer/speech/synthesize/readaloud";
    private static final String SEC_MS_GEC_VERSION = "2-143.0.3650.75"; // matches edge-tts 7.x
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    private final OkHttpClient client = new OkHttpClient.Builder().build();

    /** @param rate like "+20%" */
    public void synthesize(String text, String voice, String rate, Sink sink) {
        String connectId = UUID.randomUUID().toString().replace("-", "");
        String url = "wss://" + BASE_URL + "/edge/v1?TrustedClientToken="
                + EdgeDrm.TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + connectId
                + "&Sec-MS-GEC=" + EdgeDrm.generateToken()
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        String muid = randomHex(16).toUpperCase(Locale.ROOT);

        Request request = new Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + muid + ";")
                .build();

        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voice + "'>"
                + "<prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>"
                + escapeXml(text)
                + "</prosody></voice></speak>";

        String requestId = UUID.randomUUID().toString().replace("-", "");
        String ts = jsDateString();
        String ssmlFrame = "X-RequestId:" + requestId + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + ts + "Z\r\n"
                + "Path:ssml\r\n\r\n" + ssml;
        String configFrame = "X-Timestamp:" + ts + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";

        client.newWebSocket(request, new WebSocketListener() {
            private boolean sent = false;
            private boolean gotAudio = false;

            @Override public void onOpen(WebSocket ws, Response response) {
                ws.send(configFrame);
                ws.send(ssmlFrame);
                sent = true;
            }

            @Override public void onMessage(WebSocket ws, String text) {
                // Path:turn.end -> close
                if (text.contains("Path:turn.end")) {
                    sink.onAudio(new byte[0], true);
                    ws.close(1000, null);
                }
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                // binary frame: 2-byte big-endian header length, then headers, then payload
                if (bytes.size() < 2) return;
                int headerLen = ((bytes.getByte(0) & 0xFF) << 8) | (bytes.getByte(1) & 0xFF);
                String headers = bytes.substring(2, 2 + headerLen).utf8();
                if (headers.contains("Path:audio")) {
                    if (bytes.size() > 2 + headerLen) {
                        gotAudio = true;
                        byte[] payload = bytes.substring(2 + headerLen).toByteArray();
                        sink.onAudio(payload, false);
                    }
                }
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                sink.onError(t.getMessage() == null ? t.toString() : t.getMessage());
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                if (!gotAudio) {
                    // handled by sink already if turn.end came through
                }
            }
        });
    }

    private static String randomHex(int bytes) {
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (int i = 0; i < bytes; i++) {
            sb.append(String.format(Locale.ROOT, "%02x", (int) (Math.random() * 255)));
        }
        return sb.toString();
    }

    /** Javascript-style date string: "Mon Oct 18 2026 12:00:00 GMT+0000 (Coordinated Universal Time)" */
    private static String jsDateString() {
        SimpleDateFormat fmt = new SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }
}
