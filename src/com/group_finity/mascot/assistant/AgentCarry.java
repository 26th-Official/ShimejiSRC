package com.group_finity.mascot.assistant;

import java.awt.Rectangle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * "Carry this window around": a background thread keeps another app's window glued above the mascot
 * while the mascot goes about its normal behaviors. Runs on its own thread, never the tick (moving
 * another process's window is a synchronous call that can stall if that app hangs).
 *
 * Ends on: "drop it", the time limit (default 2 min, max 10), the window closing, the kill switch,
 * or the agent being turned off. The carried window is also hidden from the mascots' window-surface
 * scan while carried so they don't try to stand on or climb the thing they are holding.
 */
public final class AgentCarry
{
    public static final long DEFAULT_MS = 120_000L, MAX_MS = 600_000L, STEP_MS = 33L;

    private static final Set<Long> CARRIED = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<Long, AgentCarry> ACTIVE = new ConcurrentHashMap<>();

    private final long handle;
    private volatile boolean stop = false;

    private AgentCarry( final long handle ) { this.handle = handle; }

    /** True while this window handle is being carried (used by the window scan). */
    public static boolean isCarried( final long handle ) { return !CARRIED.isEmpty() && CARRIED.contains( handle ); }

    public static int activeCount() { return ACTIVE.size(); }

    /** Stop every carry; returns how many were active. */
    public static int dropAll()
    {
        int n = 0;
        for( final AgentCarry c : ACTIVE.values() ) { c.stop = true; n++; }
        return n;
    }

    /**
     * Start carrying {@code w}. {@code onEnd} gets a short reason ("dropped", "time", "closed", "off").
     * Replaces an existing carry of the same window.
     */
    public static void start( final WindowControl wc, final WindowControl.Info w, final Supplier<Rectangle> mascot,
                              final Supplier<Rectangle> screen, final java.util.function.BooleanSupplier stillEnabled, final long durationMs, final java.util.function.Consumer<String> onEnd )
    {
        final AgentCarry old = ACTIVE.get( w.handle );
        if( old != null ) old.stop = true;
        final AgentCarry c = new AgentCarry( w.handle );
        ACTIVE.put( w.handle, c );
        CARRIED.add( w.handle );
        final long until = System.currentTimeMillis() + Math.max( 5_000L, Math.min( MAX_MS, durationMs ) );
        final Thread t = new Thread( () ->
        {
            String reason = "dropped";
            try
            {
                while( true )
                {
                    if( c.stop ) { reason = "dropped"; break; }
                    if( !stillEnabled.getAsBoolean() ) { reason = "off"; break; }
                    if( System.currentTimeMillis() >= until ) { reason = "time"; break; }
                    final Rectangle m = mascot.get(), s = screen.get();
                    if( m != null && s != null && !wc.carry( w, m, s ) ) { reason = "closed"; break; }
                    Thread.sleep( STEP_MS );
                }
            }
            catch( final InterruptedException ignored ) { }
            catch( final Throwable ignored ) { reason = "closed"; }
            finally
            {
                if( ACTIVE.get( w.handle ) == c ) { ACTIVE.remove( w.handle ); CARRIED.remove( w.handle ); }
                if( onEnd != null && !( "dropped".equals( reason ) && c.replaced() ) ) onEnd.accept( reason );
            }
        }, "agent-carry" );
        t.setDaemon( true );
        t.start();
    }

    private boolean replaced() { return ACTIVE.get( handle ) != this && ACTIVE.containsKey( handle ); }

    /** Bounds of the screen the mascot is on (whole screen, not just work area: a window may sit over the taskbar). */
    public static Rectangle screenOf( final Rectangle mascot )
    {
        try
        {
            final java.awt.GraphicsDevice[] ds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            Rectangle best = null;
            long bestArea = -1;
            for( final java.awt.GraphicsDevice d : ds )
            {
                final Rectangle b = d.getDefaultConfiguration().getBounds();
                final Rectangle i = b.intersection( mascot );
                final long a = i.isEmpty() ? 0 : (long) i.width * i.height;
                if( a > bestArea ) { bestArea = a; best = b; }
            }
            if( best != null ) return best;
        }
        catch( final Throwable ignored ) { }
        return new Rectangle( 0, 0, 1920, 1080 );
    }
}
