package com.group_finity.mascot.assistant;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a point in time in everyday words: "in 20 minutes", "at 8:40pm", "tomorrow at 9",
 * "on friday", "december 3rd at 2pm". Pure (clock and zone are parameters) so it can be tested.
 * Returns the instant plus the text with the time phrase cut out, or null when there is no time.
 * A clock time with no date means the next time it happens; a date with no clock means 9:00.
 */
public final class TimeParse
{
    private TimeParse() { }

    public static final class Result
    {
        public final long atMs;
        public final String rest;
        Result( final long atMs, final String rest ) { this.atMs = atMs; this.rest = rest; }
    }

    private static final String UNITS = "(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)";
    private static final String NUM = "(\\d+(?:\\.\\d+)?|an?|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty five|half an?|half|a quarter of an?)";
    private static final Pattern REL = Pattern.compile( "\\b(?:in|after)\\s+" + NUM + "\\s*" + UNITS + "\\b" );
    private static final Pattern REL_HALF = Pattern.compile( "\\b(?:in|after)\\s+(an hour and a half|a day and a half)\\b" );

    private static final String MONTHS = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    private static final Pattern MONTH_DAY = Pattern.compile( "\\b(?:on\\s+)?(?:the\\s+)?" + MONTHS + "\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b" );
    private static final Pattern DAY_MONTH = Pattern.compile( "\\b(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+of\\s+" + MONTHS + "\\b" );
    private static final Pattern NUMERIC = Pattern.compile( "\\b(?:on\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b" );
    private static final Pattern DAY_WORD = Pattern.compile( "\\b(?:(next|this|on)\\s+)?(today|tonight|tomorrow|monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)\\b" );

    private static final Pattern CLOCK_MER = Pattern.compile( "\\b(?:(?:at|by|around|until|till)\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?m\\b\\.?" );
    private static final Pattern CLOCK_COLON = Pattern.compile( "\\b(?:(?:at|by|around)\\s+)?(\\d{1,2}):(\\d{2})\\b" );
    private static final Pattern CLOCK_AT = Pattern.compile( "\\b(?:at|by|around)\\s+(\\d{1,2})\\b(?!\\s*(?:/|-|st\\b|nd\\b|rd\\b|th\\b|\\d|%|:))" );
    private static final Pattern CLOCK_WORD = Pattern.compile( "\\b(?:at\\s+)?(noon|midnight)\\b" );

