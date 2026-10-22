package org.uzel.desktop;

import java.awt.Window;
import java.util.Locale;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

/** Native DWM caption styling. Keeps Windows snap, resize, system menu and accessible controls. */
public final class WindowsCaption {
    public interface Dwm extends StdCallLibrary {
        int DwmSetWindowAttribute(Pointer window,int attribute,int[] value,int bytes);
        int DwmGetWindowAttribute(Pointer window,int attribute,int[] value,int bytes);
    }
    private WindowsCaption(){}
    static String apply(Window window) {
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"))return "native platform caption";
        try {
            Dwm dwm=Native.load("dwmapi",Dwm.class);Pointer handle=Native.getComponentPointer(window);
            int result=dwm.DwmSetWindowAttribute(handle,20,new int[]{1},4);
            // COLORREF is 0x00BBGGRR. Unsupported attributes simply return HRESULT failure.
            int caption=dwm.DwmSetWindowAttribute(handle,35,new int[]{0x00221303},4);
            int text=dwm.DwmSetWindowAttribute(handle,36,new int[]{0x00fff7ed},4);
            dwm.DwmSetWindowAttribute(handle,34,new int[]{0x00221303},4);
            int[] actual=new int[1];int read=dwm.DwmGetWindowAttribute(handle,35,actual,4);
            return "dark="+result+", caption="+caption+", text="+text+", read="+read+", color="+Integer.toHexString(actual[0]);
        }catch(LinkageError|RuntimeException ignored){return "native caption fallback";}
    }
}
