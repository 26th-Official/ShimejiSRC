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

    /** Tags implied by what the user said, or an empty list. */
    public static List<String[]> parse( final String userText )
    {
        final List<String[]> out = new ArrayList<>();
        if( userText == null ) return out;
        String t = userText.toLowerCase( Locale.ROOT ).trim();
        t = t.replaceAll( "[.!?,]+$", "" ).replaceAll( "^(?:hey |ok |okay |please |can you |could you |would you |will you |go ahead and |just )+", "" )
             .replaceAll( "\\s+please$", "" ).replaceAll( "\\s+", " " ).trim();
        Matcher m;
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

    /** Drop filler so "my browser window" becomes "browser". */
    static String clean( final String s )
    {
        return s.replaceAll( "\\b(?:the|my|this|that|window|app|application|program)\\b", " " ).replaceAll( "\\s+", " " ).trim();
    }
}
