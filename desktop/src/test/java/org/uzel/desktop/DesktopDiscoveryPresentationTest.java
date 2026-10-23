package org.uzel.desktop;

import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopDiscoveryPresentationTest {
    @Test public void emptyStateReflectsActualDiscoveryInsteadOfConnectionErrors() {
        assertEquals("Ищем узлы…",DesktopDiscoveryPresentation.empty("ONLINE",true,false,false).title());
        assertEquals("Нет связи с каталогом",DesktopDiscoveryPresentation.empty("UNAVAILABLE",false,false,false).title());
        assertEquals("Каталог не настроен",DesktopDiscoveryPresentation.empty("NOT_CONFIGURED",false,false,false).title());
        assertEquals("Узлы не найдены",DesktopDiscoveryPresentation.empty("ONLINE",false,false,false).title());
        assertEquals("В этом регионе нет узлов",DesktopDiscoveryPresentation.empty("ONLINE",false,false,true).title());
        assertEquals("Поиск ещё не запущен",DesktopDiscoveryPresentation.empty(null,false,false,false).title());
        assertEquals("Поиск не завершён",DesktopDiscoveryPresentation.empty("ONLINE",false,true,false).title());
    }
}
