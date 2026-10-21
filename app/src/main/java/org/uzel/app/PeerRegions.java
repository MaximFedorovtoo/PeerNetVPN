package org.uzel.app;

import java.util.Locale;

/** CLDR 48 territoryContainment, the geography model used by ICU.
 * Android ICU Region is hidden from the public SDK; no hidden API/reflection is used.
 * Source: https://github.com/unicode-org/cldr/blob/release-48/common/supplemental/supplementalData.xml
 * Data copyright Unicode, Inc. https://www.unicode.org/license.txt
 * Unmapped/invalid territories explicitly remain Unknown; country claims are not location proof.
 */
final class PeerRegions {
    private PeerRegions(){}
    static final int ALL=0,AFRICA=1,AMERICAS=2,ASIA=3,EUROPE=4,OCEANIA=5,UNKNOWN=6;
    private static final String[] COUNTRIES={
"",
" AO BF BI BJ BW CD CF CG CI CM CV DJ DZ EA EG EH ER ET GA GH GM GN GQ GW IC IO KE KM LR LS LY MA MG ML MR MU MW MZ NA NE NG RE RW SC SD SH SL SN SO SS ST SZ TD TF TG TN TZ UG YT ZA ZM ZW ",
" AG AI AR AW BB BL BM BO BQ BR BS BV BZ CA CL CO CR CU CW DM DO EC FK GD GF GL GP GS GT GY HN HT JM KN KY LC MF MQ MS MX NI PA PE PM PR PY SR SV SX TC TT US UY VC VE VG VI ",
" AE AF AM AZ BD BH BN BT CN CY GE HK ID IL IN IQ IR JO JP KG KH KP KR KW KZ LA LB LK MM MN MO MV MY NP OM PH PK PS QA SA SG SY TH TJ TL TM TR TW UZ VN YE ",
" AD AL AT AX BA BE BG BY CH CQ CZ DE DK EE ES FI FO FR GB GG GI GR HR HU IE IM IS IT JE LI LT LU LV MC MD ME MK MT NL NO PL PT RO RS RU SE SI SJ SK SM UA VA XK ",
" AS AU CC CK CX FJ FM GU HM KI MH MP NC NF NR NU NZ PF PG PN PW QO SB TK TO TV UM VU WF WS "
    };
    static int group(String country){
        if(country==null)return UNKNOWN;
        String code=country.toUpperCase(Locale.ROOT);
        if(!code.matches("[A-Z]{2}"))return UNKNOWN;
        for(int i=1;i<COUNTRIES.length;i++)if(COUNTRIES[i].contains(" "+code+" "))return i;
        return UNKNOWN;
    }
}