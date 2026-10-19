package cn.shaohsmoni.voxassist

import java.util.Collections

/**
 * Process-wide log buffer so the foreground service can push state/errors into
 * the MainActivity log pane (otherwise they only reach logcat and the user sees
 * nothing when something goes wrong).
 */
object AppLog {
    private val lines = Collections.synchronizedList(ArrayList<String>())

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun add(s: String) {
        lines.add(s)
        try {
            listener?.invoke(s)
        } catch (_: Exception) {
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { ArrayList(lines) }
}
