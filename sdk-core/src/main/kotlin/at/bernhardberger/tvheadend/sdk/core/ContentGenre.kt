package at.bernhardberger.tvheadend.sdk.core

/**
 * Level-1 content class of an ETSI EN 300 468 content descriptor (Table 28).
 *
 * [code] is the high nibble of the descriptor byte that TVHeadend reports as `contentType`.
 */
public enum class ContentCategory(public val code: Int) {
    /** Movie/drama (`0x1`). */
    MOVIE_DRAMA(0x1),

    /** News/current affairs (`0x2`). */
    NEWS_CURRENT_AFFAIRS(0x2),

    /** Show/game show (`0x3`). */
    SHOW_GAME_SHOW(0x3),

    /** Sports (`0x4`). */
    SPORTS(0x4),

    /** Children's/youth programmes (`0x5`). */
    CHILDREN_YOUTH(0x5),

    /** Music/ballet/dance (`0x6`). */
    MUSIC_BALLET_DANCE(0x6),

    /** Arts/culture, without music (`0x7`). */
    ARTS_CULTURE(0x7),

    /** Social/political issues/economics (`0x8`). */
    SOCIAL_POLITICAL_ECONOMICS(0x8),

    /** Education/science/factual topics (`0x9`). */
    EDUCATION_SCIENCE_FACTUAL(0x9),

    /** Leisure hobbies (`0xA`). */
    LEISURE_HOBBIES(0xA),

    /** Special characteristics (`0xB`). */
    SPECIAL_CHARACTERISTICS(0xB),

    /** User defined (`0xF`); the broadcaster assigns the meaning. */
    USER_DEFINED(0xF),
}

/**
 * Level-2 content genre of an ETSI EN 300 468 content descriptor (Table 28).
 *
 * [code] is the full descriptor byte, whose high nibble is [category]'s code. The "general" rows
 * (low nibble `0x0`) and "user defined" rows (low nibble `0xF`) are not subgenres, except in
 * [ContentCategory.SPECIAL_CHARACTERISTICS], where Table 28 assigns `0xB0` to
 * [ORIGINAL_LANGUAGE].
 */
public enum class ContentSubgenre(public val category: ContentCategory, public val code: Int) {
    /** Detective/thriller (`0x11`). */
    DETECTIVE_THRILLER(ContentCategory.MOVIE_DRAMA, 0x11),

    /** Adventure/western/war (`0x12`). */
    ADVENTURE_WESTERN_WAR(ContentCategory.MOVIE_DRAMA, 0x12),

    /** Science fiction/fantasy/horror (`0x13`). */
    SCIENCE_FICTION_FANTASY_HORROR(ContentCategory.MOVIE_DRAMA, 0x13),

    /** Comedy (`0x14`). */
    COMEDY(ContentCategory.MOVIE_DRAMA, 0x14),

    /** Soap/melodrama/folkloric (`0x15`). */
    SOAP_MELODRAMA_FOLKLORIC(ContentCategory.MOVIE_DRAMA, 0x15),

    /** Romance (`0x16`). */
    ROMANCE(ContentCategory.MOVIE_DRAMA, 0x16),

    /** Serious/classical/religious/historical movie/drama (`0x17`). */
    SERIOUS_CLASSICAL_RELIGIOUS_HISTORICAL_MOVIE_DRAMA(ContentCategory.MOVIE_DRAMA, 0x17),

    /** Adult movie/drama (`0x18`). */
    ADULT_MOVIE_DRAMA(ContentCategory.MOVIE_DRAMA, 0x18),

    /** News/weather report (`0x21`). */
    NEWS_WEATHER_REPORT(ContentCategory.NEWS_CURRENT_AFFAIRS, 0x21),

    /** News magazine (`0x22`). */
    NEWS_MAGAZINE(ContentCategory.NEWS_CURRENT_AFFAIRS, 0x22),

    /** Documentary (`0x23`). */
    DOCUMENTARY(ContentCategory.NEWS_CURRENT_AFFAIRS, 0x23),

    /** Discussion/interview/debate (`0x24`). */
    DISCUSSION_INTERVIEW_DEBATE(ContentCategory.NEWS_CURRENT_AFFAIRS, 0x24),

    /** Game show/quiz/contest (`0x31`). */
    GAME_SHOW_QUIZ_CONTEST(ContentCategory.SHOW_GAME_SHOW, 0x31),

    /** Variety show (`0x32`). */
    VARIETY_SHOW(ContentCategory.SHOW_GAME_SHOW, 0x32),

    /** Talk show (`0x33`). */
    TALK_SHOW(ContentCategory.SHOW_GAME_SHOW, 0x33),

    /** Special events (Olympic Games, World Cup, etc.) (`0x41`). */
    SPECIAL_EVENTS(ContentCategory.SPORTS, 0x41),

    /** Sports magazines (`0x42`). */
    SPORTS_MAGAZINES(ContentCategory.SPORTS, 0x42),

