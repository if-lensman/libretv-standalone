package family.libretv.tv

import android.util.Log
import android.util.Xml
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI

/** isTV is a brand heuristic in UPnPCast, not a renderer capability flag. */
internal object ReceiverDiscovery {
    private const val TAG = "LibreTVDiscovery"
    private const val MAX_XML_BYTES = 262144

    suspend fun receivers(devices: List<DLNACast.Device>): List<DLNACast.Device> = coroutineScope {
        val permits = Semaphore(6)
        devices.distinctBy { it.id }.map { device ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    val receiver = supportsPlayback(device.id)
                    Log.i(TAG, "device=${device.name} address=${device.address} brandTV=${device.isTV} receiver=$receiver")
                    device.takeIf { receiver }
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.name }
    }

    private fun supportsPlayback(location: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val uri = URI(location)
            if (uri.scheme !in listOf("http", "https") || uri.userInfo != null) return false
            connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) return false
            val bytes = connection.inputStream.use { it.readBytesBounded() }
            hasPlaybackService(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_XML_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun hasPlaybackService(xml: String): Boolean {
        // Never resolve entities from a network device description.
        if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) return false
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", false)
            parser.setInput(StringReader(xml))
            var found = false
            var rootClosed = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.END_TAG && parser.depth == 1) rootClosed = true
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "serviceType") {
                    if (parser.nextText().trim().matches(Regex("urn:schemas-upnp-org:service:AVTransport:[0-9]+", RegexOption.IGNORE_CASE))) found = true
                }
                parser.next()
            }
            found && rootClosed
        } catch (e: Exception) { false }
    }
}
