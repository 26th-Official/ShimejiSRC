package com.group_finity.mascot.assistant;

import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class AgentToolsTest
{
    static int fails = 0;
    static void eq( final String n, final Object want, final Object got )
    {
        if( !java.util.Objects.equals( want, got ) ) { fails++; System.out.println( "FAIL " + n + ": want " + want + " got " + got ); }
    }

    static final class FakeWin implements WindowControl
    {
        final List<Info> wins = new ArrayList<>();
        final List<String> applied = new ArrayList<>();
        public List<Info> list() { return wins; }
        public boolean apply( final Info w, final Op op ) { applied.add( op + ":" + w.title ); return true; }
    }

    static WindowControl.Info w( final String t ) { return new WindowControl.Info( 1, t, new Rectangle( 0, 0, 100, 100 ), false ); }

    public static void main( final String[] a ) throws Exception
    {
        // parsing
        final List<String[]> tags = AgentTools.parse( "Sure. [DO:window|left|Google Chrome] [do:UNDO] [ACTION:Dance] [DO:organize|Downloads]" );
        eq( "tag count", 3, tags.size() );
        eq( "tool lower", "window", tags.get( 0 )[ 0 ] );
        eq( "arg", "Google Chrome", tags.get( 0 )[ 2 ] );
        eq( "undo no args", 1, tags.get( 1 ).length );
        eq( "cap per reply", 3, AgentTools.parse( "[DO:undo][DO:undo][DO:undo][DO:undo][DO:undo]" ).size() );
        eq( "null", 0, AgentTools.parse( null ).size() );

        // matching: never guess between equals
        final List<WindowControl.Info> all = List.of( w( "Google Chrome" ), w( "Discord" ), w( "Notes - Chrome Dev" ), w( "Untitled - Notepad" ) );
        eq( "two contains is ambiguous", null, AgentTools.matchWindow( all, "chrome" ).match );
        eq( "starts-with", "Google Chrome", AgentTools.matchWindow( all, "google" ).match.title );
        final List<WindowControl.Info> sw = List.of( w( "Chrome" ), w( "Docs - Chrome" ) );
        eq( "starts-with beats contains", "Chrome", AgentTools.matchWindow( sw, "chrome" ).match.title );
        eq( "exact", "Discord", AgentTools.matchWindow( all, "discord" ).match.title );
        final List<WindowControl.Info> two = List.of( w( "Docs - Chrome" ), w( "Mail - Chrome" ) );
        eq( "tie is ambiguous", null, AgentTools.matchWindow( two, "chrome" ).match );
        eq( "tie lists both", 2, AgentTools.matchWindow( two, "chrome" ).ambiguous.size() );
        eq( "none", 0, AgentTools.matchWindow( all, "zzz" ).ambiguous.size() );
        eq( "empty query", null, AgentTools.matchWindow( all, "" ).match );

        // ops + layout
        eq( "op focus", WindowControl.Op.FOCUS, AgentTools.opOf( "Focus" ) );
        eq( "op bad", null, AgentTools.opOf( "close" ) );
        final Rectangle wa = new Rectangle( 0, 0, 1000, 800 );
        eq( "left", new Rectangle( 0, 0, 500, 800 ), AgentLayout.target( wa, WindowControl.Op.LEFT ) );
        eq( "right", new Rectangle( 500, 0, 500, 800 ), AgentLayout.target( wa, WindowControl.Op.RIGHT ) );
        eq( "center", new Rectangle( 150, 120, 700, 560 ), AgentLayout.target( wa, WindowControl.Op.CENTER ) );

        // policy
        final Path root = Files.createTempDirectory( "agent" );
        final Path home = root.resolve( "home" );
        final Path dl = home.resolve( "Downloads" );
        Files.createDirectories( dl );
        final Properties p = new Properties();
        eq( "off by default", false, AgentPolicy.enabled( p ) );
        p.setProperty( "AgentEnabled", "true" );
        eq( "on", true, AgentPolicy.enabled( p ) );
        eq( "default folder", List.of( dl ), AgentPolicy.allowedFolders( p, home ) );
        p.setProperty( "AgentFolders", home.toString() + ";" + root.getRoot() );
        eq( "home and drive root refused", 0, AgentPolicy.allowedFolders( p, home ).size() );
        p.setProperty( "AgentFolders", "Downloads" );
        eq( "resolve by name", dl, AgentPolicy.resolveFolder( "downloads", AgentPolicy.allowedFolders( p, home ) ) );
        eq( "other folder refused", null, AgentPolicy.resolveFolder( "Documents", AgentPolicy.allowedFolders( p, home ) ) );

        // organizer
        final long old = System.currentTimeMillis() - 3_600_000L;
        for( final String n : new String[] { "a.png", "b.PDF", "c.zip", "weird.xyz", "dl.crdownload", ".hidden.png", "fresh.png" } )
        {
            final Path f = dl.resolve( n );
            Files.write( f, new byte[] { 1 } );
            Files.setLastModifiedTime( f, java.nio.file.attribute.FileTime.fromMillis( n.equals( "fresh.png" ) ? System.currentTimeMillis() : old ) );
        }
        Files.createDirectories( dl.resolve( "Images" ) );
        Files.write( dl.resolve( "Images" ).resolve( "taken.png" ), new byte[] { 1 } );
        Files.write( dl.resolve( "taken.png" ), new byte[] { 2 } );
        Files.setLastModifiedTime( dl.resolve( "taken.png" ), java.nio.file.attribute.FileTime.fromMillis( old ) );
        final List<FileOrganizer.Move> plan = FileOrganizer.plan( dl, System.currentTimeMillis() );
        eq( "plan size (png, pdf, zip only)", 3, plan.size() );
        eq( "summary", true, FileOrganizer.summary( plan ).startsWith( "3 files" ) );
        eq( "category", "Documents", FileOrganizer.categoryOf( "x.PDF" ) );
        eq( "partial download ignored", null, FileOrganizer.categoryOf( "x.crdownload" ) );

        // tool flow with confirmation
        final FakeWin fw = new FakeWin();
        fw.wins.add( w( "Google Chrome" ) );
        final List<String> said = new ArrayList<>();
        final boolean[] yes = { false };
        final Path journal = root.resolve( "journal.tsv" );
        final AgentTools t = new AgentTools( p, fw, ( ti, m ) -> yes[ 0 ], said::add, journal, home );

        t.handleNow( "ok [DO:window|left|chrome]" );
        eq( "window applied", List.of( "LEFT:Google Chrome" ), fw.applied );
        t.handleNow( "[DO:organize|Downloads]" );
        eq( "declined: nothing moved", true, Files.exists( dl.resolve( "a.png" ) ) );
        yes[ 0 ] = true;
        t.handleNow( "[DO:organize|Downloads]" );
        eq( "moved png", true, Files.exists( dl.resolve( "Images" ).resolve( "a.png" ) ) );
        eq( "moved pdf", true, Files.exists( dl.resolve( "Documents" ).resolve( "b.PDF" ) ) );
        eq( "clash left alone", true, Files.exists( dl.resolve( "taken.png" ) ) );
        eq( "unknown stays", true, Files.exists( dl.resolve( "weird.xyz" ) ) );
        eq( "fresh stays", true, Files.exists( dl.resolve( "fresh.png" ) ) );
        t.handleNow( "[DO:undo]" );
        eq( "undo restores", true, Files.exists( dl.resolve( "a.png" ) ) && Files.exists( dl.resolve( "b.PDF" ) ) );
        eq( "undo removes empty category dir", false, Files.exists( dl.resolve( "Documents" ) ) );
        eq( "pre-existing Images dir kept", true, Files.exists( dl.resolve( "Images" ).resolve( "taken.png" ) ) );
        eq( "second undo", -1, FileOrganizer.undoLast( journal ) );

        // refusals
        said.clear();
        t.handleNow( "[DO:organize|Documents]" );
        eq( "disallowed folder says so", true, said.size() == 1 && said.get( 0 ).contains( "Downloads" ) );
        t.handleNow( "[DO:organize|../..]" );
        eq( "traversal refused", true, said.size() == 2 );

        // kill switch + disabled
        fw.applied.clear();
        AgentPolicy.halt();
        t.handleNow( "[DO:window|left|chrome]" );
        eq( "halted does nothing", 0, fw.applied.size() );
        eq( "halted prompt empty", "", t.promptBlock() );
        AgentPolicy.resume();
        eq( "prompt lists windows", true, t.promptBlock().contains( "Google Chrome" ) );
        final Properties off = new Properties();
        eq( "disabled prompt empty", "", new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).promptBlock() );

        System.out.println( fails == 0 ? "AgentToolsTest: all passed" : "AgentToolsTest: " + fails + " FAILED" );
        if( fails > 0 ) System.exit( 1 );
    }
}
