package com.example.virhuman.data

import org.json.JSONObject

data class CharacterResource(
    val id: String,
    val name: String,
    val introduce: String,
    val picture: String,
    val aiAppId: String,
    val speakerId: Int,
    val leisureUrl: String,
    val listeningUrl: String,
    val speakingUrl: String
)

data class ResourceResponse(
    val code: Int,
    val message: String,
    val characters: List<CharacterResource>
)

object ResourceParser {
    fun parseResponse(jsonText: String): ResourceResponse {
        val root = JSONObject(jsonText)
        val code = root.optInt("code", -1)
        val message = root.optString("message", "")
        val data = root.optJSONObject("data")
        val charactersArray = data?.optJSONArray("characters")

        val list = mutableListOf<CharacterResource>()
        if (charactersArray != null) {
            for (i in 0 until charactersArray.length()) {
                val item = charactersArray.optJSONObject(i) ?: continue
                val videos = item.optJSONObject("videos")
                list.add(
                    CharacterResource(
                        id = item.optString("id", "").trim(),
                        name = item.optString("name", "").trim(),
                        introduce = item.optString("introduce", "").trim(),
                        picture = item.optString("picture", "").trim(),
                        aiAppId = item.optString("aiAppId", "").trim(),
                        speakerId = item.optInt("speakerId", 0),
                        leisureUrl = videos?.optString("leisure", "").orEmpty().trim(),
                        listeningUrl = videos?.optString("listening", "").orEmpty().trim(),
                        speakingUrl = videos?.optString("speaking", "").orEmpty().trim()
                    )
                )
            }
        }

        return ResourceResponse(
            code = code,
            message = message,
            characters = list
        )
    }
}