    public static Result parse( final String text, final long nowMs, final ZoneId zone )
    {
        if( text == null ) return null;
        final String t = text.toLowerCase( Locale.ROOT );
        final boolean sameLen = t.length() == text.length();
        final List<int[]> spans = new ArrayList<>();
        final ZonedDateTime now = ZonedDateTime.ofInstant( java.time.Instant.ofEpochMilli( nowMs ), zone );

        // ---- relative: "in 20 minutes" ----
        Matcher m = REL_HALF.matcher( t );
        if( m.find() )
        {
            spans.add( new int[] { m.start(), m.end() } );
            final long ms = m.group( 1 ).startsWith( "an hour" ) ? 90L * 60_000 : 36L * 3_600_000;
            return new Result( nowMs + ms, cut( sameLen ? text : t, spans ) );
        }
        m = REL.matcher( t );
        if( m.find() )
        {
            final double n = number( m.group( 1 ) );
            final String u = m.group( 2 );
            final double unit = u.startsWith( "sec" ) ? 1000 : u.startsWith( "min" ) ? 60_000 : u.startsWith( "h" ) ? 3_600_000 : 86_400_000;
            if( n > 0 )
            {
                spans.add( new int[] { m.start(), m.end() } );
                return new Result( nowMs + Math.round( n * unit ), cut( sameLen ? text : t, spans ) );
            }
        }

        // ---- absolute: date part ----
        LocalDate date = null;
        boolean explicitDate = false, tonight = false, weekday = false, nextWord = false;
        m = MONTH_DAY.matcher( t );
        if( m.find() )
        {
            date = monthDay( month( m.group( 1 ) ), Integer.parseInt( m.group( 2 ) ), m.group( 3 ), now.toLocalDate() );
            if( date == null ) return null;
            spans.add( new int[] { m.start(), m.end() } );
            explicitDate = true;
        }
        if( date == null && ( m = DAY_MONTH.matcher( t ) ).find() )
        {
            date = monthDay( month( m.group( 2 ) ), Integer.parseInt( m.group( 1 ) ), null, now.toLocalDate() );
            if( date == null ) return null;
            spans.add( new int[] { m.start(), m.end() } );
            explicitDate = true;
        }
        if( date == null && ( m = NUMERIC.matcher( t ) ).find() )
        {
            date = monthDay( Integer.parseInt( m.group( 1 ) ), Integer.parseInt( m.group( 2 ) ), m.group( 3 ), now.toLocalDate() );
            if( date != null ) { spans.add( new int[] { m.start(), m.end() } ); explicitDate = true; }
        }
        if( date == null && ( m = DAY_WORD.matcher( t ) ).find() )
        {
            final String w = m.group( 2 );
            spans.add( new int[] { m.start(), m.end() } );
            explicitDate = true;
            if( w.equals( "today" ) ) date = now.toLocalDate();
            else if( w.equals( "tonight" ) ) { date = now.toLocalDate(); tonight = true; }
            else if( w.equals( "tomorrow" ) ) date = now.toLocalDate().plusDays( 1 );
            else
            {
                weekday = true;
                nextWord = "next".equals( m.group( 1 ) );
                final int delta = ( dow( w ).getValue() - now.getDayOfWeek().getValue() + 7 ) % 7;
                date = now.toLocalDate().plusDays( delta == 0 && nextWord ? 7 : delta );
            }
        }

        // ---- clock part ----
        int hour = -1, min = 0;
        boolean meridiem = false, pm = false;
        int[] clockSpan = null;
        if( ( m = CLOCK_MER.matcher( t ) ).find() )
        {
            hour = Integer.parseInt( m.group( 1 ) ); min = m.group( 2 ) == null ? 0 : Integer.parseInt( m.group( 2 ) );
            meridiem = true; pm = m.group( 3 ).equals( "p" ); clockSpan = new int[] { m.start(), m.end() };
        }
        else if( ( m = CLOCK_WORD.matcher( t ) ).find() )
        {
            hour = m.group( 1 ).equals( "noon" ) ? 12 : 0; min = 0; meridiem = true; pm = hour == 12;
            clockSpan = new int[] { m.start(), m.end() };
        }
        else if( ( m = CLOCK_COLON.matcher( t ) ).find() )
        {
            hour = Integer.parseInt( m.group( 1 ) ); min = Integer.parseInt( m.group( 2 ) );
            clockSpan = new int[] { m.start(), m.end() };
        }
        else if( ( m = CLOCK_AT.matcher( t ) ).find() )
        {
            hour = Integer.parseInt( m.group( 1 ) );
            clockSpan = new int[] { m.start(), m.end() };
        }
        if( clockSpan != null ) spans.add( clockSpan );
        if( date == null && hour < 0 ) return null;
        if( hour > 24 || min > 59 || ( meridiem && hour > 12 ) ) return null;
        if( hour == 24 ) hour = 0;

        // ---- resolve ----
        final LocalDate baseDate = date == null ? now.toLocalDate() : date;
        final boolean today = baseDate.equals( now.toLocalDate() );
        int h;
        if( hour < 0 ) h = tonight ? 20 : 9;                           // date with no clock
        else if( meridiem ) h = hour == 12 ? ( pm ? 12 : 0 ) : hour + ( pm ? 12 : 0 );
        else if( hour >= 13 || hour == 0 ) h = hour;                   // 24-hour
        else if( tonight ) h = hour == 12 ? 12 : hour + 12;
        else if( today )                                               // soonest future AM/PM
        {
            final int am = hour % 12, p = am + 12;
            final boolean amAhead = at( baseDate, am, min, zone ).toInstant().toEpochMilli() > nowMs;
            final boolean pmAhead = at( baseDate, p, min, zone ).toInstant().toEpochMilli() > nowMs;
            h = amAhead ? am : pmAhead ? p : am;                       // both gone today: tomorrow's morning one
        }
        else h = hour >= 7 && hour <= 11 ? hour : ( hour == 12 ? 12 : hour + 12 );   // other days: 7-11 am, 1-6 pm

        ZonedDateTime when = at( baseDate, h, min, zone );
        if( when.toInstant().toEpochMilli() <= nowMs )
        {
            if( date == null ) when = when.plusDays( 1 );                     // "at 8:40pm" said at 9pm -> tomorrow
            else if( weekday ) when = when.plusDays( 7 );
            else if( explicitDate && !today && m == null ) { /* leave the past date; caller reports it */ }
        }
        return new Result( when.toInstant().toEpochMilli(), cut( sameLen ? text : t, spans ) );
    }

