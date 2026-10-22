# UPnPCast pinned source

Upstream: https://github.com/yinnho/UPnPCast/tree/v1.3.0
Commit: 362ec62601c02c7c931ba55bdde98491990ab9d0
License: MIT (LICENSE retained, also included in app assets).

Only upstream app/src/main Kotlin sources and Manifest are vendored. Local Gradle uses the existing SDK35/JDK17/coroutines1.8.1 baseline and nanohttpd2.3.1. Upstream demo UI dependencies are unnecessary for this library source.

Local patch 2026-10-21: UpnpHttp.soapEnvelope trims leading whitespace after interpolation. A multiline SOAP body previously makes trimIndent leave whitespace before the XML declaration. On the authorized TVFruit4K, the original GetTransportInfo returned HTTP500/UPnP402 Invalid Args; the same envelope with leading whitespace removed returned HTTP200. All SOAP actions share this builder. Add focused XML envelope regression tests. Also log HTTP/UPnP error codes and exception classes without media URLs, cookies or SOAP bodies.

Do not edit Gradle cached AARs. Updates must retain provenance and rerun protocol fixtures and actual receiver acceptance.

Local extension 0.8.0: DLNACast.reconnect(device, expectedUri) attaches controls only when GetMediaInfo.CurrentURI still matches the saved queue. It sends neither SetAVTransportURI nor Play. XML CurrentURI parsing decodes entities. This permits phone Activity/process restart without intentionally restarting the receiver. Protocol fixtures cover matching/mismatched URLs and absence of playback mutations.

Local extension 0.8.1: reconnectQueue accepts a saved series URL set and returns the matching CurrentURI, because a receiver-owned queue may advance while the phone is away. It still performs read-only matching and sends no SetURI/Play. Batch delivery reuses the original castToDevice call unchanged; native playlist behavior is device-specific and was reported by the user for their TVFruit4K.