    /** Football/soccer (`0x43`). */
    FOOTBALL_SOCCER(ContentCategory.SPORTS, 0x43),

    /** Tennis/squash (`0x44`). */
    TENNIS_SQUASH(ContentCategory.SPORTS, 0x44),

    /** Team sports (excluding football) (`0x45`). */
    TEAM_SPORTS_EXCLUDING_FOOTBALL(ContentCategory.SPORTS, 0x45),

    /** Athletics (`0x46`). */
    ATHLETICS(ContentCategory.SPORTS, 0x46),

    /** Motor sport (`0x47`). */
    MOTOR_SPORT(ContentCategory.SPORTS, 0x47),

    /** Water sport (`0x48`). */
    WATER_SPORT(ContentCategory.SPORTS, 0x48),

    /** Winter sports (`0x49`). */
    WINTER_SPORTS(ContentCategory.SPORTS, 0x49),

    /** Equestrian (`0x4A`). */
    EQUESTRIAN(ContentCategory.SPORTS, 0x4A),

    /** Martial sports (`0x4B`). */
    MARTIAL_SPORTS(ContentCategory.SPORTS, 0x4B),

    /** Pre-school children's programmes (`0x51`). */
    PRE_SCHOOL_CHILDRENS_PROGRAMMES(ContentCategory.CHILDREN_YOUTH, 0x51),

    /** Entertainment programmes for 6 to 14 (`0x52`). */
    ENTERTAINMENT_PROGRAMMES_FOR_6_TO_14(ContentCategory.CHILDREN_YOUTH, 0x52),

    /** Entertainment programmes for 10 to 16 (`0x53`). */
    ENTERTAINMENT_PROGRAMMES_FOR_10_TO_16(ContentCategory.CHILDREN_YOUTH, 0x53),

    /** Informational/educational/school programmes (`0x54`). */
    INFORMATIONAL_EDUCATIONAL_SCHOOL_PROGRAMMES(ContentCategory.CHILDREN_YOUTH, 0x54),

    /** Cartoons/puppets (`0x55`). */
    CARTOONS_PUPPETS(ContentCategory.CHILDREN_YOUTH, 0x55),

    /** Rock/pop (`0x61`). */
    ROCK_POP(ContentCategory.MUSIC_BALLET_DANCE, 0x61),

    /** Serious music/classical music (`0x62`). */
    SERIOUS_MUSIC_CLASSICAL_MUSIC(ContentCategory.MUSIC_BALLET_DANCE, 0x62),

    /** Folk/traditional music (`0x63`). */
    FOLK_TRADITIONAL_MUSIC(ContentCategory.MUSIC_BALLET_DANCE, 0x63),

    /** Jazz (`0x64`). */
    JAZZ(ContentCategory.MUSIC_BALLET_DANCE, 0x64),

    /** Musical/opera (`0x65`). */
    MUSICAL_OPERA(ContentCategory.MUSIC_BALLET_DANCE, 0x65),

    /** Ballet (`0x66`). */
    BALLET(ContentCategory.MUSIC_BALLET_DANCE, 0x66),

    /** Performing arts (`0x71`). */
    PERFORMING_ARTS(ContentCategory.ARTS_CULTURE, 0x71),

    /** Fine arts (`0x72`). */
    FINE_ARTS(ContentCategory.ARTS_CULTURE, 0x72),

    /** Religion (`0x73`). */
    RELIGION(ContentCategory.ARTS_CULTURE, 0x73),

    /** Popular culture/traditional arts (`0x74`). */
    POPULAR_CULTURE_TRADITIONAL_ARTS(ContentCategory.ARTS_CULTURE, 0x74),

    /** Literature (`0x75`). */
    LITERATURE(ContentCategory.ARTS_CULTURE, 0x75),

    /** Film/cinema (`0x76`). */
    FILM_CINEMA(ContentCategory.ARTS_CULTURE, 0x76),

    /** Experimental film/video (`0x77`). */
    EXPERIMENTAL_FILM_VIDEO(ContentCategory.ARTS_CULTURE, 0x77),

    /** Broadcasting/press (`0x78`). */
    BROADCASTING_PRESS(ContentCategory.ARTS_CULTURE, 0x78),

    /** New media (`0x79`). */
    NEW_MEDIA(ContentCategory.ARTS_CULTURE, 0x79),

    /** Arts/culture magazines (`0x7A`). */
    ARTS_CULTURE_MAGAZINES(ContentCategory.ARTS_CULTURE, 0x7A),

    /** Fashion (`0x7B`). */
    FASHION(ContentCategory.ARTS_CULTURE, 0x7B),

    /** Magazines/reports/documentary (`0x81`). */
    MAGAZINES_REPORTS_DOCUMENTARY(ContentCategory.SOCIAL_POLITICAL_ECONOMICS, 0x81),

    /** Economics/social advisory (`0x82`). */
    ECONOMICS_SOCIAL_ADVISORY(ContentCategory.SOCIAL_POLITICAL_ECONOMICS, 0x82),

