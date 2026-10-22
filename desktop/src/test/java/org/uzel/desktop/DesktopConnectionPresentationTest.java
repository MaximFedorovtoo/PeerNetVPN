package org.uzel.desktop;

import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopConnectionPresentationTest {
    @Test public void reasonsDistinguishLocalEligibilityFromMissingRoute() {
        assertEquals("Включите раздачу",DesktopConnectionPresentation.reason(false,false,0,true,false,false));
        assertEquals("Дождитесь замера",DesktopConnectionPresentation.reason(true,true,2,true,true,false));
        assertEquals("Выберите приложения",DesktopConnectionPresentation.reason(true,false,0,true,true,false));
        assertEquals("Нужна Windows x64",DesktopConnectionPresentation.reason(true,false,2,false,false,false));
        assertEquals("Нужен сетевой компонент",DesktopConnectionPresentation.reason(true,false,2,true,false,false));
        assertEquals("Маршрут не получен",DesktopConnectionPresentation.reason(true,false,2,true,true,false));
        assertEquals("Можно подключиться",DesktopConnectionPresentation.reason(true,false,2,true,true,true));
    }
}
