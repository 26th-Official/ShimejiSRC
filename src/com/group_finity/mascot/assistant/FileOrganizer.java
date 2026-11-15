package com.group_finity.mascot.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sorts the loose files of one folder into category subfolders, as a reviewable plan first and an
 * undoable run second. Never deletes, never overwrites, never recurses, never follows links, and
 * never touches in-progress downloads. Every move is written to a journal so the last batch can
 * be put back.
 */
public final class FileOrganizer
{
    public static final int MAX_MOVES = 500;
    private static final long RECENT_MS = 60_000L;

    /** One planned move. */
    public static final class Move
    {
        public final Path from, to;
        public final String category;
        Move( final Path from, final Path to, final String category ) { this.from = from; this.to = to; this.category = category; }
    }

    private static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();
    static
    {
        CATEGORIES.put( "Images", new String[] { "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "tif", "tiff" } );
        CATEGORIES.put( "Documents", new String[] { "pdf", "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "csv", "ppt", "pptx", "md" } );
        CATEGORIES.put( "Archives", new String[] { "zip", "7z", "rar", "tar", "gz" } );
        CATEGORIES.put( "Audio", new String[] { "mp3", "wav", "flac", "ogg", "m4a", "aac" } );
        CATEGORIES.put( "Video", new String[] { "mp4", "mkv", "mov", "avi", "webm" } );
        CATEGORIES.put( "Installers", new String[] { "exe", "msi" } );
    }
    private static final java.util.Set<String> PARTIAL = java.util.Set.of( "crdownload", "part", "tmp", "download", "opdownload" );

    public static String categoryOf( final String fileName )
    {
        final int dot = fileName.lastIndexOf( '.' );
        if( dot <= 0 || dot == fileName.length() - 1 ) return null;
        final String ext = fileName.substring( dot + 1 ).toLowerCase( Locale.ROOT );
        if( PARTIAL.contains( ext ) ) return null;
        for( final Map.Entry<String, String[]> e : CATEGORIES.entrySet() )
            for( final String x : e.getValue() ) if( x.equals( ext ) ) return e.getKey();
        return null;
    }

    /** What would move. Unknown types, hidden files, links, recent files and name clashes stay put. */
    public static List<Move> plan( final Path root, final long nowMs ) throws IOException
    {
        final List<Move> out = new ArrayList<>();
        try( DirectoryStream<Path> ds = Files.newDirectoryStream( root ) )
        {
            for( final Path f : ds )
            {
                if( out.size() >= MAX_MOVES ) break;
                final String name = f.getFileName().toString();
                if( name.startsWith( "." ) || name.indexOf( '\t' ) >= 0 || name.indexOf( '\n' ) >= 0 ) continue;
                if( Files.isSymbolicLink( f ) || !Files.isRegularFile( f ) || Files.isHidden( f ) ) continue;
                if( nowMs - Files.getLastModifiedTime( f ).toMillis() < RECENT_MS ) continue;
                final String cat = categoryOf( name );
                if( cat == null ) continue;
                final Path dest = root.resolve( cat ).resolve( name );
                if( Files.exists( dest ) || !dest.normalize().startsWith( root.normalize() ) ) continue;
                out.add( new Move( f, dest, cat ) );
            }
        }
        return out;
    }

    /** Execute a plan, journalling each success. Returns the number moved. */
    public static int execute( final List<Move> moves, final Path journal ) throws IOException
    {
        final String batch = Long.toString( System.currentTimeMillis() );
        int done = 0;
        for( final Move m : moves )
        {
            try
            {
                Files.createDirectories( m.to.getParent() );
                if( Files.exists( m.to ) ) continue; // never overwrite
                Files.move( m.from, m.to );          // no REPLACE_EXISTING
                Files.write( journal, ( batch + "\t" + m.from + "\t" + m.to + "\n" ).getBytes( StandardCharsets.UTF_8 ),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND );
                done++;
            }
            catch( final IOException e ) { /* locked or vanished: skip it, keep going */ }
        }
        return done;
    }

    /** Put the most recent batch back. Returns the number restored (-1 if there is nothing to undo). */
    public static int undoLast( final Path journal ) throws IOException
    {
        if( !Files.isRegularFile( journal ) ) return -1;
        final List<String> lines = new ArrayList<>( Files.readAllLines( journal, StandardCharsets.UTF_8 ) );
        lines.removeIf( String::isBlank );
        if( lines.isEmpty() ) return -1;
        final String batch = lines.get( lines.size() - 1 ).split( "\t", 3 )[ 0 ];
        int restored = 0;
        final List<String> keep = new ArrayList<>();
        final List<String[]> batchRows = new ArrayList<>();
        for( final String l : lines )
        {
            final String[] p = l.split( "\t", 3 );
            if( p.length == 3 && p[ 0 ].equals( batch ) ) batchRows.add( p ); else keep.add( l );
        }
        for( int i = batchRows.size() - 1; i >= 0; i-- )
        {
            final Path from = Path.of( batchRows.get( i )[ 1 ] ), to = Path.of( batchRows.get( i )[ 2 ] );
            try
            {
                if( Files.exists( to ) && !Files.exists( from ) )
                {
                    Files.move( to, from );
                    restored++;
                    final Path dir = to.getParent();
                    try( DirectoryStream<Path> ds = Files.newDirectoryStream( dir ) )
                    {
                        if( !ds.iterator().hasNext() ) Files.delete( dir ); // empty category folder we created
                    }
                }
            }
            catch( final IOException ignored ) { }
        }
        Files.write( journal, ( String.join( "\n", keep ) + ( keep.isEmpty() ? "" : "\n" ) ).getBytes( StandardCharsets.UTF_8 ),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING );
        return restored;
    }

    /** Short human summary, e.g. "12 files: Images 5, Documents 7". */
    public static String summary( final List<Move> moves )
    {
        final Map<String, Integer> c = new LinkedHashMap<>();
        for( final Move m : moves ) c.merge( m.category, 1, Integer::sum );
        final StringBuilder sb = new StringBuilder().append( moves.size() ).append( moves.size() == 1 ? " file: " : " files: " );
        boolean first = true;
        for( final Map.Entry<String, Integer> e : c.entrySet() )
        {
            if( !first ) sb.append( ", " );
            sb.append( e.getKey() ).append( ' ' ).append( e.getValue() );
            first = false;
        }
        return sb.toString();
    }
}
