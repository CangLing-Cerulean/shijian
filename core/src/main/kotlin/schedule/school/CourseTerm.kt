package schedule.school

fun SchoolCourse.belongsTo(term: AcademicTerm): Boolean {
    if (academicYear.trim() != term.year) return false
    val season = semester.removePrefix(term.year).trim()
    return season in if (term.half == 1) setOf("秋", "冬", "秋冬") else setOf("春", "夏", "春夏")
}
