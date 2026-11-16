package com.group_finity.mascot.assistant;

/**
 * The few system-level things the agent may do: press the volume and media keys. Nothing that powers
 * off, closes, deletes or installs. The Windows backend lives in src_win and is found by name.
 */
public interface SystemControl
{
    enum Key { VOLUME_UP, VOLUME_DOWN, MUTE, PLAY_PAUSE, NEXT, PREVIOUS }

    /** Press and release the key once. False if it could not be sent. */
    boolean press( Key key );

    static SystemControl get()
    {
        try
        {
            return (SystemControl) Class.forName( "com.group_finity.mascot.win.WindowsSystemControl" )
                .getDeclaredConstructor().newInstance();
        }
        catch( final Throwable t ) { return null; }
    }
}
