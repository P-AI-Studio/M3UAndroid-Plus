import com.m3u.smartphone.ui.business.advanced.BulkM3uBuilder

fun main() {
    val urls = (1..20).joinToString("\n") { "https://example.test/live/$it.m3u8" }
    val parsed = BulkM3uBuilder.parse(urls)
    check(parsed.error == null)
    check(parsed.urls.size == 20)
    val m3u = BulkM3uBuilder.toM3u(parsed.urls)
    check(m3u.startsWith("#EXTM3U\n"))
    check(m3u.contains("#EXTINF:-1,1\nhttps://example.test/live/1.m3u8"))
    check(m3u.contains("#EXTINF:-1,20\nhttps://example.test/live/20.m3u8"))
    val mixed = BulkM3uBuilder.parse("https://one.test/a\nhttps://one.test/a\nnot-a-url\nhttps://two.test/b")
    check(mixed.urls.size == 2 && mixed.duplicateCount == 1 && mixed.invalidCount == 1)
    val tooMany = (1..101).joinToString("\n") { "https://example.test/$it" }
    check(BulkM3uBuilder.parse(tooMany).error != null)
    println("BulkM3uBuilder smoke tests passed")
}
