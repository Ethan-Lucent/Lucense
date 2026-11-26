package com.exo.musicplayer.desktop.ui

import com.exo.musicplayer.desktop.system.Platform
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window
import java.lang.reflect.Method

/**
 * Lucense's own title strip and edges, with the Linux desktop's window manager
 * doing the moving - as Firefox and every GTK app have it.
 *
 * Moved by hand, a new position for every mouse event, the window manager only
 * ever sees a window being put somewhere, so none of what it does for a window
 * it moves itself happens: dragged to the top of the screen it doesn't
 * maximise, to a side it doesn't take that half, a maximised one dragged away
 * doesn't come back to its own size, and it trails the pointer. So instead,
 * dragged by its title strip, the window manager is asked to take it (EWMH's
 * _NET_WM_MOVERESIZE) and moves it itself, snapping and all; pressed on an
 * edge, it sizes it the same way; right-clicked, its own window menu shows
 * (_GTK_SHOW_WINDOW_MENU). Maximising is asked of it too, through the frame's
 * state ([WindowFrame.toggleMaximize]), and whether the window is maximised is
 * whatever the window manager says.
 *
 * The request has to come over AWT's own connection to the X server, with
 * AWT's lock held: a button held down holds the pointer for the window it was
 * pressed in, and only that connection can let go of it - which the window
 * manager needs, to take the pointer itself. That is two reaches into AWT's
 * insides, sun.awt.X11 and sun.awt, which build.gradle.kts opens on Linux. On
 * Wayland, Lucense is an X11 window under Xwayland and the compositor (GNOME's,
 * KDE's) takes the same request. With no window manager that knows it, or no
 * way into AWT, [install] says so and the strip moves the window by hand.
 */
object LinuxFrame {

    /** Which way an edge sizes the window, as EWMH numbers them. */
    const val SIZE_TOP_LEFT = 0
    const val SIZE_TOP = 1
    const val SIZE_TOP_RIGHT = 2
    const val SIZE_RIGHT = 3
    const val SIZE_BOTTOM_RIGHT = 4
    const val SIZE_BOTTOM = 5
    const val SIZE_BOTTOM_LEFT = 6
    const val SIZE_LEFT = 7
    private const val MOVE = 8

    /** EWMH's "a normal application asked", as against a pager. */
    private const val FROM_APPLICATION = 1L

    /** AWT's connection to the X server, and the lock everything using it holds. */
    private class Awt(val display: Long, val lock: Method, val unlock: Method)

    private val awt: Awt? by lazy {
        if (!Platform.isLinux) return@lazy null
        runCatching {
            val toolkit = Class.forName("sun.awt.X11.XToolkit")
            check(toolkit.isInstance(java.awt.Toolkit.getDefaultToolkit())) { "not AWT's X11 toolkit" }
            val display = toolkit.getMethod("getDisplay").invoke(null) as Long
            check(display != 0L) { "no X display" }
            val sun = Class.forName("sun.awt.SunToolkit")
            val found = Awt(display, sun.getMethod("awtLock"), sun.getMethod("awtUnlock"))
            // Tried once now, so a JVM started without the way in says so here and not mid-drag.
            found.lock.invoke(null)
            found.unlock.invoke(null)
            found
        }.onFailure {
            System.err.println("Lucense: the window manager can't be asked to move the window; it moves by hand (${it.message})")
        }.getOrNull()
    }

    private val x11: X11? by lazy { runCatching { X11.INSTANCE }.getOrNull() }

    /** The one call needed that JNA's X11 doesn't have. */
    @Suppress("FunctionName")
    private interface Grabs : Library {
        fun XUngrabPointer(display: X11.Display, time: NativeLong): Int
    }

    private val grabs: Grabs? by lazy {
        runCatching { Native.load("X11", Grabs::class.java) }
            .onFailure { trace { "libX11 not loaded: ${it.message}" } }
            .getOrNull()
    }

    /** The window manager takes moves and sizes from Lucense; asked of it once. */
    val available: Boolean by lazy { grabs != null && supports("_NET_WM_MOVERESIZE") }

    private val windowMenu: Boolean by lazy { available && supports("_GTK_SHOW_WINDOW_MENU") }

