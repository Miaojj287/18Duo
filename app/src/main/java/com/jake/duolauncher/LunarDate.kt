package com.jake.duolauncher

/*
 * The Chinese lunisolar date line iOS appends to the Lock Screen's date when the 「中国农历」
 * alternate calendar is on (the owner's iPhone, round 12: "10月21日周三 · 丙午年九月十二"). Pure
 * text from the calendar's fields; the fields themselves come from ICU's ChineseCalendar on Android
 * ([lunarDateOf]). Names follow ICU's zh data (正月 … 冬月、腊月; 初一 … 三十), which is what iOS shows.
 */
internal object LunarDate {
    private const val STEMS = "甲乙丙丁戊己庚辛壬癸"
    private const val BRANCHES = "子丑寅卯辰巳午未申酉戌亥"
    private val MONTHS = listOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")
    private val DAYS = listOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    /**
     * @param cyclicYear the year within the sexagenary cycle, 1..60 (ICU `Calendar.YEAR` of a ChineseCalendar; 1984 = 甲子 = 1).
     * @param month 1..12 (ICU `MONTH` + 1), [leap] for an intercalary month (ICU `IS_LEAP_MONTH`).
     * @param day 1..30.
     */
    fun text(cyclicYear: Int, month: Int, leap: Boolean, day: Int): String? {
        if (cyclicYear !in 1..60 || month !in 1..12 || day !in 1..30) return null
        val stem = STEMS[(cyclicYear - 1) % 10]
        val branch = BRANCHES[(cyclicYear - 1) % 12]
        return "$stem$branch" + "年" + (if (leap) "闰" else "") + MONTHS[month - 1] + DAYS[day - 1]
    }

    /**
     * The two characters iOS stacks beside the date in the Calendar widgets (round 21, the owner's
     * iPhone Duo: "25 │ 十六 │ 周日"): the lunar day (初五, 十六, 廿三), or on the first day of a lunar
     * month that month's name (十月, 冬月) — the convention of iOS Calendar's lunar month grid. A leap
     * month's first day ("闰六月" does not fit two lines) keeps 初一.
     */
    fun dayLabel(month: Int, leap: Boolean, day: Int): String? {
        if (month !in 1..12 || day !in 1..30) return null
        return if (day == 1 && !leap) MONTHS[month - 1] else DAYS[day - 1]
    }
}
