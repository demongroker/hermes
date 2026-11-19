package app.hermes.updater

/** Dotted numeric version comparison, shared by the daily worker and the UI. */
object Versions {

  /** Anything unparseable counts as "not newer" — fail closed on weird tags. */
  fun isNewer(latest: String, installed: String): Boolean {
    val a = latest.split('.').mapNotNull { it.toIntOrNull() }
    val b = installed.split('.').mapNotNull { it.toIntOrNull() }
    if (a.isEmpty() || b.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
      val x = a.getOrElse(i) { 0 }
      val y = b.getOrElse(i) { 0 }
      if (x != y) return x > y
    }
    return false
  }
}
