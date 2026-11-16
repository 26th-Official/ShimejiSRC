package com.group_finity.mascot.assistant;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;

/**
 * Types one line into whatever window is in front: pastes it through the clipboard (reliable for any
 * text) then presses Enter, and puts the user's clipboard back. This is keystroke injection, so
 * callers must already have verified WHICH window is in front. Never call on the Swing thread.
 */
public final class AgentTyper
{
    private AgentTyper() { }

    public static final int MAX_CHARS = 500;

    /** One line, no control characters, capped. */
    static String sanitize( final String s )
    {
        final String t = s.replaceAll( "[\\p{Cntrl}&&[^\\t]]", " " ).replaceAll( "\\s+", " " ).trim();
        return t.length() > MAX_CHARS ? t.substring( 0, MAX_CHARS ) : t;
    }

    public static boolean typeLine( final String text )
    {
        if( GraphicsEnvironment.isHeadless() ) return false;
        final String line = sanitize( text );
        if( line.isEmpty() ) return false;
        Object saved = null;
        Clipboard cb = null;
        try
        {
            cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            try { if( cb.isDataFlavorAvailable( DataFlavor.stringFlavor ) ) saved = cb.getData( DataFlavor.stringFlavor ); }
            catch( final Exception ignored ) { }
            cb.setContents( new StringSelection( line ), null );
            final Robot r = new Robot();
            r.setAutoDelay( 30 );
            r.keyPress( KeyEvent.VK_CONTROL ); r.keyPress( KeyEvent.VK_V );
            r.keyRelease( KeyEvent.VK_V ); r.keyRelease( KeyEvent.VK_CONTROL );
            Thread.sleep( 250 );
            r.keyPress( KeyEvent.VK_ENTER ); r.keyRelease( KeyEvent.VK_ENTER );
            Thread.sleep( 300 );
            return true;
        }
        catch( final Exception e ) { return false; }
        finally
        {
            try { if( cb != null && saved instanceof String ) cb.setContents( new StringSelection( (String) saved ), null ); }
            catch( final Exception ignored ) { }
        }
    }
}
