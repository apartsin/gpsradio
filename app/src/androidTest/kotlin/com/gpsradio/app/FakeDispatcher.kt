package com.gpsradio.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * Canned responses shaped like the real APIs. Coordinates are around Gmunden, Austria
 * (47.918, 13.799), where the test "stands".
 */
class FakeDispatcher : Dispatcher() {
    val requests = java.util.Collections.synchronizedList(mutableListOf<String>())

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        requests += path
        val body = request.body.readUtf8()
        return when {
            path.startsWith("/overpass") -> json("""{"elements":[
                {"type":"node","id":7,"lat":47.9195,"lon":13.8000,"tags":{"name":"Kalvarienberg","natural":"peak","ele":"610"}}]}""")
            path.contains("list=geosearch") -> json("""{"query":{"geosearch":[
                {"pageid":101,"title":"Schloss Ort","lat":47.9190,"lon":13.7995,"dist":120}]}}""")
            path.startsWith("/wiki/") -> json("""{"query":{"pages":[{"pageid":101,"title":"Schloss Ort",
                "extract":"Schloss Ort is a castle in Gmunden on Lake Traun, built on an island and linked to the shore by a long wooden bridge. It dates back to the 11th century and became famous as the setting of a television series.",
                "description":"castle in Upper Austria","pageprops":{"wikibase_item":"Q1"}}]}}""")
            path.endsWith("/responses") && body.contains("json_schema") -> json(responses(
                """{\"reply\":\"FAKE-ANSWER: the bridge is about 130 metres long.\",\"action\":\"none\",\"language\":null,\"persist_language\":false,\"theme\":null,\"entity_id\":\"wiki:en:101\",\"remember\":[{\"category\":\"style\",\"text\":\"Keep stories short\",\"topic\":null}],\"forget\":[]}""",
            ))
            path.endsWith("/responses") -> json(responses("FAKE-STORY: Schloss Ort sits on its own island, about a hundred metres ahead."))
            path.endsWith("/audio/speech") -> MockResponse().setBody("AUDIO:" + body.take(60))
            path.endsWith("/audio/transcriptions") -> json("""{"text":"Tell me more"}""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)

    private fun responses(text: String) =
        """{"id":"resp_1","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$text","annotations":[]}]}]}"""
}