    /** Remarkable people (`0x83`). */
    REMARKABLE_PEOPLE(ContentCategory.SOCIAL_POLITICAL_ECONOMICS, 0x83),

    /** Nature/animals/environment (`0x91`). */
    NATURE_ANIMALS_ENVIRONMENT(ContentCategory.EDUCATION_SCIENCE_FACTUAL, 0x91),

    /** Technology/natural sciences (`0x92`). */
    TECHNOLOGY_NATURAL_SCIENCES(ContentCategory.EDUCATION_SCIENCE_FACTUAL, 0x92),

    /** Medicine/physiology/psychology (`0x93`). */
    MEDICINE_PHYSIOLOGY_PSYCHOLOGY(ContentCategory.EDUCATION_SCIENCE_FACTUAL, 0x93),

    /** Foreign countries/expeditions (`0x94`). */
    FOREIGN_COUNTRIES_EXPEDITIONS(ContentCategory.EDUCATION_SCIENCE_FACTUAL, 0x94),

    /** Social/spiritual sciences (`0x95`). */
    SOCIAL_SPIRITUAL_SCIENCES(ContentCategory.EDUCATION_SCIENCE_FACTUAL, 0x95),

    /** Further education (`0x96`). */
    FURTHER_EDUCATION(ContentCategory.EDUCATION_SCIENCE_FACTUAL, 0x96),

    /** Languages (`0x97`). */
    LANGUAGES(ContentCategory.EDUCATION_SCIENCE_FACTUAL, 0x97),

    /** Tourism/travel (`0xA1`). */
    TOURISM_TRAVEL(ContentCategory.LEISURE_HOBBIES, 0xA1),

    /** Handicraft (`0xA2`). */
    HANDICRAFT(ContentCategory.LEISURE_HOBBIES, 0xA2),

    /** Motoring (`0xA3`). */
    MOTORING(ContentCategory.LEISURE_HOBBIES, 0xA3),

    /** Fitness and health (`0xA4`). */
    FITNESS_AND_HEALTH(ContentCategory.LEISURE_HOBBIES, 0xA4),

    /** Cooking (`0xA5`). */
    COOKING(ContentCategory.LEISURE_HOBBIES, 0xA5),

    /** Advertisement/shopping (`0xA6`). */
    ADVERTISEMENT_SHOPPING(ContentCategory.LEISURE_HOBBIES, 0xA6),

    /** Gardening (`0xA7`). */
    GARDENING(ContentCategory.LEISURE_HOBBIES, 0xA7),

    /** Original language (`0xB0`). */
    ORIGINAL_LANGUAGE(ContentCategory.SPECIAL_CHARACTERISTICS, 0xB0),

    /** Black and white (`0xB1`). */
    BLACK_AND_WHITE(ContentCategory.SPECIAL_CHARACTERISTICS, 0xB1),

    /** Unpublished (`0xB2`). */
    UNPUBLISHED(ContentCategory.SPECIAL_CHARACTERISTICS, 0xB2),

    /** Live broadcast (`0xB3`). */
    LIVE_BROADCAST(ContentCategory.SPECIAL_CHARACTERISTICS, 0xB3),

    /** Plano-stereoscopic (`0xB4`). */
    PLANO_STEREOSCOPIC(ContentCategory.SPECIAL_CHARACTERISTICS, 0xB4),

    /** Local or regional (`0xB5`). */
    LOCAL_OR_REGIONAL(ContentCategory.SPECIAL_CHARACTERISTICS, 0xB5),
}

/**
 * ETSI EN 300 468 content classification decoded from a raw TVHeadend `contentType`.
 *
 * [subgenre] is `null` for a class's "general" or "user defined" byte and for level-2 values that
 * Table 28 leaves unassigned; [category] is still known in those cases. The raw byte remains
 * available as [EpgEvent.contentType] or [DvrEntry.contentType].
 */
@ConsistentCopyVisibility
public data class ContentGenre private constructor(
    public val category: ContentCategory,
    public val subgenre: ContentSubgenre?,
) {
    public companion object {
        /**
         * Decodes a raw TVHeadend content type, or returns `null` when it is absent, outside one
         * descriptor byte, or uses the undefined (`0x0`) or reserved (`0xC`–`0xE`) class nibble.
         */
        @JvmStatic
        public fun fromContentType(contentType: Long?): ContentGenre? {
            if (contentType == null || contentType !in 0L..CONTENT_TYPE_MAX) return null
            val code = contentType.toInt()
            val category = ContentCategory.entries.firstOrNull { it.code == code ushr LEVEL_ONE_SHIFT }
                ?: return null
            return ContentGenre(category, ContentSubgenre.entries.firstOrNull { it.code == code })
        }

        private const val CONTENT_TYPE_MAX: Long = 0xFF
        private const val LEVEL_ONE_SHIFT: Int = 4
    }
}
