package com.group_finity.mascot.win;

import com.group_finity.mascot.assistant.SystemControl;
import com.group_finity.mascot.win.jna.User32;

/** Sends the Windows media virtual keys (the same ones a keyboard's volume/media buttons send). */
public final class WindowsSystemControl implements SystemControl
{
    private static final int KEYEVENTF_KEYUP = 0x0002;

    @Override
    public boolean press( final Key key )
    {
        final byte vk;
        switch( key )
        {
            case VOLUME_UP:   vk = (byte) 0xAF; break;
            case VOLUME_DOWN: vk = (byte) 0xAE; break;
            case MUTE:        vk = (byte) 0xAD; break;
            case PLAY_PAUSE:  vk = (byte) 0xB3; break;
            case NEXT:        vk = (byte) 0xB0; break;
            case PREVIOUS:    vk = (byte) 0xB1; break;
            default: return false;
        }
        try
        {
            User32.INSTANCE.keybd_event( vk, (byte) 0, 0, null );
            User32.INSTANCE.keybd_event( vk, (byte) 0, KEYEVENTF_KEYUP, null );
            return true;
        }
        catch( final Throwable t ) { return false; }
    }
}
