package com.exo.musicplayer.desktop.system

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import kotlin.concurrent.thread

/**
 * "Now playing" as a desktop notification on Linux, when a song starts - for
 * when Lucense is behind other windows. Off unless chosen in Settings.
 *
 * Through the freedesktop notification service, which GNOME, KDE, COSMIC and the
 * rest all provide on the session bus. Each song replaces the last one's
 * notification rather than stacking up under it, and it shows the song's cover
 * where there is one. Sent on a thread of its own: the bus is a round trip, and
 * whoever asks is the UI thread.
 */
class SongNotifications(private val connect: () -> DBusConnection = { Mpris.sessionBus() }) {

    @Volatile private var bus: DBusConnection? = null
    /** The last notification's id, so the next replaces it. */
    @Volatile private var lastId = 0L
    @Volatile var problem: Throwable? = null
        private set

    /** Shows [title] by [artist], with the picture at [iconUrl] (a file:// address) if given. */
    fun show(title: String, artist: String?, iconUrl: String?) {
        thread(name = "lucense-notify", isDaemon = true) {
            runCatching {
                val connection = bus ?: connect().also { bus = it }
                val service = connection.getRemoteObject(SERVICE, PATH, Notifications::class.java)
                val hints = linkedMapOf<String, Variant<*>>(
                    // Not kept in the notification list: it is out of date at the next song.
                    "transient" to Variant(true),
                    "category" to Variant("x-gnome.music"),
                    "desktop-entry" to Variant(Mpris.DESKTOP_ENTRY)
                )
                iconUrl?.let { hints["image-path"] = Variant(it) }
                val id = service.Notify(
                    "Lucense",
                    UInt32(lastId),
                    iconUrl ?: "lucense",
                    title,
                    artist.orEmpty(),
                    emptyList(),
                    hints,
                    EXPIRE_MS
                )
                lastId = id.toLong()
                problem = null
            }.onFailure {
                problem = it
                System.err.println("Lucense: no song notification (${it.message})")
                runCatching { bus?.close() }
                bus = null
            }
        }
    }

    fun close() {
        runCatching { bus?.close() }
        bus = null
    }

    @DBusInterfaceName(SERVICE)
    interface Notifications : DBusInterface {
        @Suppress("FunctionName")
        fun Notify(
            appName: String,
            replacesId: UInt32,
            appIcon: String,
            summary: String,
            body: String,
            actions: List<String>,
            hints: Map<String, Variant<*>>,
            expireTimeout: Int
        ): UInt32
    }

    companion object {
        const val SERVICE = "org.freedesktop.Notifications"
        const val PATH = "/org/freedesktop/Notifications"
        /** Long enough to read, short enough not to linger over the next. */
        private const val EXPIRE_MS = 5_000
    }
}
