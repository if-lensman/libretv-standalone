package family.libretv.tv

import androidx.test.platform.app.InstrumentationRegistry
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.CastOptions
import junit.framework.TestCase
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Real UPnPCast public API against local SSDP + SOAP fixture; not a real TV acceptance test. */
class DlnaProtocolTest : TestCase() {
    fun testDiscoveryAndPlaybackControl() = runBlocking {
        val fixture = Renderer()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DLNACast.init(context)
        try {
            val advertisement = launch(Dispatchers.IO) {
                delay(300)
                DatagramSocket().use { socket ->
                    repeat(12) {
                        val msg = "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:${fixture.port}/description.xml\r\nUSN: uuid:libretv-fixture::urn:schemas-upnp-org:device:MediaRenderer:1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n".toByteArray()
                        socket.send(DatagramPacket(msg,msg.size,InetAddress.getByName("127.0.0.1"),1900)); delay(200)
                    }
                }
            }
            val devices = DLNACast.search(3500); advertisement.join()
            val device = devices.firstOrNull { it.name == "LibreTV protocol fixture" }
            assertNotNull("SSDP fixture was not discovered", device)
            val url = "https://media.example/master.m3u8?token=synthetic&x=2"
            assertTrue(DLNACast.castToDevice(device!!,url,"Fixture & title",CastOptions(mimeType="application/vnd.apple.mpegurl")))
            assertTrue(fixture.bodies.any { it.contains("SetAVTransportURI") && it.contains("token=synthetic&amp;x=2") })
            assertEquals(DLNACast.PlaybackState.PLAYING,DLNACast.getPlaybackState())
            val beforeReconnect=fixture.bodies.size
            assertTrue(DLNACast.reconnect(device,url))
            assertFalse(DLNACast.reconnect(device,"https://media.example/other.m3u8"))
            assertEquals(url,DLNACast.reconnectQueue(device,listOf("https://media.example/other.m3u8",url)))
            assertFalse(fixture.bodies.drop(beforeReconnect).any { it.contains("SetAVTransportURI") || it.contains("<u:Play ") })
            assertTrue(DLNACast.pause())
            assertEquals(DLNACast.PlaybackState.PAUSED,DLNACast.getPlaybackState())
            assertEquals(10000L,DLNACast.getProgressRealtime()!!.first)
            assertTrue(DLNACast.seek(20000))
            assertTrue(fixture.bodies.any { it.contains("00:00:20") })
            assertTrue(DLNACast.setVolume(35))
            assertTrue(DLNACast.stop())
            assertEquals(DLNACast.PlaybackState.STOPPED,DLNACast.getPlaybackState())
            assertFalse("NAS cookie must not be sent to a renderer",fixture.headers.any { it.contains("Cookie:",true) })
        } finally { DLNACast.cleanup(); fixture.close() }
    }
    fun testMultipleReceiversWithoutBrandFilter() = runBlocking {
        val tv = Renderer("Fixture Xiaomi", "Xiaomi", "TV")
        val box = Renderer("Fixture TVFruit", "iQIYI", "A11")
        val server = Renderer("Fixture NAS", "NAS", "Storage", false)
        val fixtures = listOf(tv, box, server)
        try {
            val candidates = fixtures.map {
                DLNACast.Device("http://127.0.0.1:${it.port}/description.xml", it.name, "127.0.0.1", it.manufacturer == "Xiaomi")
            }
            assertFalse("TVFruit-style metadata must reproduce old brand filter failure", candidates.single { it.name == box.name }.isTV)
            val receivers = ReceiverDiscovery.receivers(candidates)
            assertEquals(setOf(tv.name, box.name), receivers.map { it.name }.toSet())
            assertEquals(2, receivers.size)
            assertTrue(ReceiverDiscovery.hasPlaybackService("<u:root xmlns:u='urn:schemas-upnp-org:device-1-0'><u:serviceType>urn:schemas-upnp-org:service:AVTransport:2</u:serviceType></u:root>"))
            assertFalse(ReceiverDiscovery.hasPlaybackService("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root/>"))
            assertFalse(ReceiverDiscovery.hasPlaybackService("<root><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"))
        } finally { fixtures.forEach { it.close() } }
    }
    internal class Renderer(val name: String = "LibreTV protocol fixture", val manufacturer: String = "LibreTV test", val model: String = "Fixture", val playback: Boolean = true) {
        private val server=ServerSocket(0,20,InetAddress.getByName("127.0.0.1"))
        val port=server.localPort
        val bodies=CopyOnWriteArrayList<String>()
        val headers=CopyOnWriteArrayList<String>()
        @Volatile private var state="STOPPED"
        @Volatile private var mediaUri=""
        @Volatile var rejectMedia=false
        @Volatile var rejectUriFragment: String? = null
        fun simulateReceiverAdvance(uri: String) { mediaUri = uri }
        fun advertise() = thread(isDaemon=true, name="DLNA-advertise") {
            DatagramSocket().use { socket ->
                while(!server.isClosed) {
                    val msg="HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:$port/description.xml\r\nUSN: uuid:fixture-$port\r\nST: upnp:rootdevice\r\n\r\n".toByteArray()
                    socket.send(DatagramPacket(msg,msg.size,InetAddress.getByName("127.0.0.1"),1900))
                    Thread.sleep(250)
                }
            }
        }
        private val worker=thread(isDaemon=true,name="DLNA-fixture") {
            while(!server.isClosed) {
                try { server.accept().use { socket ->
                    socket.soTimeout=5000
                    val input=socket.getInputStream()
                    val header=java.io.ByteArrayOutputStream()
                    while(true) {
                        val next=input.read(); if(next<0)return@use
                        header.write(next)
                        if(header.size()>65536)throw java.io.IOException("fixture header too large")
                        if(header.toString("UTF-8").endsWith("\r\n\r\n"))break
                    }
                    val lines=header.toString("UTF-8").split("\r\n")
                    val first=lines.first()
                    headers.add(lines.joinToString("\n"))
                    val count=lines.firstOrNull { it.startsWith("Content-Length:",true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val requestBytes=ByteArray(count); var at=0
                    while(at<count) { val n=input.read(requestBytes,at,count-at); if(n<0)break; at+=n }
                    val body=String(requestBytes,0,at,Charsets.UTF_8); bodies.add(body)
                    if(body.contains("SetAVTransportURI") && (rejectMedia || rejectUriFragment?.let { body.contains(it) } == true)) {
                        socket.getOutputStream().write("HTTP/1.1 500 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return@use
                    }
                    val response=if(first.startsWith("GET")) description() else {
                        val action=lines.first { it.startsWith("SOAPAction:",true) }.substringAfter('#').trim().trim('"')
                        if(action=="SetAVTransportURI") {
                            val parser=android.util.Xml.newPullParser(); parser.setInput(java.io.StringReader(body))
                            while(parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                                if(parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name=="CurrentURI") mediaUri=parser.nextText()
                                parser.next()
                            }
                        }
                        when(action) { "Play" -> state="PLAYING"; "Pause" -> state="PAUSED_PLAYBACK"; "Stop" -> state="STOPPED" }
                        val data=when(action) {
                            "GetMediaInfo" -> "<CurrentURI>${mediaUri.replace("&","&amp;").replace("<","&lt;")}</CurrentURI>"
                            "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
                            "GetPositionInfo" -> "<Track>1</Track><TrackDuration>00:01:00</TrackDuration><RelTime>00:00:10</RelTime><AbsTime>00:00:10</AbsTime>"
                            "GetVolume" -> "<CurrentVolume>30</CurrentVolume>"
                            "GetMute" -> "<CurrentMute>0</CurrentMute>"
                            else -> ""
                        }
                        val service=if(action.contains("Volume") || action.contains("Mute")) "RenderingControl" else "AVTransport"
                        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$data</u:${action}Response></s:Body></s:Envelope>"""
                    }
                    val bytes=response.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(bytes)
                } } catch(e: Exception) { if(!server.isClosed) throw e }
            }
        }
        private fun description() = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><URLBase>http://127.0.0.1:$port/</URLBase><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>$name</friendlyName><manufacturer>$manufacturer</manufacturer><modelName>$model</modelName><UDN>uuid:libretv-fixture</UDN><serviceList>${if(playback) service("AVTransport") + service("RenderingControl") else service("ContentDirectory")}</serviceList></device></root>"""
        private fun service(name:String) = """<service><serviceType>urn:schemas-upnp-org:service:$name:1</serviceType><serviceId>urn:upnp-org:serviceId:$name</serviceId><SCPDURL>/$name.xml</SCPDURL><controlURL>/$name/control</controlURL><eventSubURL>/$name/event</eventSubURL></service>"""
        fun close() { server.close(); worker.join(2000) }
    }
}
