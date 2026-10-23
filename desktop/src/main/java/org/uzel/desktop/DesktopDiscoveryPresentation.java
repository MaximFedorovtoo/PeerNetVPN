package org.uzel.desktop;

/** Empty discovery state is independent of unrelated sharing or connection errors. */
final class DesktopDiscoveryPresentation {
    record EmptyState(String title,String hint,String action,String artwork){}
    private DesktopDiscoveryPresentation(){}
    static EmptyState empty(String state,boolean searching,boolean failed,boolean regionFiltered) {
        if(searching||"SEARCHING".equals(state))return new EmptyState("Ищем узлы…","Проверяем доступные каталоги","Поиск…","loading");
        if("NOT_CONFIGURED".equals(state))return new EmptyState("Каталог не настроен","Добавьте код каталога в профиле","Добавить каталог","search");
        if("UNAVAILABLE".equals(state))return new EmptyState("Нет связи с каталогом","Проверьте интернет или повторите поиск","Повторить поиск","offline");
        if(failed)return new EmptyState("Поиск не завершён","Повторите поиск по доступным каталогам","Повторить поиск","offline");
        if("ONLINE".equals(state))return regionFiltered?new EmptyState("В этом регионе нет узлов","Выберите другой регион","Все регионы","search"):new EmptyState("Узлы не найдены","Успешный запрос не вернул устройств","Повторить поиск","search");
        return new EmptyState("Поиск ещё не запущен","Проверьте доступные каталоги","Начать поиск","search");
    }
}
