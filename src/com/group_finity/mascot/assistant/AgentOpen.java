package com.group_finity.mascot.assistant;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Open this page / file / program": turns the user's words into ONE safe target.
 * Pages are http(s) only. Files are found by name in the user's folders and may not be programs or
 * scripts. Programs are found only through Start Menu shortcuts (what the user would click), never by
 * an arbitrary path. Everything is re-checked right before opening, not just when it was scheduled.
 */
public final class AgentOpen
{
    private AgentOpen() { }

    public static final class Target
    {
        public final String kind;   // url | file | app
        public final String path;   // URL, absolute file, or absolute .lnk
        public final String label;  // what to say to the user
        Target( final String kind, final String path, final String label ) { this.kind = kind; this.path = path; this.label = label; }
    }

    public static final class Resolved
    {
        public final Target target;
        public final String error;
        Resolved( final Target t, final String e ) { target = t; error = e; }
    }

    private static final Set<String> BANNED = Set.of( "exe", "bat", "cmd", "com", "ps1", "psm1", "vbs", "vbe", "js", "jse", "jar",
        "msi", "msp", "scr", "pif", "reg", "hta", "wsf", "wsh", "lnk", "cpl", "dll", "sys", "appx", "msix", "url", "gadget", "inf", "sh" );

    private static final Pattern URL_FULL = Pattern.compile( "^https?://[^\\s/]+\\S*$", Pattern.CASE_INSENSITIVE );
    private static final Pattern DOMAIN = Pattern.compile(
        "^(?:www\\.)?[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|io|tv|gg|app|dev|co|edu|gov|me|ai|us|uk|ca|de|jp|fm|ly|xyz|info|so|to|sh|wiki|news|live)(?:[/?#]\\S*)?$",
        Pattern.CASE_INSENSITIVE );

    static String ext( final String name )
    {
        final int d = name.lastIndexOf( '.' );
        return d < 0 ? "" : name.substring( d + 1 ).toLowerCase( Locale.ROOT );
    }

    /** The user's own Start Menu folders (where the shortcuts for installed programs live). */
    public static List<Path> startMenuDirs()
    {
        final List<Path> out = new ArrayList<>();
        final String pd = System.getenv( "ProgramData" ), ad = System.getenv( "APPDATA" );
        if( pd != null ) out.add( Paths.get( pd, "Microsoft", "Windows", "Start Menu", "Programs" ) );
        if( ad != null ) out.add( Paths.get( ad, "Microsoft", "Windows", "Start Menu", "Programs" ) );
        return out;
    }

    public static Resolved resolve( final String wordsIn, final List<Path> fileRoots, final List<Path> startMenu )
    {
        String words = wordsIn == null ? "" : wordsIn.trim().replaceAll( "^[\"']|[\"']$", "" );
        words = words.replaceAll( "(?i)^(?:the|my)\\s+", "" ).replaceAll( "(?i)\\s+(?:website|web site|page|site|app|application|program)$", "" ).trim();
        if( words.isEmpty() || words.matches( "(?i)this|that|it|this page|this file|this tab" ) )
            return err( "Tell me what to open: a web address, a file name, or a program." );

        if( URL_FULL.matcher( words ).matches() || DOMAIN.matcher( words ).matches() )
        {
            final String u = URL_FULL.matcher( words ).matches() ? words : "https://" + words;
            try
            {
                final URI uri = URI.create( u );
                if( uri.getHost() == null ) return err( "That doesn't look like a web address." );
                return new Resolved( new Target( "url", uri.toString(), uri.getHost().replaceFirst( "^www\\.", "" ) ), null );
            }
            catch( final IllegalArgumentException e ) { return err( "That doesn't look like a web address." ); }
        }
        if( words.contains( "\\" ) || words.contains( "/" ) || words.contains( ".." ) || words.contains( ":" ) )
            return err( "I open web pages, files by name, and programs by name, not by path." );

        final boolean hasExt = !ext( words ).isEmpty() && words.matches( ".*\\.[A-Za-z0-9]{1,5}$" );
        if( hasExt )
        {
            if( BANNED.contains( ext( words ) ) ) return err( "I won't open programs or scripts by file name. Say the program's name instead." );
            return fileByName( words, fileRoots );
        }
        final Resolved app = appByName( words, startMenu );
        if( app.target != null || app.error != null && !app.error.startsWith( "NONE" ) ) return app;
        final Resolved f = fileByName( words, fileRoots );
        if( f.target != null ) return f;
        return err( "I can't find a program or file called \"" + words + "\"." );
    }

    private static Resolved err( final String e ) { return new Resolved( null, e ); }

