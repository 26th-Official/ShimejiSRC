package com.group_finity.mascot.assistant;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * "Move report.pdf to Documents": finds one loose file and one destination folder by the words the
 * user used. Pure planning, no confirmation and no moving here. Only the top level of the allowed
 * roots is searched, files only, never hidden, never overwriting; ambiguity is reported, never guessed.
 */
public final class FileMover
{
    private FileMover() { }

    /** Result of planning: exactly one of {@code move} / {@code error} is set. */
    public static final class Plan
    {
        public final FileOrganizer.Move move;
        public final String error;
        Plan( final FileOrganizer.Move m, final String e ) { move = m; error = e; }
    }

    private static final String[] KNOWN = { "Desktop", "Documents", "Downloads", "Pictures", "Videos", "Music" };

    /** The agent's folders plus the usual user folders that exist (all under the home folder). */
    public static List<Path> roots( final Properties props, final Path home )
    {
        final Set<Path> out = new LinkedHashSet<>( AgentPolicy.allowedFolders( props, home ) );
        for( final String k : KNOWN )
        {
            final Path p = home.resolve( k ).normalize();
            if( Files.isDirectory( p ) ) out.add( p );
        }
        return new ArrayList<>( out );
    }

    public static Plan plan( final String fileWords, final String destWords, final List<Path> roots )
    {
        final String fq = fileWords == null ? "" : fileWords.toLowerCase( Locale.ROOT ).replaceAll( "[\"']", "" ).trim();
        if( fq.isEmpty() || fq.equals( "this" ) || fq.equals( "it" ) || fq.equals( "that" ) || fq.equals( "this file" ) )
            return err( "Which file? Tell me its name." );
        if( fq.contains( "/" ) || fq.contains( "\\" ) || fq.contains( ".." ) ) return err( "Just tell me the file's name." );

        // ---- the file ----
        int best = 0;
        final List<Path> hits = new ArrayList<>();
        for( final Path root : roots )
        {
            try( DirectoryStream<Path> ds = Files.newDirectoryStream( root ) )
            {
                for( final Path f : ds )
                {
                    final String name = f.getFileName().toString();
                    if( name.startsWith( "." ) || name.indexOf( '\t' ) >= 0 || name.indexOf( '\n' ) >= 0 ) continue;
                    if( Files.isSymbolicLink( f ) || !Files.isRegularFile( f ) || Files.isHidden( f ) ) continue;
                    final String n = name.toLowerCase( Locale.ROOT );
                    final int dot = n.lastIndexOf( '.' );
                    final String base = dot > 0 ? n.substring( 0, dot ) : n;
                    final int score = n.equals( fq ) || base.equals( fq ) ? 3 : n.startsWith( fq ) ? 2 : n.contains( fq ) ? 1 : 0;
                    if( score == 0 ) continue;
                    if( score > best ) { best = score; hits.clear(); }
                    if( score == best ) hits.add( f );
                }
            }
            catch( final IOException ignored ) { }
        }
        if( hits.isEmpty() ) return err( "I can't find a file called \"" + fileWords.trim() + "\" in " + rootNames( roots ) + "." );
        if( hits.size() > 1 )
        {
            final StringBuilder sb = new StringBuilder( "Which one? " );
            for( int i = 0; i < Math.min( 4, hits.size() ); i++ ) sb.append( i > 0 ? " / " : "" ).append( hits.get( i ).getFileName() );
            return err( sb.toString() );
        }
        final Path from = hits.get( 0 );

        // ---- the folder ----
        final String dq = destWords == null ? "" : destWords.toLowerCase( Locale.ROOT )
            .replaceAll( "\\b(?:the|my|folder|directory)\\b", " " ).replaceAll( "\\s+", " " ).trim();
        if( dq.isEmpty() ) return err( "Where should it go?" );
        final List<Path> dirs = new ArrayList<>();
        for( final Path r : roots ) if( r.getFileName() != null && r.getFileName().toString().toLowerCase( Locale.ROOT ).equals( dq ) ) dirs.add( r );
        if( dirs.isEmpty() )
            for( final Path r : roots )
                try( DirectoryStream<Path> ds = Files.newDirectoryStream( r, Files::isDirectory ) )
                {
                    for( final Path d : ds )
                        if( !d.getFileName().toString().startsWith( "." ) && !Files.isSymbolicLink( d )
                            && d.getFileName().toString().toLowerCase( Locale.ROOT ).equals( dq ) ) dirs.add( d );
                }
                catch( final IOException ignored ) { }
        if( dirs.isEmpty() ) return err( "I can't find a folder called \"" + destWords.trim() + "\". I look in " + rootNames( roots ) + " and the folders inside them." );
        if( dirs.size() > 1 ) return err( "More than one folder is called \"" + destWords.trim() + "\". Which one?" );
        final Path dir = dirs.get( 0 );
        if( dir.equals( from.getParent() ) ) return err( "It's already in " + dir.getFileName() + "." );
        final Path to = dir.resolve( from.getFileName() ).normalize();
        if( !to.startsWith( dir ) ) return err( "That name isn't allowed." );
        if( Files.exists( to ) ) return err( dir.getFileName() + " already has a file with that name. I won't overwrite it." );
        return new Plan( new FileOrganizer.Move( from, to, dir.getFileName().toString() ), null );
    }

    private static Plan err( final String e ) { return new Plan( null, e ); }

    private static String rootNames( final List<Path> roots )
    {
        final List<String> n = new ArrayList<>();
        for( final Path p : roots ) if( p.getFileName() != null ) n.add( p.getFileName().toString() );
        return String.join( ", ", n );
    }
}
