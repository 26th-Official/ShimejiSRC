package com.group_finity.mascot.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Things the mascot will do later. Persisted to {@code agent-schedule.tsv} so they survive a restart.
 * A task is removed (and the file rewritten) BEFORE it runs, so a crash can never make it run twice.
 * Tasks more than {@link #LATE_MS} overdue (the computer was off) are reported as missed, not run.
 */
public final class AgentScheduler
{
    private static final Logger log = Logger.getLogger( AgentScheduler.class.getName() );
    public static final long LATE_MS = 15 * 60_000L;
    public static final int MAX_TASKS = 200;

    public static final class Task
    {
        public final long id, atMs, createdMs;
        public final String kind, a, b;
        Task( final long id, final long atMs, final String kind, final String a, final String b, final long createdMs )
        { this.id = id; this.atMs = atMs; this.kind = kind; this.a = a; this.b = b; this.createdMs = createdMs; }
    }

    public interface Handler { void fire( Task t, boolean late ); }

    private static AgentScheduler shared;

    public static synchronized AgentScheduler shared()
    {
        if( shared == null ) shared = new AgentScheduler( Paths.get( "agent-schedule.tsv" ) );
        return shared;
    }

    private final Path file;
    private final List<Task> tasks = new ArrayList<>();
    private volatile Handler handler;
    private Thread thread;
    private long nextId = 1;

    public AgentScheduler( final Path file )
    {
        this.file = file;
        load();
    }

    private static String clean( final String s ) { return s == null ? "" : s.replace( '\t', ' ' ).replace( '\n', ' ' ).replace( '\r', ' ' ); }

    private synchronized void load()
    {
        try
        {
            if( !Files.exists( file ) ) return;
            for( final String l : Files.readAllLines( file, StandardCharsets.UTF_8 ) )
            {
                final String[] p = l.split( "\t", -1 );
                if( p.length < 6 ) continue;
                try
                {
                    final long id = Long.parseLong( p[ 0 ] );
                    tasks.add( new Task( id, Long.parseLong( p[ 1 ] ), p[ 2 ], p[ 3 ], p[ 4 ], Long.parseLong( p[ 5 ] ) ) );
                    nextId = Math.max( nextId, id + 1 );
                }
                catch( final NumberFormatException ignored ) { }
            }
        }
        catch( final IOException e ) { log.warning( "[Agent] couldn't read schedule: " + e ); }
    }

    private synchronized void save()
    {
        try
        {
            final StringBuilder sb = new StringBuilder();
            for( final Task t : tasks )
                sb.append( t.id ).append( '\t' ).append( t.atMs ).append( '\t' ).append( t.kind ).append( '\t' )
                  .append( t.a ).append( '\t' ).append( t.b ).append( '\t' ).append( t.createdMs ).append( '\n' );
            if( tasks.isEmpty() ) Files.deleteIfExists( file );
            else Files.write( file, sb.toString().getBytes( StandardCharsets.UTF_8 ) );
        }
        catch( final IOException e ) { log.warning( "[Agent] couldn't save schedule: " + e ); }
    }

    /** Null when the schedule is full. */
    public synchronized Task add( final long atMs, final String kind, final String a, final String b )
    {
        if( tasks.size() >= MAX_TASKS ) return null;
        final Task t = new Task( nextId++, atMs, kind, clean( a ), clean( b ), System.currentTimeMillis() );
        tasks.add( t );
        save();
        return t;
    }

    public synchronized List<Task> list()
    {
        final List<Task> out = new ArrayList<>( tasks );
        out.sort( ( x, y ) -> Long.compare( x.atMs, y.atMs ) );
        return out;
    }

    /** Cancel by id, "all", "last" (the most recently added) or words found in the task text. Returns how many. */
    public synchronized int cancel( final String what )
    {
        final String w = what == null ? "" : what.toLowerCase( Locale.ROOT ).trim();
        final List<Task> gone = new ArrayList<>();
        if( w.equals( "all" ) ) gone.addAll( tasks );
        else if( w.equals( "last" ) )
        {
            Task best = null;
            for( final Task t : tasks ) if( best == null || t.createdMs >= best.createdMs ) best = t;
            if( best != null ) gone.add( best );
        }
        else if( !w.isEmpty() )
            for( final Task t : tasks )
                if( Long.toString( t.id ).equals( w ) || ( t.a + " " + t.b + " " + t.kind ).toLowerCase( Locale.ROOT ).contains( w ) ) gone.add( t );
        if( !gone.isEmpty() ) { tasks.removeAll( gone ); save(); }
        return gone.size();
    }

    /** Run everything due at {@code now}; returns how many were handled. Used by the timer thread and tests. */
    public int fireDue( final long now, final Handler h )
    {
        final List<Task> due = new ArrayList<>();
        synchronized( this )
        {
            for( final Task t : tasks ) if( t.atMs <= now ) due.add( t );
            if( due.isEmpty() ) return 0;
            tasks.removeAll( due );
            save();                                   // forget them first: never run twice
        }
        due.sort( ( x, y ) -> Long.compare( x.atMs, y.atMs ) );
        for( final Task t : due )
        {
            try { h.fire( t, now - t.atMs > LATE_MS ); }
            catch( final Throwable e ) { log.warning( "[Agent] scheduled " + t.kind + " failed: " + e ); }
        }
        return due.size();
    }

    /** Start the once-a-second checker (idempotent); the newest handler always wins. */
    public synchronized void start( final Handler h )
    {
        handler = h;
        if( thread != null ) return;
        thread = new Thread( () ->
        {
            while( true )
            {
                try
                {
                    final Handler cur = handler;
                    if( cur != null ) fireDue( System.currentTimeMillis(), cur );
                    Thread.sleep( 1000 );
                }
                catch( final InterruptedException e ) { return; }
                catch( final Throwable e ) { log.warning( "[Agent] scheduler loop: " + e ); }
            }
        }, "agent-scheduler" );
        thread.setDaemon( true );
        thread.start();
    }
}
