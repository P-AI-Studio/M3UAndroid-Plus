import com.m3u.smartphone.ui.business.advanced.StreamDiagnostics
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

fun main() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/media") { exchange ->
        exchange.responseHeaders.add("Content-Type", "video/mp2t")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.sendResponseHeaders(206, 1)
        exchange.responseBody.use { it.write(byteArrayOf(0x47)) }
    }
    server.start()
    try {
        val port = server.address.port
        val ok = StreamDiagnostics.probe("http://name:secret@127.0.0.1:$port/media?token=private")
        check(ok.statusCode == 206)
        check(ok.firstByteReceived)
        check(ok.rangeSupported)
        check(!ok.endpoint.contains("secret") && !ok.endpoint.contains("private"))
        val bad = StreamDiagnostics.probe("ftp://127.0.0.1/file")
        check(bad.error?.contains("HTTP") == true)
        println("StreamDiagnostics smoke tests passed")
    } finally {
        server.stop(0)
    }
}
