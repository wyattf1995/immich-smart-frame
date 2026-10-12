# Legacy Home Assistant TLS trust

FrameOS uses Android's normal TLS trust store for every Home Assistant request.
On API levels below 26 only, the app-scoped `UrlConnectionHomeAssistantTransport`
adds ISRG Root X1 as a fallback trust anchor after platform trust rejects a server
chain. The pinned certificate is embedded with its reviewed DER SHA-256:
`96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6`.

The fallback changes only the SSL socket factory assigned to that transport's
`HttpsURLConnection`. It does not modify global HTTPS defaults, hostname
verification, browser/Gecko trust, client-certificate trust, request URLs, or
authentication request behavior. A chain rejected by both stores still fails.
