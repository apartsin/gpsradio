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
    /** Three sights nearby instead of one (for "next story" flows). */
    @Volatile var manyPlaces = false
    /** OpenAI answers "insufficient_quota" to everything, as when the credit has run out. */
    @Volatile var quotaExhausted = false

    private val places = listOf(
        Triple(101L, "Schloss Ort", "Schloss Ort is a castle in Gmunden on Lake Traun, built on an island and linked to the shore by a long wooden bridge. It dates back to the 11th century."),
        Triple(102L, "Kammerhof Museum", "The Kammerhof Museum in Gmunden shows the history of salt trading on Lake Traun. It is housed in the former salt office."),
        Triple(103L, "Gmunden Town Hall", "The town hall of Gmunden is known for its ceramic carillon. Its bells are made of the local Gmunden pottery."),
    )
    private val quotaBody = """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","param":null,"code":"insufficient_quota"}}"""

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        requests += path
        val body = request.body.readUtf8()
        if (quotaExhausted && path.startsWith("/openai/")) {
            return MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setBody(quotaBody)
        }
        if (manyPlaces) {
            when {
                path.contains("list=geosearch") -> return json("""{"query":{"geosearch":[""" + places.mapIndexed { i, (id, title, _) ->
                    """{"pageid":$id,"title":"$title","lat":${47.9190 + i * 0.0012},"lon":${13.7995 + i * 0.0010},"dist":${120 + i * 150}}"""
                }.joinToString(",") + "]}}")
                path.startsWith("/wiki/") -> return json("""{"query":{"pages":[""" + places.filter { (id, _, _) -> path.contains("$id") }
                    .ifEmpty { places }.joinToString(",") { (id, title, extract) ->
                        """{"pageid":$id,"title":"$title","extract":"$extract","description":"sight in Gmunden","pageprops":{"wikibase_item":"Q$id"}}"""
                    } + "]}}")
                path.endsWith("/responses") && body.contains("radio_story") -> {
                    val name = places.firstOrNull { (_, title, _) -> body.contains(title) }?.second ?: "Gmunden"
                    return json(responses("""{\"text\":\"FAKE-STORY about $name.\",\"basis\":\"documented\"}"""))
                }
            }
        }
        return when {
            path.startsWith("/overpass") -> json("""{"elements":[
                {"type":"node","id":7,"lat":47.9195,"lon":13.8000,"tags":{"name":"Kalvarienberg","natural":"peak","ele":"610"}}]}""")
            path.contains("list=geosearch") -> json("""{"query":{"geosearch":[
                {"pageid":101,"title":"Schloss Ort","lat":47.9190,"lon":13.7995,"dist":120}]}}""")
            path.startsWith("/wiki/") -> json("""{"query":{"pages":[{"pageid":101,"title":"Schloss Ort",
                "extract":"Schloss Ort is a castle in Gmunden on Lake Traun, built on an island and linked to the shore by a long wooden bridge. It dates back to the 11th century and became famous as the setting of a television series.",
                "description":"castle in Upper Austria","pageprops":{"wikibase_item":"Q1"}}]}}""")
            // Structured narration: the story plus its claim basis.
            path.endsWith("/responses") && body.contains("radio_story") -> json(responses(
                """{\"text\":\"FAKE-STORY: Schloss Ort sits on its own island, about a hundred metres ahead.\",\"basis\":\"documented\"}""",
            ))
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
