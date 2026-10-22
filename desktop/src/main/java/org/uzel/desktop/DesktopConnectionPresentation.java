package org.uzel.desktop;

/** Local prerequisites are separate from possession of a signed route or proven connectivity. */
final class DesktopConnectionPresentation {
    private DesktopConnectionPresentation(){}
    static String reason(boolean sharing,boolean measuring,int selectedApplications,boolean supported,boolean installed,boolean eligible) {
        if(measuring)return "Дождитесь замера";
        if(!sharing)return "Включите раздачу";
        if(selectedApplications==0)return "Выберите приложения";
        if(!supported)return "Нужна Windows x64";
        if(!installed)return "Нужен сетевой компонент";
        return eligible?"Можно подключиться":"Маршрут не получен";
    }
}