    private static Resolved fileByName( final String words, final List<Path> roots )
    {
        final String q = words.toLowerCase( Locale.ROOT );
        int best = 0;
        final List<Path> hits = new ArrayList<>();
        for( final Path root : roots )
        {
            final List<Path> dirs = new ArrayList<>();
            dirs.add( root );
            try( DirectoryStream<Path> ds = Files.newDirectoryStream( root, Files::isDirectory ) )
            {
                for( final Path d : ds ) if( !d.getFileName().toString().startsWith( "." ) && !Files.isSymbolicLink( d ) ) dirs.add( d );
            }
            catch( final IOException ignored ) { }
            for( final Path d : dirs )
                try( DirectoryStream<Path> ds = Files.newDirectoryStream( d ) )
                {
                    for( final Path f : ds )
                    {
                        final String name = f.getFileName().toString();
                        if( name.startsWith( "." ) || Files.isSymbolicLink( f ) || !Files.isRegularFile( f ) || BANNED.contains( ext( name ) ) ) continue;
                        final String n = name.toLowerCase( Locale.ROOT );
                        final int dot = n.lastIndexOf( '.' );
                        final String base = dot > 0 ? n.substring( 0, dot ) : n;
                        final int score = n.equals( q ) || base.equals( q ) ? 3 : n.startsWith( q ) ? 2 : n.contains( q ) ? 1 : 0;
                        if( score == 0 ) continue;
                        if( score > best ) { best = score; hits.clear(); }
                        if( score == best ) hits.add( f );
                    }
                }
                catch( final IOException ignored ) { }
        }
        if( hits.isEmpty() ) return err( "I can't find a file called \"" + words + "\"." );
        if( hits.size() > 1 )
        {
            final StringBuilder sb = new StringBuilder( "Which file? " );
            for( int i = 0; i < Math.min( 4, hits.size() ); i++ ) sb.append( i > 0 ? " / " : "" ).append( hits.get( i ).getFileName() );
            return err( sb.toString() );
        }
        return new Resolved( new Target( "file", hits.get( 0 ).toString(), hits.get( 0 ).getFileName().toString() ), null );
    }

    private static Resolved appByName( final String words, final List<Path> menus )
    {
        final String q = words.toLowerCase( Locale.ROOT );
        int best = 0;
        final List<Path> hits = new ArrayList<>();
        for( final Path m : menus ) collect( m, 0, q, hits, new int[] { 0 }, new int[] { best }, null );
        // second pass to rank (kept simple: exact > starts > contains)
        best = 0;
        final List<Path> ranked = new ArrayList<>();
        for( final Path p : hits )
        {
            final String n = p.getFileName().toString().toLowerCase( Locale.ROOT ).replaceAll( "\\.lnk$", "" );
            final int s = n.equals( q ) ? 3 : n.startsWith( q ) ? 2 : 1;
            if( s > best ) { best = s; ranked.clear(); }
            if( s == best ) ranked.add( p );
        }
        // the same program often has a per-user and an all-users shortcut with the same name
        final List<Path> uniq = new ArrayList<>();
        final List<String> seen = new ArrayList<>();
        for( final Path p : ranked ) { final String k = p.getFileName().toString().toLowerCase( Locale.ROOT ); if( !seen.contains( k ) ) { seen.add( k ); uniq.add( p ); } }
        if( uniq.isEmpty() ) return err( "NONE" );
        if( uniq.size() > 1 )
        {
            final StringBuilder sb = new StringBuilder( "Which program? " );
            for( int i = 0; i < Math.min( 4, uniq.size() ); i++ ) sb.append( i > 0 ? " / " : "" ).append( uniq.get( i ).getFileName().toString().replaceAll( "(?i)\\.lnk$", "" ) );
            return err( sb.toString() );
        }
        final Path p = uniq.get( 0 );
        return new Resolved( new Target( "app", p.toString(), p.getFileName().toString().replaceAll( "(?i)\\.lnk$", "" ) ), null );
    }

    private static void collect( final Path dir, final int depth, final String q, final List<Path> out, final int[] n, final int[] unused, final Object u2 )
    {
        if( depth > 3 || n[ 0 ] > 4000 ) return;
        try( DirectoryStream<Path> ds = Files.newDirectoryStream( dir ) )
        {
            for( final Path p : ds )
            {
                n[ 0 ]++;
                final String name = p.getFileName().toString();
                if( Files.isDirectory( p ) && !Files.isSymbolicLink( p ) ) collect( p, depth + 1, q, out, n, unused, u2 );
                else if( name.toLowerCase( Locale.ROOT ).endsWith( ".lnk" ) && name.toLowerCase( Locale.ROOT ).contains( q )
                         && !name.toLowerCase( Locale.ROOT ).contains( "uninstall" ) ) out.add( p );
            }
        }
        catch( final IOException ignored ) { }
    }

    /** Check a stored target again right before opening it (the schedule file is plain text anyone could edit). */
    public static boolean stillSafe( final String kind, final String path, final List<Path> fileRoots, final List<Path> startMenu )
    {
        try
        {
            if( "url".equals( kind ) )
            {
                final URI u = URI.create( path );
                return ( "http".equalsIgnoreCase( u.getScheme() ) || "https".equalsIgnoreCase( u.getScheme() ) ) && u.getHost() != null;
            }
            final Path p = Paths.get( path ).toAbsolutePath().normalize();
            if( !Files.isRegularFile( p ) ) return false;
            if( "app".equals( kind ) )
            {
                if( !p.getFileName().toString().toLowerCase( Locale.ROOT ).endsWith( ".lnk" ) ) return false;
                for( final Path m : startMenu ) if( p.startsWith( m.toAbsolutePath().normalize() ) ) return true;
                return false;
            }
            if( "file".equals( kind ) )
            {
                if( BANNED.contains( ext( p.getFileName().toString() ) ) ) return false;
                for( final Path r : fileRoots ) if( p.startsWith( r.toAbsolutePath().normalize() ) ) return true;
            }
            return false;
        }
        catch( final Exception e ) { return false; }
    }

    /** Open it with the system's default handler. Off the Swing thread. */
    public static boolean open( final String kind, final String path )
    {
        try
        {
            if( !java.awt.Desktop.isDesktopSupported() ) return false;
            if( "url".equals( kind ) ) java.awt.Desktop.getDesktop().browse( URI.create( path ) );
            else java.awt.Desktop.getDesktop().open( new File( path ) );
            return true;
        }
        catch( final Exception e ) { return false; }
    }
}
