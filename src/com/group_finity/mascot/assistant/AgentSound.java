package com.group_finity.mascot.assistant;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/** A short generated chime for timers and alarms (no sound file needed). Never blocks, never throws. */
public final class AgentSound
{
    private AgentSound() { }

    private static volatile long lastMs = 0;
    private static final float RATE = 22050f;

    /** 16-bit mono PCM of {@code count} descending two-note chimes. Pure, for tests. */
    static byte[] chimeSamples( final int count )
    {
        final double[] notes = { 988, 784 };
        final int noteLen = (int) ( RATE * 0.28 ), gap = (int) ( RATE * 0.12 );
        final int per = notes.length * noteLen + gap;
        final byte[] out = new byte[ count * per * 2 ];
        int pos = 0;
        for( int c = 0; c < count; c++ )
        {
            for( final double f : notes )
            {
                for( int i = 0; i < noteLen; i++ )
                {
                    final double env = Math.min( 1, i / 200.0 ) * Math.exp( -4.0 * i / noteLen );
                    final short v = (short) ( Math.sin( 2 * Math.PI * f * i / RATE ) * env * 9000 );
                    out[ pos++ ] = (byte) ( v & 0xff );
                    out[ pos++ ] = (byte) ( v >> 8 );
                }
            }
            pos += gap * 2;
        }
        return out;
    }

    /** Play the chime (3 times) unless one just played (a timer bubble and the scheduler can both ask). */
    public static void chime()
    {
        final long now = System.currentTimeMillis();
        synchronized( AgentSound.class )
        {
            if( now - lastMs < 4000 ) return;
            lastMs = now;
        }
        try
        {
            final com.group_finity.mascot.Main m = com.group_finity.mascot.Main.getInstance();
            if( m != null && "false".equalsIgnoreCase( m.getProperties().getProperty( "TimerSound", "true" ).trim() ) ) return;
        }
        catch( final Throwable ignored ) { }
        final Thread th = new Thread( () ->
        {
            try
            {
                final byte[] pcm = chimeSamples( 3 );
                final Clip clip = AudioSystem.getClip();
                clip.open( new AudioFormat( RATE, 16, 1, true, false ), pcm, 0, pcm.length );
                clip.addLineListener( e -> { if( e.getType() == javax.sound.sampled.LineEvent.Type.STOP ) clip.close(); } );
                clip.start();
            }
            catch( final Throwable ignored ) { /* no audio device: the bubble text is still shown */ }
        }, "agent-chime" );
        th.setDaemon( true );
        th.start();
    }
}
