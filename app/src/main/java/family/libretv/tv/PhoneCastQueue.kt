package family.libretv.tv

/** Snapshot of the series actually cast, independent of whichever series is being browsed. */
internal data class PhoneCastQueue(
    val title: String,
    val urls: List<String>,
    val index: Int,
    val poster: String = "",
    val itemJson: String = "{}",
    val sourceJson: String = "{}"
) {
    init { require(urls.isNotEmpty() && index in urls.indices) }
    fun adjacent(delta: Int): Int? = (index + delta).takeIf { it in urls.indices }
    fun at(newIndex: Int) = copy(index = newIndex)
    val label get() = "$title · 第 ${index + 1} 集"
}
