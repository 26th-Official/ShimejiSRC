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
        public final boolean maximized;

        public Info( final long handle, final String title, final Rectangle bounds, final boolean minimized )
        {
            this( handle, title, bounds, minimized, false );
        }

        public Info( final long handle, final String title, final Rectangle bounds, final boolean minimized, final boolean maximized )
        {
            this.handle = handle;
            this.title = title;
            this.bounds = bounds;
            this.minimized = minimized;
            this.maximized = maximized;
        }
    }

    enum Op { FOCUS, MINIMIZE, MAXIMIZE, RESTORE, LEFT, RIGHT, TOP, BOTTOM, CENTER }

    /** Visible, titled, user-facing windows (never this app's own windows, taskbar or desktop). */
    List<Info> list();

    /** Apply the operation; false if the system refused it. */
    boolean apply( Info window, Op op );

    /** Fresh state of one window, or null once it no longer exists. */
    Info current( long handle );

    /** Move the window's top-left corner to (x, y), keeping its size. False if it is gone. */
    boolean moveTo( Info window, int x, int y );

    /** Work areas of all monitors in Win32 coordinates (the same space as window bounds), left to right. */
    default List<Rectangle> monitors() { return List.of(); }

    /** Move and resize the window to {@code r}. False if it is gone. */
    default boolean place( final Info window, final Rectangle r ) { return moveTo( window, r.x, r.y ); }

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
