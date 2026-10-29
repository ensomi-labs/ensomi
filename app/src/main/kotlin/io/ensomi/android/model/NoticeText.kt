package io.ensomi.android.model

/**
 * A licence or notice file as paragraphs that wrap to the screen. The files are hard-wrapped at about 80 columns; a
 * paragraph's lines are joined with spaces, so the text reflows at any width and font size. A line break is kept
 * where the file meant one: before a list item, and after a line much shorter than the file's wrap width (a heading,
 * an address, a copyright line).
 */
object NoticeText {
    private val BLANK_LINE = Regex("\n\\s*\n")
    private val LIST_ITEM = Regex("^([-*•+]|\\(?[0-9]{1,3}[.)]|\\(?[a-zA-Z][.)]|\\([ivxIVX]{1,4}\\))\\s")

    fun paragraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n")
        // The wrap width: nine lines in ten are no longer.
        val lengths = normalized.lineSequence().map { it.trim().length }.filter { it > 0 }.sorted().toList()
        val width = if (lengths.isEmpty()) 0 else lengths[lengths.size * 9 / 10]
        return normalized.split(BLANK_LINE).mapNotNull { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@mapNotNull null
            buildString {
                append(lines.first())
                for (i in 1 until lines.size) {
                    val keepBreak = LIST_ITEM.containsMatchIn(lines[i]) || lines[i - 1].length < width * 6 / 10
                    append(if (keepBreak) '\n' else ' ')
                    append(lines[i])
                }
            }
        }
    }
}
