package com.group_finity.mascot.assistant;

import java.awt.Rectangle;

/** Pure geometry for window snapping, kept apart from the OS code so it can be tested. */
public final class AgentLayout
{
    private AgentLayout() { }

    /** Target rectangle inside work area {@code wa} for a snap operation. */
    public static Rectangle target( final Rectangle wa, final WindowControl.Op op )
    {
        final int hw = wa.width / 2, hh = wa.height / 2;
        switch( op )
        {
            case LEFT:   return new Rectangle( wa.x, wa.y, hw, wa.height );
            case RIGHT:  return new Rectangle( wa.x + wa.width - hw, wa.y, hw, wa.height );
            case TOP:    return new Rectangle( wa.x, wa.y, wa.width, hh );
            case BOTTOM: return new Rectangle( wa.x, wa.y + wa.height - hh, wa.width, hh );
            case CENTER:
            {
                final int w = wa.width * 7 / 10, h = wa.height * 7 / 10;
                return new Rectangle( wa.x + ( wa.width - w ) / 2, wa.y + ( wa.height - h ) / 2, w, h );
            }
            default: return new Rectangle( wa );
        }
    }
}