    /** Hands [window]'s moving, sizing and maximising to the window manager. True when it took. */
    fun install(window: Window, frame: WindowFrame): Boolean {
        if (!Platform.isLinux) return false
        if (!available || window !is java.awt.Frame) {
            System.err.println("Lucense: the window manager doesn't take moves from Lucense; the window moves by hand")
            return false
        }
        frame.attachManaged()
        System.err.println("Lucense: the window manager moves, sizes and snaps the window")
        return true
    }

    /** -Dlucense.frame.debug=true: every request to the window manager, and why one wasn't made. */
    private val debug = System.getProperty("lucense.frame.debug") == "true"

    private fun trace(message: () -> String) {
        if (debug) System.err.println("Lucense frame: " + message())
    }

    /** Asks the window manager to move [window] with the pointer, from where the button went down. */
    fun startMove(window: Window): Boolean = moveResize(window, MOVE)

    /** Asks the window manager to size [window] from the edge [direction] names (SIZE_…). */
    fun startResize(window: Window, direction: Int): Boolean = moveResize(window, direction)

    /** The window manager's own menu for [window] - minimise, always on top, move to a workspace - at the pointer. */
    fun showWindowMenu(window: Window): Boolean {
        if (!windowMenu) return false
        return locked { x, display ->
            val target = topLevel(x, display, window) ?: return@locked false
            val at = pointer(x, display) ?: return@locked false
            letGo(display)
            // The first is which pointer it was; window managers take the menu from any.
            send(x, display, target, "_GTK_SHOW_WINDOW_MENU", 0L, at.x.toLong(), at.y.toLong())
        } ?: false
    }

    private fun moveResize(window: Window, direction: Int): Boolean {
        if (!available) return false
        return locked { x, display ->
            val target = topLevel(x, display, window) ?: return@locked false.also { trace { "no top-level window" } }
            val at = pointer(x, display) ?: return@locked false.also { trace { "pointer not found" } }
            // Let go already: a move started now would follow a pointer no longer holding it.
            if (at.buttons and X11.Button1Mask == 0) return@locked false.also { trace { "button 1 not down (mask ${at.buttons})" } }
            // The press gave this window the pointer; the window manager takes it from here.
            letGo(display)
            send(x, display, target, "_NET_WM_MOVERESIZE", at.x.toLong(), at.y.toLong(), direction.toLong(), 1L, FROM_APPLICATION)
                .also { sent -> trace { "_NET_WM_MOVERESIZE dir=$direction at ${at.x},${at.y} to 0x${java.lang.Long.toHexString(target.toLong())}: sent=$sent" } }
        } ?: false
    }

    /** Lets go of the pointer a press gave this connection, for the window manager to take. */
    private fun letGo(display: X11.Display) {
        grabs?.XUngrabPointer(display, NativeLong(X11.CurrentTime.toLong()))
    }

    /** [block] with AWT's connection, under AWT's lock; null when there is no way in. */
    private fun <T> locked(block: (X11, X11.Display) -> T): T? {
        val awt = awt ?: return null
        val x = x11 ?: return null
        val display = X11.Display().apply { pointer = Pointer(awt.display) }
        awt.lock.invoke(null)
        try {
            return runCatching { block(x, display) }
                .onFailure { System.err.println("Lucense: asking the window manager failed (${it.message})") }
                .getOrNull()
        } finally {
            awt.unlock.invoke(null)
        }
    }

    /**
     * The atom named [name], made if the server hasn't one yet - harmless, a name is all it is.
     *
     * Never asked with only_if_exists = true: JNA hands Java's true to Xlib as
     * 0xff, the X server refuses anything but 0 or 1 (BadValue), and on AWT's
     * connection AWT's error handler swallows that, leaving a made-up atom.
     * Every lookup then missed - _NET_WM_MOVERESIZE looked unsupported and
     * Lucense never let the window manager move it.
     */
    private fun atom(x: X11, display: X11.Display, name: String): X11.Atom? =
        x.XInternAtom(display, name, false)?.takeIf { it.toLong() != 0L }

    /** Whether the window manager lists [name] among what it supports (_NET_SUPPORTED). */
    private fun supports(name: String): Boolean {
        if (!Platform.isLinux) return false
        return locked { x, display ->
            val list = atom(x, display, "_NET_SUPPORTED") ?: return@locked false
            val wanted = atom(x, display, name) ?: return@locked false
            val supported = atoms(x, display, x.XDefaultRootWindow(display), list)
            trace { "$name=${wanted.toLong()} among ${supported.size} supported: ${wanted.toLong() in supported}" }
            supported.contains(wanted.toLong())
        } ?: false
    }

