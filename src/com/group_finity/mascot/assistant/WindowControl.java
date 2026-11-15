package com.group_finity.mascot.assistant;

import java.awt.Rectangle;
import java.util.List;

/**
 * What the agent may do to other applications' windows. Deliberately small: no closing (could lose
 * unsaved work) and no keystroke/click injection. The Windows backend lives in src_win and is found
 * by name, so other platforms simply report "not available".
 */
public interface WindowControl
{
    /** A top-level window the user could reasonably mean. */
    final class Info
    {
        public final long handle;
        public final String title;
        public final Rectangle bounds;
        public final boolean minimized;

        public Info( final long handle, final String title, final Rectangle bounds, final boolean minimized )
        {
            this.handle = handle;
            this.title = title;
            this.bounds = bounds;
            this.minimized = minimized;
        }
    }

    enum Op { FOCUS, MINIMIZE, MAXIMIZE, RESTORE, LEFT, RIGHT, TOP, BOTTOM, CENTER }

    /** Visible, titled, user-facing windows (never this app's own windows, taskbar or desktop). */
    List<Info> list();

    /** Apply the operation; false if the system refused it. */
    boolean apply( Info window, Op op );

    /**
     * Hold the window just above the mascot: restores it if maximized/minimized, then moves it so it
     * hangs over the mascot's head, clamped to {@code screen}. False once the window is gone.
     */
    boolean carry( Info window, Rectangle mascot, Rectangle screen );

    /** The backend for this platform, or null when there is none. */
    static WindowControl get()
    {
        try
        {
            return (WindowControl) Class.forName( "com.group_finity.mascot.win.WindowsWindowControl" )
                .getDeclaredConstructor().newInstance();
        }
        catch( final Throwable t ) { return null; }
    }
}
