package com.group_finity.mascot.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The visible version of "move this file": the mascot walks to the source folder, takes the file out
 * (it is parked in a hidden holding folder next to where it was, so it really leaves its folder), carries
 * it across and puts it into the destination folder. This class is the director and only touches the
 * file system; everything you can see (windows, walking, the flying file chip) is behind {@link Stage}.
 * The file never ends up anywhere but its original place or the destination: on any failure it is
 * put back, and a crash mid-carry is repaired by {@link #recover} at the next start.
 */
public final class AgentErrand
{
    private AgentErrand() { }

    /** What the user sees. All calls block until that part is finished; they never throw. */
    public interface Stage
    {
        void openFolders( Path from, Path to );
        /** Walk to the source folder's window; false if the mascot could not get there. */
        boolean walkToSource();
        /** The file leaves its window and rides on the mascot. */
        void grab( Path held );
        boolean walkToDest();
        /** The file drops into the destination window. */
        void drop();
        /** Always called last: give the mascot back to its normal behavior and remove any visuals. */
        void finish();
    }

    public enum Outcome { DONE, NOT_STAGED, CLASH }

    public static final String HOLD_DIR = ".shimeji-hold";

    public static Outcome run( final Stage st, final FileOrganizer.Move mv, final Path journal, final Path holding ) throws IOException
    {
        final Path orig = mv.from, dest = mv.to;
        final Path holdDir = orig.getParent().resolve( HOLD_DIR );
        final Path held = holdDir.resolve( orig.getFileName() );
        boolean staged = false;
        try
        {
            st.openFolders( orig.getParent(), dest.getParent() );
            if( !st.walkToSource() ) return Outcome.NOT_STAGED;
            if( Files.exists( dest ) ) return Outcome.CLASH;
            Files.createDirectories( holdDir );
            try { Files.setAttribute( holdDir, "dos:hidden", true ); } catch( final Exception ignored ) { }
            if( Files.exists( held ) ) return Outcome.NOT_STAGED;
            record( holding, held, orig );
            Files.move( orig, held );
            staged = true;
            st.grab( held );
            st.walkToDest();            // if the walk fails the file still arrives, just without the trip
            st.drop();
            if( Files.exists( dest ) ) return Outcome.CLASH;  // appeared meanwhile; finally{} puts it back
            Files.createDirectories( dest.getParent() );
            Files.move( held, dest );
            staged = false;
            unrecord( holding, held );
            FileOrganizer.journalMove( orig, dest, journal );
            try { Files.deleteIfExists( holdDir ); } catch( final IOException ignored ) { }
            return Outcome.DONE;
        }
        finally
        {
            try
            {
                if( staged ) { putBack( held, orig ); unrecord( holding, held ); }
            }
            finally { st.finish(); }
        }
    }

    private static void putBack( final Path held, final Path orig )
    {
        try
        {
            if( Files.exists( held ) && !Files.exists( orig ) ) Files.move( held, orig );
            try { Files.deleteIfExists( held.getParent() ); } catch( final IOException ignored ) { }
        }
        catch( final IOException e ) { /* left in the holding folder; recover() retries next start */ }
    }

    private static synchronized void record( final Path holding, final Path held, final Path orig ) throws IOException
    {
        Files.write( holding, ( held + "\t" + orig + "\n" ).getBytes( StandardCharsets.UTF_8 ),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND );
    }

    private static synchronized void unrecord( final Path holding, final Path held )
    {
        try
        {
            if( !Files.exists( holding ) ) return;
            final List<String> keep = new ArrayList<>();
            for( final String l : Files.readAllLines( holding, StandardCharsets.UTF_8 ) )
                if( !l.isEmpty() && !l.startsWith( held + "\t" ) ) keep.add( l );
            if( keep.isEmpty() ) Files.delete( holding );
            else Files.write( holding, ( String.join( "\n", keep ) + "\n" ).getBytes( StandardCharsets.UTF_8 ) );
        }
        catch( final IOException ignored ) { }
    }

    /** After a crash: anything still parked in a holding folder goes back where it came from. Returns how many. */
    public static synchronized int recover( final Path holding )
    {
        int n = 0;
        try
        {
            if( !Files.exists( holding ) ) return 0;
            for( final String l : Files.readAllLines( holding, StandardCharsets.UTF_8 ) )
            {
                final String[] p = l.split( "\t" );
                if( p.length != 2 ) continue;
                final Path held = java.nio.file.Paths.get( p[ 0 ] ), orig = java.nio.file.Paths.get( p[ 1 ] );
                if( held.getFileName() != null && HOLD_DIR.equals( held.getParent() == null || held.getParent().getFileName() == null ? "" : held.getParent().getFileName().toString() )
                    && Files.isRegularFile( held ) && !Files.exists( orig ) )
                {
                    Files.move( held, orig );
                    try { Files.deleteIfExists( held.getParent() ); } catch( final IOException ignored ) { }
                    n++;
                }
            }
            Files.deleteIfExists( holding );
        }
        catch( final IOException ignored ) { }
        return n;
    }
}