    private fun atoms(x: X11, display: X11.Display, window: X11.Window, property: X11.Atom): Set<Long> {
        val type = X11.AtomByReference()
        val format = IntByReference()
        val count = NativeLongByReference()
        val after = NativeLongByReference()
        val data = PointerByReference()
        val status = x.XGetWindowProperty(
            display, window, property, NativeLong(0), NativeLong(4096), false,
            X11.XA_ATOM, type, format, count, after, data
        )
        val values = data.value ?: return emptySet()
        try {
            if (status != X11.Success || format.value != 32) return emptySet()
            // Format 32 comes back as C longs, whatever their width.
            return (0 until count.value.toInt()).mapTo(HashSet()) { values.getNativeLong(it.toLong() * NativeLong.SIZE).toLong() }
        } finally {
            x.XFree(values)
        }
    }

    /**
     * The window the window manager manages for [window]: AWT draws into a
     * window inside it, and a window manager that frames its windows puts a
     * frame around it. The one with WM_STATE, which the window manager sets.
     */
    private fun topLevel(x: X11, display: X11.Display, window: Window): X11.Window? {
        var id = runCatching { Native.getWindowID(window) }.getOrDefault(0L)
        if (id == 0L) return null
        val state = atom(x, display, "WM_STATE")
        val root = X11.WindowByReference()
        val parent = X11.WindowByReference()
        val children = PointerByReference()
        val count = IntByReference()
        // Never more than a few levels; bounded all the same.
        repeat(8) {
            val here = X11.Window(id)
            if (state != null && hasProperty(x, display, here, state)) return here
            if (x.XQueryTree(display, here, root, parent, children, count) == 0) return here
            children.value?.let { x.XFree(it) }
            val up = parent.value?.toLong() ?: 0L
            if (up == 0L || up == root.value?.toLong()) return here
            id = up
        }
        return X11.Window(id)
    }

    private fun hasProperty(x: X11, display: X11.Display, window: X11.Window, property: X11.Atom): Boolean {
        val type = X11.AtomByReference()
        val format = IntByReference()
        val count = NativeLongByReference()
        val after = NativeLongByReference()
        val data = PointerByReference()
        val status = x.XGetWindowProperty(
            display, window, property, NativeLong(0), NativeLong(0), false,
            X11.Atom(X11.AnyPropertyType.toLong()), type, format, count, after, data
        )
        data.value?.let { x.XFree(it) }
        return status == X11.Success && (type.value?.toLong() ?: 0L) != 0L
    }

    private class At(val x: Int, val y: Int, val buttons: Int)

    /** Where the pointer is on the screen, in the X server's own pixels, and which buttons are down. */
    private fun pointer(x: X11, display: X11.Display): At? {
        val rootX = IntByReference()
        val rootY = IntByReference()
        val mask = IntByReference()
        val found = x.XQueryPointer(
            display, x.XDefaultRootWindow(display),
            X11.WindowByReference(), X11.WindowByReference(),
            rootX, rootY, IntByReference(), IntByReference(), mask
        )
        return if (found) At(rootX.value, rootY.value, mask.value) else null
    }

    /** A client message about [window] to the root window, which is how a window manager is asked. */
    private fun send(x: X11, display: X11.Display, window: X11.Window, type: String, vararg data: Long): Boolean {
        val atom = atom(x, display, type) ?: return false
        val message = X11.XClientMessageEvent().apply {
            this.type = X11.ClientMessage
            serial = NativeLong(0)
            send_event = 1
            this.display = display
            this.window = window
            message_type = atom
            format = 32
            this.data.setType(Array<NativeLong>::class.java)
            for (i in 0 until 5) this.data.l[i] = NativeLong(data.getOrElse(i) { 0L })
        }
        val event = X11.XEvent()
        event.setTypedValue(message)
        val mask = NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong())
        val sent = x.XSendEvent(display, x.XDefaultRootWindow(display), 0, mask, event) != 0
        x.XFlush(display)
        return sent
    }
}
