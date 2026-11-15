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
        public boolean carry( final Info w, final Rectangle m, final Rectangle s ) { applied.add( "CARRY:" + w.title ); return true; }
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
        eq( "halted prompt denies abilities", true, t.promptBlock().contains( "cannot move" ) );
        AgentPolicy.resume();
        eq( "prompt lists windows", true, t.promptBlock().contains( "Google Chrome" ) );
        final Properties off = new Properties();
        eq( "disabled prompt denies abilities", true, new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).promptBlock().contains( "cannot move" ) );
        said.clear();
        new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).handleUser( "minimize my browser", "[DO:window|minimize|chrome]" );
        eq( "disabled tells user how to enable", true, said.size() == 1 && said.get( 0 ).contains( "AgentEnabled=true" ) );
        said.clear();
        new AgentTools( off, fw, ( x, y ) -> true, said::add, journal, home ).handleUser( "what is the weather", "" );
        eq( "disabled + chit-chat is silent", 0, said.size() );

        // intent from the user's own words
        eq( "minimize browser", "window|minimize|browser", String.join( "|", AgentIntent.parse( "Can you minimize my browser?" ).get( 0 ) ) );
        eq( "snap left", "window|left|notepad", String.join( "|", AgentIntent.parse( "snap notepad to the left" ).get( 0 ) ) );
        eq( "put on right half", "window|right|discord", String.join( "|", AgentIntent.parse( "please put the discord window on the right half of the screen" ).get( 0 ) ) );
        eq( "center", "window|center|chrome", String.join( "|", AgentIntent.parse( "center chrome" ).get( 0 ) ) );
        eq( "switch to", "window|focus|spotify", String.join( "|", AgentIntent.parse( "switch to spotify" ).get( 0 ) ) );
        eq( "clean up downloads", "organize|downloads", String.join( "|", AgentIntent.parse( "clean up my downloads" ).get( 0 ) ) );
        eq( "tidy folder", "organize|downloads", String.join( "|", AgentIntent.parse( "tidy the downloads folder" ).get( 0 ) ) );
        eq( "undo", "undo", String.join( "|", AgentIntent.parse( "undo that" ).get( 0 ) ) );
        eq( "chit-chat is not a command", 0, AgentIntent.parse( "how are you today" ).size() );
        eq( "question is not a command", 0, AgentIntent.parse( "what does minimize mean" ).size() );
        eq( "sentence is not a command", 0, AgentIntent.parse( "I like to show my friends the cat" ).size() );

        eq( "show me is chit-chat", 0, AgentIntent.parse( "show me the time" ).size() );
        eq( "focus on is chit-chat", 0, AgentIntent.parse( "focus on my work" ).size() );

        // carry / drop intent
        eq( "carry this window", "carry||180", String.join( "|", AgentIntent.parse( "hey, carry this window around for a while" ).get( 0 ) ) );
        eq( "carry named", "carry|browser|120", String.join( "|", AgentIntent.parse( "carry my browser around" ).get( 0 ) ) );
        eq( "carry minutes", "carry|notepad|300", String.join( "|", AgentIntent.parse( "carry notepad around for 5 minutes" ).get( 0 ) ) );
        eq( "carry capped", "carry|chrome|600", String.join( "|", AgentIntent.parse( "carry chrome around for 90 minutes" ).get( 0 ) ) );
        eq( "pick up", "carry|discord|120", String.join( "|", AgentIntent.parse( "pick up discord" ).get( 0 ) ) );
        eq( "drop it", "drop", String.join( "|", AgentIntent.parse( "ok drop it" ).get( 0 ) ) );
        eq( "put it down", "drop", String.join( "|", AgentIntent.parse( "put it down" ).get( 0 ) ) );
        eq( "stop carrying", "drop", String.join( "|", AgentIntent.parse( "stop carrying" ).get( 0 ) ) );
        eq( "take is chit-chat", 0, AgentIntent.parse( "take a look at this" ).size() );
        eq( "hold is chit-chat", 0, AgentIntent.parse( "hold on a second" ).size() );

        // carry geometry: centred above the mascot, clamped
        final Rectangle scr = new Rectangle( 0, 0, 1920, 1080 );
        eq( "carry above head", new Rectangle( 830, 396, 400, 300 ), AgentLayout.carryTarget( new Rectangle( 1000, 700, 60, 80 ), 400, 300, scr ) );
        eq( "carry clamps left/top", new Rectangle( 0, 0, 400, 300 ), AgentLayout.carryTarget( new Rectangle( 10, 100, 60, 80 ), 400, 300, scr ) );
        eq( "carry clamps right", new Rectangle( 1520, 396, 400, 300 ), AgentLayout.carryTarget( new Rectangle( 1900, 700, 60, 80 ), 400, 300, scr ) );
        eq( "huge window pinned", new Rectangle( 0, 0, 2000, 1200 ), AgentLayout.carryTarget( new Rectangle( 900, 900, 60, 80 ), 2000, 1200, scr ) );

        // carry via tool: "this window" uses the active window, drop stops it
        final FakeWin cw = new FakeWin();
        cw.wins.add( w( "Notes - Editor" ) );
        final List<String> csaid = new ArrayList<>();
        final AgentTools ct = new AgentTools( p, cw, ( x, y ) -> true, csaid::add, journal, home )
            .withHost( () -> new Rectangle( 500, 500, 60, 80 ), () -> "Notes - Editor" );
        ct.handleNow( "[DO:carry||60]" );
        Thread.sleep( 200 );
        eq( "carry started and moving", true, cw.applied.contains( "CARRY:Notes - Editor" ) );
        eq( "carried hidden from surface scan", true, AgentCarry.isCarried( 1 ) );
        ct.handleNow( "[DO:drop]" );
        Thread.sleep( 200 );
        eq( "dropped", false, AgentCarry.isCarried( 1 ) );
        eq( "drop reported", true, csaid.stream().anyMatch( x -> x.contains( "Here you go" ) ) );
        csaid.clear();
        ct.handleNow( "[DO:drop]" );
        eq( "drop with nothing", true, csaid.size() == 1 && csaid.get( 0 ).contains( "not carrying" ) );

        // generic folder word tidies the single allowed folder
        said.clear();
        yes[ 0 ] = false;
        t.handleNow( "[DO:organize|drive]" );
        eq( "generic word reaches the Downloads preview", true, said.size() == 1 && said.get( 0 ).contains( "left it alone" ) );

        // aliases
        final List<WindowControl.Info> br = List.of( w( "Inbox - Mozilla Firefox" ), w( "Discord" ) );
        eq( "browser alias", "Inbox - Mozilla Firefox", AgentTools.matchWindow( br, "browser" ).match.title );
        eq( "librewolf is a browser", "Home - LibreWolf", AgentTools.matchWindow( List.of( w( "Home - LibreWolf" ), w( "Discord" ) ), "browser" ).match.title );
        eq( "two browsers ambiguous", null, AgentTools.matchWindow( List.of( w( "A - Google Chrome" ), w( "B - Mozilla Firefox" ) ), "browser" ).match );

        System.out.println( fails == 0 ? "AgentToolsTest: all passed" : "AgentToolsTest: " + fails + " FAILED" );
        if( fails > 0 ) System.exit( 1 );
    }
}
