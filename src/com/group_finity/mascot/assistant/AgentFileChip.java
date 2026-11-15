package com.group_finity.mascot.assistant;

import java.awt.*;
import java.nio.file.Path;
import java.util.function.Supplier;
import javax.swing.*;

/**
 * The little file icon + name that flies out of a folder window, rides on the mascot and drops into
 * another folder. It eases toward whatever point its target supplier returns, so the mascot only has to
 * say where it is. Headless or any failure: every method is a harmless no-op.
 */
public final class AgentFileChip
{
    private volatile JWindow win;
    private volatile Supplier<Point> target = () -> null;
    private volatile Timer timer;
    private double fx, fy;
    private boolean placed = false;
    private volatile boolean near = false;

    public static AgentFileChip create( final Path file, final Point start )
    {
        final AgentFileChip c = new AgentFileChip();
        if( GraphicsEnvironment.isHeadless() ) return c;
        try { SwingUtilities.invokeAndWait( () -> c.build( file, start ) ); }
        catch( final Throwable t ) { c.win = null; }
        return c;
    }

    public static final int W = 190, H = 34;

    private void build( final Path file, final Point start )
    {
        final Icon icon = FileIcons.of( file );
        String name = file.getFileName().toString();
        if( name.length() > 24 ) name = name.substring( 0, 21 ) + "...";
        final JLabel l = new JLabel( name, icon, SwingConstants.LEFT );
        l.setForeground( new Color( 235, 235, 240 ) );
        l.setFont( l.getFont().deriveFont( Font.PLAIN, 12f ) );
        final JPanel p = new JPanel( new BorderLayout() )
        {
            @Override protected void paintComponent( final Graphics g )
            {
                final Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
                g2.setColor( new Color( 30, 30, 36, 235 ) );
                g2.fillRoundRect( 0, 0, getWidth() - 1, getHeight() - 1, 14, 14 );
                g2.setColor( new Color( 255, 255, 255, 70 ) );
                g2.drawRoundRect( 0, 0, getWidth() - 1, getHeight() - 1, 14, 14 );
                g2.dispose();
            }
        };
        p.setOpaque( false );
        p.setBorder( BorderFactory.createEmptyBorder( 4, 10, 4, 10 ) );
        p.add( l );
        final JWindow w = new JWindow();
        w.setAlwaysOnTop( true );
        w.setFocusableWindowState( false );
        w.setBackground( new Color( 0, 0, 0, 0 ) );
        w.setContentPane( p );
        w.setSize( W, H );
        fx = start.x; fy = start.y;
        w.setLocation( start );
        w.setVisible( true );
        win = w;
        final Timer t = new Timer( 16, e -> step() );
        timer = t;
        t.start();
    }

    private void step()
    {
        final JWindow w = win;
        final Point tg = target.get();
        if( w == null || tg == null ) return;
        final double dx = tg.x - fx, dy = tg.y - fy;
        final double d = Math.hypot( dx, dy );
        near = d < 8;
        if( d > 0.5 )
        {
            final double s = Math.max( Math.min( d, 3.0 ), d * 0.18 );
            fx += dx / d * s;
            fy += dy / d * s;
            w.setLocation( (int) Math.round( fx ), (int) Math.round( fy ) );
        }
    }

    /** Where the chip should be (top-left); it glides there. */
    public void follow( final Supplier<Point> s ) { target = s == null ? () -> null : s; near = false; }

    /** Block until the chip has arrived (or the timeout), so a fly-in reads as a fly-in. */
    public void awaitArrival( final long timeoutMs )
    {
        if( win == null ) return;
        final long end = System.currentTimeMillis() + timeoutMs;
        while( !near && System.currentTimeMillis() < end )
            try { Thread.sleep( 20 ); } catch( final InterruptedException e ) { Thread.currentThread().interrupt(); return; }
    }

    public void dispose()
    {
        final Timer t = timer;
        final JWindow w = win;
        win = null;
        SwingUtilities.invokeLater( () -> { if( t != null ) t.stop(); if( w != null ) w.dispose(); } );
    }

    /** System file icon, with a plain fallback. */
    static final class FileIcons
    {
        static Icon of( final Path f )
        {
            try { return javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemIcon( f.toFile() ); }
            catch( final Throwable t ) { return UIManager.getIcon( "FileView.fileIcon" ); }
        }
    }
}
