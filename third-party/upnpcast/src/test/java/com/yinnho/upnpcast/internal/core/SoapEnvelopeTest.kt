package com.yinnho.upnpcast.internal.core

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class SoapEnvelopeTest {
    @Test fun multilineCommandsHaveValidXmlDeclaration() {
        val bodies = listOf(
            "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n    <InstanceID>0</InstanceID>\n</u:GetTransportInfo>",
            "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n    <InstanceID>0</InstanceID>\n    <Speed>1</Speed>\n</u:Play>",
            "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n    <InstanceID>0</InstanceID>\n    <CurrentURI>https://media.example/v.m3u8?a=1&amp;b=2</CurrentURI>\n    <CurrentURIMetaData><![CDATA[<DIDL-Lite/>]]></CurrentURIMetaData>\n</u:SetAVTransportURI>"
        )
        for (body in bodies) {
            val xml = UpnpHttp.soapEnvelope(body)
            assertTrue("XML declaration must be the first bytes", xml.startsWith("<?xml"))
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
            assertEquals("Envelope", document.documentElement.localName)
            assertEquals("0", document.getElementsByTagName("InstanceID").item(0).textContent)
        }
    }
}
