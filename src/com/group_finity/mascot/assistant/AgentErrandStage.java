package com.group_finity.mascot.assistant;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.group_finity.mascot.behavior.ErrandLeg;
import com.group_finity.mascot.behavior.UserBehavior;

/**
 * Production {@link AgentErrand.Stage}: opens the two folders as Explorer windows side by side in the
 * upper part of the screen (so the floor stays clear), walks the mascot along the floor using its own
 * {@code Walk} action, and animates a file chip between the windows and the mascot's head.
 * Runs on the agent worker thread; mascot changes are marshalled to the Swing thread.
 */
public final class AgentErrandStage implements AgentErrand.Stage
{
    private final AssistantHost host;
    private final WindowControl windows;
    private Rectangle leftRect, rightRect;
    private AgentFileChip chip;
    private Path heldFile;

    public AgentErrandStage( final AssistantHost host, final WindowControl windows )
    {
        this.host = host;
        this.windows = windows;
    }

    // ---- windows --------------------------------------------------------------

    @Override
    public void openFolders( final Path from, final Path to )
    {
        final Rectangle mb = host.getBounds();
        final List<Rectangle> mons = windows.monitors();
        final Rectangle wa = AgentLayout.screenFor( mons, mb, new Rectangle( 0, 0, 1280, 720 ) );
        final int h = wa.height * 68 / 100, hw = wa.width / 2;
        leftRect = new Rectangle( wa.x, wa.y, hw, h );
        rightRect = new Rectangle( wa.x + hw, wa.y, wa.width - hw, h );
        placeFolder( from, leftRect );
        placeFolder( to, rightRect );
    }

    private void placeFolder( final Path dir, final Rectangle r )
    {
        try
        {
            final Set<Long> before = new HashSet<>();
            for( final WindowControl.Info w : windows.list() ) before.add( w.handle );
            java.awt.Desktop.getDesktop().open( dir.toFile() );
            WindowControl.Info found = null;
            for( int i = 0; i < 30 && found == null; i++ )
            {
                Thread.sleep( 100 );
                found = findFolderWindow( windows.list(), dir, before, true );
            }
            if( found == null ) found = findFolderWindow( windows.list(), dir, before, false );
            if( found == null ) return;
            if( found.minimized ) windows.apply( found, WindowControl.Op.RESTORE );
            final WindowControl.Info cur = windows.current( found.handle );
            if( cur != null && cur.maximized ) windows.apply( cur, WindowControl.Op.RESTORE );
            windows.place( found, r );
        }
        catch( final Throwable t ) { /* the walk still happens; windows just aren't arranged */ }
    }

    /** The Explorer window showing {@code dir}: titled with the folder name, " - File Explorer", or the full path. */
    static WindowControl.Info findFolderWindow( final List<WindowControl.Info> all, final Path dir, final Set<Long> before, final boolean onlyNew )
    {
        final String name = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
        final String n = name.toLowerCase( Locale.ROOT ), full = dir.toString().toLowerCase( Locale.ROOT );
        for( final WindowControl.Info w : all )
        {
            if( onlyNew && before.contains( w.handle ) ) continue;
            final String t = w.title.toLowerCase( Locale.ROOT );
            if( t.equals( n ) || t.equals( full ) || t.equals( n + " - file explorer" ) ) return w;
        }
        return null;
    }

    // ---- walking ----------------------------------------------------------------

    private boolean leg( final String action, final Map<String, String> params, final long timeoutMs )
    {
        final CountDownLatch latch = new CountDownLatch( 1 );
        final boolean[] ok = { false };
        javax.swing.SwingUtilities.invokeLater( () ->
        {
            try
            {
                host.setBehavior( new ErrandLeg( action, params, host.getConfiguration(), r -> { ok[ 0 ] = r; latch.countDown(); } ) );
            }
            catch( final Throwable t ) { latch.countDown(); }
        } );
        try { return latch.await( timeoutMs, TimeUnit.MILLISECONDS ) && ok[ 0 ]; }
        catch( final InterruptedException e ) { Thread.currentThread().interrupt(); return false; }
    }

    private boolean walkTo( final int x )
    {
        final Map<String, String> p = new HashMap<>();
        p.put( "TargetX", Integer.toString( x ) );
        if( leg( "Walk", p, 30_000 ) ) return true;
        leg( "Fall", new HashMap<>(), 8_000 );           // not on the floor? land first, then retry once
        return leg( "Walk", p, 30_000 );
    }

    private int floorX( final Rectangle r )
    {
        final Rectangle mb = host.getBounds();
        final Rectangle wa = AgentLayout.screenFor( windows.monitors(), mb, new Rectangle( 0, 0, 1280, 720 ) );
        return Math.max( wa.x + 40, Math.min( r.x + r.width / 2, wa.x + wa.width - 40 ) );
    }

    @Override public boolean walkToSource() { return leftRect != null && walkTo( floorX( leftRect ) ); }
    @Override public boolean walkToDest()   { return rightRect != null && walkTo( floorX( rightRect ) ); }

    // ---- the file chip -------------------------------------------------------------

    private Point head()
    {
        final Rectangle mb = host.getBounds();
        return new Point( mb.x + mb.width / 2 - AgentFileChip.W / 2, mb.y - AgentFileChip.H - 2 );
    }

    private static Point inWindow( final Rectangle r )
    {
        return new Point( r.x + r.width / 2 - AgentFileChip.W / 2, r.y + r.height / 3 );
    }

    @Override
    public void grab( final Path held )
    {
        heldFile = held;
        final Rectangle r = leftRect != null ? leftRect : host.getBounds();
        chip = AgentFileChip.create( held, inWindow( r ) );
        chip.follow( this::head );
        leg( "Stand", new HashMap<>(), 3_000 );          // a beat of standing while it flies in
        chip.awaitArrival( 2_500 );
    }

    @Override
    public void drop()
    {
        if( chip == null ) return;
        final Rectangle r = rightRect != null ? rightRect : host.getBounds();
        final Point p = inWindow( r );
        chip.follow( () -> p );
        chip.awaitArrival( 2_500 );
        try { Thread.sleep( 250 ); } catch( final InterruptedException e ) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void finish()
    {
        if( chip != null ) { chip.dispose(); chip = null; }
        javax.swing.SwingUtilities.invokeLater( () ->
        {
            try
            {
                final com.group_finity.mascot.config.Configuration c = host.getConfiguration();
                host.setBehavior( c.buildBehavior( c.getSchema().getString( UserBehavior.BEHAVIOURNAME_FALL ) ) );
            }
            catch( final Throwable t ) { /* the mascot is gone or mid-drag; nothing to hand back */ }
        } );
    }
}
