package com.group_finity.mascot.assistant;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What the agent is allowed to do. Everything is opt-in: {@code AgentEnabled=true} turns the agent
 * on, and file actions only ever touch the folders listed in {@code AgentFolders} (default: the
 * user's Downloads folder). {@link #halt()} is the kill switch: it stops all agent actions until
 * the app restarts or {@link #resume()} is called.
 */
public final class AgentPolicy
{
    private static volatile boolean halted = false;

    private AgentPolicy() { }

    public static void halt() { halted = true; }
    public static void resume() { halted = false; }
    public static boolean isHalted() { return halted; }

    public static boolean enabled( final Properties p )
    {
        return !halted && p != null && "true".equalsIgnoreCase( p.getProperty( "AgentEnabled", "false" ).trim() );
    }

    /** Typing into other apps (scheduled "ask X to ..."): a second opt-in on top of AgentEnabled. */
    public static boolean typeEnabled( final Properties p )
    {
        return enabled( p ) && "true".equalsIgnoreCase( p.getProperty( "AgentTypeEnabled", "false" ).trim() );
    }

    /** Folders the agent may organize: semicolon-separated; bare names resolve under the user's home. */
    public static List<Path> allowedFolders( final Properties p, final Path home )
    {
        final String raw = p == null ? "Downloads" : p.getProperty( "AgentFolders", "Downloads" );
        final List<Path> out = new ArrayList<>();
        for( final String part : raw.split( ";" ) )
        {
            final String s = part.trim();
            if( s.isEmpty() ) continue;
            try
            {
                Path f = Paths.get( s );
                if( !f.isAbsolute() ) f = home.resolve( s );
                f = f.normalize();
                // Never a drive root or the home folder itself: too broad to organize blindly.
                if( f.getParent() == null || f.equals( home.normalize() ) ) continue;
                out.add( f );
            }
            catch( final Exception ignored ) { }
        }
        return out;
    }

    /** The allowed folder the user's words refer to (matched on the folder's own name), or null. */
    public static Path resolveFolder( final String words, final List<Path> allowed )
    {
        if( words == null ) return null;
        final String w = words.trim().toLowerCase( java.util.Locale.ROOT );
        for( final Path f : allowed )
        {
            final Path name = f.getFileName();
            if( name != null && name.toString().toLowerCase( java.util.Locale.ROOT ).equals( w ) ) return f;
        }
        return null;
    }
}
