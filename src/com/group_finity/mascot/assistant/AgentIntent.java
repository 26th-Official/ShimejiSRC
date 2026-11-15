package com.group_finity.mascot.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the user's OWN words for an agent command, so common requests work even when a small local
 * model never emits a {@code [DO:...]} tag. Returns the same {tool, arg...} arrays as
 * {@link AgentTools#parse}. Only ever fed the user's message, never screen text or other audio.
 */
public final class AgentIntent
{
    private AgentIntent() { }

    private static final String DIR = "(left|right|top|bottom)";
    private static final Pattern SNAP_A = Pattern.compile( "^(?:snap|dock|put|move|send|tile)\\s+(.+?)\\s+(?:window\\s+)?(?:to|on|onto|at)\\s+(?:the\\s+)?" + DIR + "(?:\\s+(?:half|side|part))?(?:\\s+of\\s+(?:the|my)\\s+screen)?$" );
    private static final Pattern SNAP_B = Pattern.compile( "^(?:snap|dock|put|move|send|tile)\\s+(?:the\\s+|my\\s+)?" + DIR + "\\s+(?:window\\s+)?(?:to|on)\\s+(.+)$" ); // rare; kept simple
    private static final Pattern CENTER = Pattern.compile( "^(?:center|centre)\\s+(.+)$" );
    private static final Pattern SIMPLE = Pattern.compile(
        "^(minimi[sz]e|hide|maximi[sz]e|fullscreen|restore|unminimi[sz]e|focus|switch\\s+to|bring\\s+up)\\s+(.+)$" );
    private static final Pattern ORGANIZE = Pattern.compile(
        "^(?:clean|tidy|organi[sz]e|sort)(?:\\s+up)?\\s+(?:out\\s+)?(?:the\\s+|my\\s+)?([a-z0-9 _-]+?)(?:\\s+folder)?$" );
    private static final Pattern UNDO = Pattern.compile( "^undo(?:\\s+(?:that|it|the\\s+(?:tidy|cleanup|clean up|sort|organi[sz]ing)))?$" );

    private static final Pattern CARRY = Pattern.compile(
        "^(?:carry|take|haul|lug|pick up|pickup|hold|lift)\\s+(.+?)(?:\\s+(?:and\\s+carry\\s+(?:it\\s+)?)?(?:around|about|with you|for a walk|along))?$" );
    /** "for a minute", "for a while", "for 5 minutes", "for a couple of minutes"... */
    private static final Pattern DURATION = Pattern.compile(
        "\\s+for\\s+(?:(?:a|an|one)\\s+(minute|min|while|bit|little while|little|moment|second|sec)|(?:a\\s+)?(?:few|couple(?:\\s+of)?)\\s+minutes?|some\\s+time|(\\d+)\\s*(seconds?|secs?|minutes?|mins?))\\b" );

    /** Seconds named by the duration phrase in {@code t}, or -1 when there is none. */
    static long durationSeconds( final String t )
    {
        final Matcher d = DURATION.matcher( t );
        if( !d.find() ) return -1;
        if( d.group( 2 ) != null ) return Long.parseLong( d.group( 2 ) ) * ( d.group( 3 ).startsWith( "m" ) ? 60 : 1 );
        final String w = d.group( 1 );
        if( w == null ) return 180;                     // a few / a couple of minutes / some time
        switch( w )
        {
            case "minute": case "min": return 60;
            case "second": case "sec": return 10;
            case "bit": case "little": case "moment": return 60;
            default: return 180;                         // a while, a little while
        }
    }
    private static final Pattern DROP = Pattern.compile(
        "^(?:(?:ok(?:ay)?\\s+)?(?:drop|release|put down|set down|let go of|let go|stop carrying)(?:\\s+(?:it|that|them|the window|this window|my window|the app))?(?:\\s+down)?|put (?:it|that|the window|this window|my window) down|that'?s enough|you can put (?:it|that) down)$" );

    /** Tags implied by what the user said, or an empty list. */
    public static List<String[]> parse( final String userText )
    {
        final List<String[]> out = new ArrayList<>();
        if( userText == null ) return out;
        String t = userText.toLowerCase( Locale.ROOT ).trim();
        t = t.replaceAll( "[.!?,]+$", "" ).replaceAll( "^(?:(?:hey|ok|okay|please|can you|could you|would you|will you|go ahead and|just)[ ,]+)+", "" )
             .replaceAll( "\\s+please$", "" ).replaceAll( "\\s+", " " ).trim();
        Matcher m;
        if( DROP.matcher( t ).matches() ) { out.add( new String[] { "drop" } ); return out; }
        if( wantsCarry( t ) )
        {
            final long dur = durationSeconds( t );
            final String bare = DURATION.matcher( t ).replaceAll( "" ).trim();
            if( ( m = CARRY.matcher( bare ) ).matches() )
            {
                out.add( new String[] { "carry", clean( m.group( 1 ) ), Long.toString( Math.min( 600, dur < 0 ? 120 : dur ) ) } );
                return out;
            }
        }
        if( ( m = UNDO.matcher( t ) ).matches() ) { out.add( new String[] { "undo" } ); return out; }
        if( ( m = ORGANIZE.matcher( t ) ).matches() ) { out.add( new String[] { "organize", m.group( 1 ).trim() } ); return out; }
        if( ( m = SNAP_A.matcher( t ) ).matches() ) { out.add( new String[] { "window", m.group( 2 ), clean( m.group( 1 ) ) } ); return out; }
        if( ( m = SNAP_B.matcher( t ) ).matches() ) { out.add( new String[] { "window", m.group( 1 ), clean( m.group( 2 ) ) } ); return out; }
        if( ( m = CENTER.matcher( t ) ).matches() ) { out.add( new String[] { "window", "center", clean( m.group( 1 ) ) } ); return out; }
        if( ( m = SIMPLE.matcher( t ) ).matches() )
        {
            final String verb = m.group( 1 );
            final String op = verb.startsWith( "minimi" ) || verb.equals( "hide" ) ? "minimize"
                : verb.startsWith( "maximi" ) || verb.equals( "fullscreen" ) ? "maximize"
                : verb.equals( "restore" ) || verb.startsWith( "unminimi" ) ? "restore" : "focus";
            final String target = clean( m.group( 2 ) );
            if( !target.isEmpty() && !target.startsWith( "on " ) && !target.startsWith( "me " ) ) out.add( new String[] { "window", op, target } );
        }
        return out;
    }

    /** "take" and "hold" are everyday verbs; only treat them as carry when the user also says around/carry/walk. */
    private static boolean wantsCarry( final String t )
    {
        return t.contains( "carry" ) || t.contains( "around" ) || t.contains( "with you" ) || t.contains( "for a walk" )
            || t.startsWith( "pick up" ) || t.startsWith( "pickup" ) || t.startsWith( "lift" );
    }

    /** Drop filler so "my browser window" becomes "browser". */
    static String clean( final String s )
    {
        return s.replaceAll( "\\b(?:the|my|this|that|window|app|application|program)\\b", " " ).replaceAll( "\\s+", " " ).trim();
    }
}