    private static ZonedDateTime at( final LocalDate d, final int h, final int min, final ZoneId zone )
    {
        return ZonedDateTime.of( LocalDateTime.of( d, java.time.LocalTime.of( h % 24, min ) ), zone );
    }

    private static LocalDate monthDay( final int month, final int day, final String year, final LocalDate today )
    {
        try
        {
            if( year != null )
            {
                int y = Integer.parseInt( year );
                if( y < 100 ) y += 2000;
                return LocalDate.of( y, month, day );
            }
            LocalDate d = LocalDate.of( today.getYear(), month, day );
            if( d.isBefore( today ) ) d = d.plusYears( 1 );
            return d;
        }
        catch( final Exception e ) { return null; }
    }

    private static int month( final String s )
    {
        final String[] names = { "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec" };
        for( int i = 0; i < 12; i++ ) if( s.startsWith( names[ i ] ) ) return i + 1;
        return 1;
    }

    private static DayOfWeek dow( final String w )
    {
        if( w.startsWith( "mon" ) ) return DayOfWeek.MONDAY;
        if( w.startsWith( "tue" ) ) return DayOfWeek.TUESDAY;
        if( w.startsWith( "wed" ) ) return DayOfWeek.WEDNESDAY;
        if( w.startsWith( "thu" ) ) return DayOfWeek.THURSDAY;
        if( w.startsWith( "fri" ) ) return DayOfWeek.FRIDAY;
        if( w.startsWith( "sat" ) ) return DayOfWeek.SATURDAY;
        return DayOfWeek.SUNDAY;
    }

    private static double number( final String s )
    {
        switch( s )
        {
            case "a": case "an": case "one": return 1;
            case "two": return 2; case "three": return 3; case "four": return 4; case "five": return 5;
            case "six": return 6; case "seven": return 7; case "eight": return 8; case "nine": return 9;
            case "ten": return 10; case "fifteen": return 15; case "twenty": return 20; case "thirty": return 30;
            case "forty five": return 45;
            case "half": case "half a": case "half an": return 0.5;
            case "a quarter of an": return 0.25;
            default: try { return Double.parseDouble( s ); } catch( final NumberFormatException e ) { return -1; }
        }
    }

    /** Remove the spans, drop prepositions left dangling at an end, tidy spaces. */
    private static String cut( final String s, final List<int[]> spans )
    {
        spans.sort( ( a, b ) -> b[ 0 ] - a[ 0 ] );
        final StringBuilder sb = new StringBuilder( s );
        for( final int[] sp : spans ) if( sp[ 0 ] >= 0 && sp[ 1 ] <= sb.length() ) sb.delete( sp[ 0 ], sp[ 1 ] );
        String r = sb.toString().replaceAll( "\\s+", " " ).trim();
        r = r.replaceAll( "(?i)\\s+(?:at|on|by|in|for|around|to|and|then|until|till)$", "" )
             .replaceAll( "(?i)^(?:and|then)\\s+", "" ).trim();
        return r.replaceAll( "[,;]+$", "" ).trim();
    }
}
