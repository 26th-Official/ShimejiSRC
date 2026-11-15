package com.group_finity.mascot.behavior;

import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.action.Action;
import com.group_finity.mascot.config.Configuration;
import com.group_finity.mascot.exception.CantBeAliveException;

/**
 * One step of an agent errand: plays a named action of the mascot's own set (e.g. {@code Walk} with a
 * {@code TargetX}) and reports when it ended. Unlike an ordinary behavior it never throws
 * {@link CantBeAliveException} for a failed step (that would remove the mascot): it reports failure
 * and then idles until the errand director gives the mascot its next step or hands it back.
 * If the user grabs the mascot the leg fails and the normal drag takes over.
 */
public final class ErrandLeg implements Behavior
{
    private final String actionName;
    private final Map<String, String> params;
    private final Configuration configuration;
    private final Consumer<Boolean> done;
    private Action action;
    private Mascot mascot;
    private boolean finished = false;

    public ErrandLeg( final String actionName, final Map<String, String> params,
                      final Configuration configuration, final Consumer<Boolean> done )
    {
        this.actionName = actionName;
        this.params = params;
        this.configuration = configuration;
        this.done = done;
    }

    @Override public String toString() { return "ErrandLeg(" + actionName + ")"; }

    private void finish( final boolean ok )
    {
        if( finished ) return;
        finished = true;
        done.accept( ok );
    }

    @Override
    public synchronized void init( final Mascot mascot )
    {
        this.mascot = mascot;
        try
        {
            action = configuration.buildAction( actionName, params );
            action.init( mascot );
            if( !action.hasNext() ) finish( true );
        }
        catch( final Throwable t ) { finish( false ); }
    }

    @Override
    public synchronized void next()
    {
        if( finished || action == null ) return;
        try
        {
            if( action.hasNext() ) action.next();
            if( !action.hasNext() ) finish( true );
        }
        catch( final Throwable t ) { finish( false ); }
    }

    @Override
    public synchronized void mousePressed( final MouseEvent event ) throws CantBeAliveException
    {
        if( !SwingUtilities.isLeftMouseButton( event ) ) return;
        finish( false );
        try
        {
            mascot.setBehavior( configuration.buildBehavior( configuration.getSchema().getString( UserBehavior.BEHAVIOURNAME_DRAGGED ) ) );
        }
        catch( final Exception e ) { throw new CantBeAliveException( "drag", e ); }
    }

    @Override
    public synchronized void mouseReleased( final MouseEvent event ) throws CantBeAliveException
    {
        if( !SwingUtilities.isLeftMouseButton( event ) ) return;
        if( mascot.isDragging() )
        {
            try
            {
                mascot.setDragging( false );
                mascot.setBehavior( configuration.buildBehavior( configuration.getSchema().getString( UserBehavior.BEHAVIOURNAME_THROWN ) ) );
            }
            catch( final Exception e ) { throw new CantBeAliveException( "drop", e ); }
        }
    }
}
