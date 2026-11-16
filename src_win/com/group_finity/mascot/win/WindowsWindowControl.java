package com.group_finity.mascot.win;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import com.group_finity.mascot.assistant.WindowControl;
import com.group_finity.mascot.win.jna.MONITORINFO;
import com.group_finity.mascot.win.jna.POINT;
import com.group_finity.mascot.win.jna.RECT;
import com.group_finity.mascot.win.jna.User32;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/** Win32 backend for {@link WindowControl}. */
public final class WindowsWindowControl implements WindowControl
{
    private static final int SW_MAXIMIZE = 3, SW_MINIMIZE = 6, SW_RESTORE = 9;
    private static final java.util.Set<String> SKIP_CLASSES = java.util.Set.of(
        "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "Progman", "WorkerW", "Windows.UI.Core.CoreWindow" );

    @Override
    public List<Info> list()
    {
        final List<Info> out = new ArrayList<>();
        final int self = (int) ProcessHandle.current().pid();
        final User32 u = User32.INSTANCE;
        u.EnumWindows( ( hWnd, arg ) ->
        {
            try
            {
                if( u.IsWindowVisible( hWnd ) == 0 ) return true;
                if( ( u.GetWindowLongW( hWnd, User32.GWL_EXSTYLE ) & User32.WS_EX_TOOLWINDOW ) != 0 ) return true;
                final char[] t = new char[ 256 ];
                final int n = u.GetWindowTextW( hWnd, t, t.length );
                if( n <= 0 ) return true;
                final char[] c = new char[ 128 ];
                final int cn = u.GetClassNameW( hWnd, c, c.length );
                if( cn > 0 && SKIP_CLASSES.contains( new String( c, 0, cn ) ) ) return true;
                final int[] pid = new int[ 1 ];
                u.GetWindowThreadProcessId( hWnd, pid );
                if( pid[ 0 ] == self ) return true;
                final RECT r = new RECT();
                u.GetWindowRect( hWnd, r );
                out.add( new Info( Pointer.nativeValue( hWnd ), new String( t, 0, n ),
                    new Rectangle( r.left, r.top, r.right - r.left, r.bottom - r.top ), u.IsIconic( hWnd ) != 0 ) );
            }
            catch( final Throwable ignored ) { }
            return true;
        }, null );
        return out;
    }

    @Override
    public Info current( final long handle )
    {
        final User32 u = User32.INSTANCE;
        final Pointer h = new Pointer( handle );
        if( u.IsWindow( h ) == 0 ) return null;
        final RECT r = new RECT();
        u.GetWindowRect( h, r );
        final char[] t = new char[ 256 ];
        final int n = u.GetWindowTextW( h, t, t.length );
        return new Info( handle, n > 0 ? new String( t, 0, n ) : "", new Rectangle( r.left, r.top, r.right - r.left, r.bottom - r.top ),
            u.IsIconic( h ) != 0, u.IsZoomed( h ) != 0 );
    }

    @Override
    public List<Rectangle> monitors()
    {
        final List<Rectangle> out = new ArrayList<>();
        final User32.MONITORENUMPROC cb = ( hMon, hdc, rc, data ) ->
        {
            final MONITORINFO mi = new MONITORINFO();
            mi.cbSize = new NativeLong( mi.size() );
            mi.write();
            if( User32.INSTANCE.GetMonitorInfoW( hMon, mi ) )
            {
                mi.read();
                out.add( new Rectangle( mi.rcWork.left, mi.rcWork.top,
                    mi.rcWork.right - mi.rcWork.left, mi.rcWork.bottom - mi.rcWork.top ) );
            }
            return true;
        };
        try { User32.INSTANCE.EnumDisplayMonitors( null, null, cb, null ); }
        catch( final Throwable t ) { return new ArrayList<>(); }
        out.sort( java.util.Comparator.<Rectangle>comparingInt( r -> r.x ).thenComparingInt( r -> r.y ) );
        return out;
    }

    @Override
    public boolean place( final Info w, final Rectangle r )
    {
        final User32 u = User32.INSTANCE;
        final Pointer h = new Pointer( w.handle );
        if( u.IsWindow( h ) == 0 ) return false;
        u.MoveWindow( h, r.x, r.y, r.width, r.height, 1 );
        return true;
    }

    @Override
    public boolean moveTo( final Info w, final int x, final int y )
    {
        final User32 u = User32.INSTANCE;
        final Pointer h = new Pointer( w.handle );
        if( u.IsWindow( h ) == 0 ) return false;
        u.MoveWindow( h, x, y, w.bounds.width, w.bounds.height, 1 );
        return true;
    }

    @Override
    public boolean foreground( final Info w )
    {
        final User32 u = User32.INSTANCE;
        final Pointer h = new Pointer( w.handle );
        if( u.IsWindow( h ) == 0 ) return false;
        if( u.IsIconic( h ) != 0 ) u.ShowWindow( h, SW_RESTORE );
        u.BringWindowToTop( h );
        u.SetForegroundWindow( h );
        try { Thread.sleep( 150 ); } catch( final InterruptedException e ) { return false; }
        final Pointer f = u.GetForegroundWindow();
        return f != null && Pointer.nativeValue( f ) == w.handle;
    }

    @Override
    public String foregroundTitle()
    {
        final User32 u = User32.INSTANCE;
        final Pointer f = u.GetForegroundWindow();
        if( f == null ) return null;
        final char[] t = new char[ 256 ];
        final int n = u.GetWindowTextW( f, t, t.length );
        return n > 0 ? new String( t, 0, n ) : null;
    }

    @Override
    public boolean apply( final Info w, final Op op )
    {
        final User32 u = User32.INSTANCE;
        final Pointer h = new Pointer( w.handle );
        if( u.IsWindow( h ) == 0 ) return false;
        switch( op )
        {
            case MINIMIZE: return u.ShowWindow( h, SW_MINIMIZE ) || true;
            case MAXIMIZE: u.ShowWindow( h, SW_MAXIMIZE ); return true;
            case RESTORE:  u.ShowWindow( h, SW_RESTORE ); return true;
            case FOCUS:
                if( u.IsIconic( h ) != 0 ) u.ShowWindow( h, SW_RESTORE );
                u.BringWindowToTop( h );
                return u.SetForegroundWindow( h ) || true; // Windows may refuse focus steal; the raise still happened
            default:
                break;
        }
        // Snap: restore first, then size to part of the work area of the monitor the window is on.
        if( u.IsIconic( h ) != 0 || u.IsZoomed( h ) != 0 ) u.ShowWindow( h, SW_RESTORE );
        final POINT.ByValue pt = new POINT.ByValue();
        pt.x = w.bounds.x + w.bounds.width / 2;
        pt.y = w.bounds.y + w.bounds.height / 2;
        final Pointer mon = u.MonitorFromPoint( pt, 2 );
        if( mon == null ) return false;
        final MONITORINFO mi = new MONITORINFO();
        mi.cbSize = new NativeLong( mi.size() );
        mi.write();
        if( !u.GetMonitorInfoW( mon, mi ) ) return false;
        mi.read();
        final Rectangle wa = new Rectangle( mi.rcWork.left, mi.rcWork.top,
            mi.rcWork.right - mi.rcWork.left, mi.rcWork.bottom - mi.rcWork.top );
        final Rectangle t = com.group_finity.mascot.assistant.AgentLayout.target( wa, op );
        u.MoveWindow( h, t.x, t.y, t.width, t.height, 1 );
        return true;
    }
}
