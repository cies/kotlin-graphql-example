package dropnext.dss.testutil.fake

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


/**
 * An upstream whose first [failFirstConnections] TCP connections are accepted and then reset without
 * an answer, after which it serves [body] normally.
 *
 * A raw socket rather than a Ktor server because the failure under test happens below HTTP: this is
 * what a dropped connection or a restarting upstream looks like to the client. It also replaces the
 * "bind a port, close it, and hope nothing else takes it" trick, which races against any other test
 * binding port 0 at the same moment.
 */
class FakeFlakyServer(
  private val failFirstConnections: Int = Int.MAX_VALUE,
  private val body: String = "{}",
) : AutoCloseable {

  private val serverSocket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
  private val connections = AtomicInteger(0)
  private val accepting = AtomicBoolean(true)

  private val acceptThread = Thread(::acceptLoop, "fake-flaky-server").apply {
    isDaemon = true
    start()
  }

  val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}"

  /** How many times a client reached this server, whether or not it got an answer. */
  val connectionCount: Int get() = connections.get()

  private fun acceptLoop() {
    while (accepting.get()) {
      val socket = try {
        serverSocket.accept()
      } catch (_: IOException) {
        return // Closed while we were waiting, which is how this server stops.
      }
      if (connections.incrementAndGet() <= failFirstConnections) {
        // A zero linger makes close() send RST, so the client sees a reset rather than a clean EOF.
        runCatching { socket.setSoLinger(true, 0) }
        runCatching { socket.close() }
      } else {
        runCatching { socket.use(::serve) }
      }
    }
  }

  private fun serve(socket: Socket) {
    val reader = socket.getInputStream().bufferedReader()
    var contentLength = 0
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isEmpty()) break
      if (line.startsWith("Content-Length:", ignoreCase = true)) {
        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
      }
    }
    // Drain the body, or the client may see the reply as an error on a half-written request.
    if (contentLength > 0) reader.read(CharArray(contentLength), 0, contentLength)

    val bytes = body.toByteArray()
    val head = "HTTP/1.1 200 OK\r\n" +
      "Content-Type: application/json\r\n" +
      "Content-Length: ${bytes.size}\r\n" +
      "Connection: close\r\n\r\n"
    socket.getOutputStream().apply {
      write(head.toByteArray())
      write(bytes)
      flush()
    }
  }

  override fun close() {
    accepting.set(false)
    runCatching { serverSocket.close() }
    acceptThread.interrupt()
  }
}
