package com.group_finity.mascot.assistant;

import java.awt.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.*;

/**
 * A small yes/no bubble that follows the mascot. If the user does nothing it fades out and counts
 * as "no", so an unanswered question never lets an action through. Hovering pauses the countdown.
 * {@link #ask} blocks: call it from a worker thread, never the EDT.
 */
public final class AgentConfirmBubble
{
    private AgentConfirmBubble() { }

    public static final long FADE_MS = 4000L;
    public static final long DEFAULT_TIMEOUT_MS = 25_000L;

    /** Window opacity for the time left: solid until the last {@link #FADE_MS}, then fading to a faint 0.15. */
    static float opacityFor( final long remainingMs )
    {
        if( remainingMs >= FADE_MS ) return 1f;
        return (float) Math.max( 0.15, Math.max( 0, remainingMs ) / (double) FADE_MS );
    }

    /** True only if the user clicked Yes before the bubble faded away. Headless/any failure = false. */
    public static boolean ask( final String title, final String message, final Supplier<Rectangle> anchor, final long timeoutMs )
    {
        if( GraphicsEnvironment.isHeadless() ) return false;
        final CountDownLatch done = new CountDownLatch( 1 );
        final boolean[] yes = { false };
        try
        {
            SwingUtilities.invokeLater( () ->
            {
                try { show( title, message, anchor, timeoutMs, yes, done ); }
                catch( final Throwable t ) { done.countDown(); }
            } );
            // hover can extend the countdown, so wait generously; the bubble itself decides when it is over
            done.await( 10 * 60_000L, TimeUnit.MILLISECONDS );
        }
        catch( final InterruptedException e ) { Thread.currentThread().interrupt(); return false; }
        synchronized( yes ) { return yes[ 0 ]; }
    }

    private static String esc( final String s )
    {
        return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" ).replace( "\n", "<br>" );
    }

    private static void show( final String title, final String message, final Supplier<Rectangle> anchor,
                              final long timeoutMs, final boolean[] yes, final CountDownLatch done )
    {
        final Color bg = new Color( 30, 30, 36, 238 ), fg = new Color( 235, 235, 240 );
        final JWindow win = new JWindow();
        win.setAlwaysOnTop( true );
        win.setFocusableWindowState( false );   // never steal the user's typing focus
        win.setBackground( new Color( 0, 0, 0, 0 ) );

        final JPanel panel = new JPanel( new BorderLayout( 0, 6 ) )
        {
            @Override protected void paintComponent( final Graphics g )
            {
                final Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
                g2.setColor( bg );
                g2.fillRoundRect( 0, 0, getWidth() - 1, getHeight() - 1, 18, 18 );
                g2.setColor( new Color( 255, 255, 255, 60 ) );
                g2.drawRoundRect( 0, 0, getWidth() - 1, getHeight() - 1, 18, 18 );
                g2.dispose();
            }
        };
        panel.setOpaque( false );
        panel.setBorder( BorderFactory.createEmptyBorder( 10, 12, 10, 12 ) );
        final JLabel text = new JLabel( "<html><div style='width:240px'><b>" + esc( title ) + "</b><br>" + esc( message ) + "</div></html>" );
        text.setForeground( fg );
        text.setFont( text.getFont().deriveFont( Font.PLAIN, 13f ) );
        panel.add( text, BorderLayout.CENTER );

        final JPanel row = new JPanel( new FlowLayout( FlowLayout.RIGHT, 8, 0 ) );
        row.setOpaque( false );
        final JButton no = button( "No", new Color( 90, 50, 55 ) ), ok = button( "Yes", new Color( 45, 100, 70 ) );
        row.add( no );
        row.add( ok );
        panel.add( row, BorderLayout.SOUTH );
        win.setContentPane( panel );
        win.pack();

        final long[] deadline = { System.currentTimeMillis() + timeoutMs };
        final Timer[] tm = new Timer[ 1 ];
        final Runnable finish = () ->
        {
            if( tm[ 0 ] != null ) tm[ 0 ].stop();
            win.dispose();
            done.countDown();
        };
        ok.addActionListener( e -> { synchronized( yes ) { yes[ 0 ] = true; } finish.run(); } );
        no.addActionListener( e -> finish.run() );

        tm[ 0 ] = new Timer( 50, e ->
        {
            final long now = System.currentTimeMillis();
            try
            {
                final PointerInfo pi = MouseInfo.getPointerInfo();
                if( pi != null && win.getBounds().contains( pi.getLocation() ) ) deadline[ 0 ] = Math.max( deadline[ 0 ], now + FADE_MS + 2000 );
            }
            catch( final Throwable ignored ) { }
            if( now >= deadline[ 0 ] || AgentPolicy.isHalted() ) { finish.run(); return; }
            try { win.setOpacity( opacityFor( deadline[ 0 ] - now ) ); } catch( final Throwable ignored ) { }
            place( win, anchor.get() );
        } );
        place( win, anchor.get() );
        win.setVisible( true );
        tm[ 0 ].start();
    }

    private static JButton button( final String label, final Color c )
    {
        final JButton b = new JButton( label );
        b.setFocusable( false );
        b.setBackground( c );
        b.setForeground( Color.WHITE );
        b.setBorder( BorderFactory.createEmptyBorder( 4, 14, 4, 14 ) );
        b.setContentAreaFilled( true );
        b.setOpaque( true );
        return b;
    }

    /** Centre above the mascot, clamped to the screen it is on. */
    static void place( final Window win, final Rectangle mascot )
    {
        final Dimension d = win.getSize();
        Rectangle screen = new Rectangle( Toolkit.getDefaultToolkit().getScreenSize() );
        int x = 100, y = 100;
        if( mascot != null )
        {
            for( final GraphicsDevice g : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices() )
            {
                final Rectangle b = g.getDefaultConfiguration().getBounds();
                if( b.intersects( mascot ) ) { screen = b; break; }
            }
            x = mascot.x + mascot.width / 2 - d.width / 2;
            y = mascot.y - d.height - 8;
        }
        x = Math.max( screen.x, Math.min( x, screen.x + screen.width - d.width ) );
        y = Math.max( screen.y, Math.min( y, screen.y + screen.height - d.height ) );
        if( win.getX() != x || win.getY() != y ) win.setLocation( x, y );
    }
}
