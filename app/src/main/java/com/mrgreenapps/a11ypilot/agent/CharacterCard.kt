package com.mrgreenapps.a11ypilot.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A role/character card that can be selected as the agent persona.
 *
 * The card packages a role description with a per-card "phone use" capability flag:
 * when [allowPhoneUse] is true the model may operate the device (tap/swipe/send/etc.),
 * when false the phone tooling is withheld from the prompt entirely.
 *
 * [source] marks provenance: "native" for cards authored inside Metis, "tavern" for
 * cards imported from SillyTavern / NativeTavern (PNG `ccv3`/`chara` or JSON).
 * [rawJson] preserves the original card payload so future fields (world book, assets,
 * example dialogue) can be recovered without re-importing.
 */
@Serializable
data class CharacterCard(
    val id: String,
    val name: String,
    val description: String,
    val allowPhoneUse: Boolean = false,
    val allowedTools: List<String> = emptyList(),
    val source: String = "native",
    val rawJson: String = "",
    val avatarUri: String = ""
) {
    /** Human-facing summary of what this card allows. */
    fun capabilityLabel(): String =
        if (allowPhoneUse) "可操作手机" else "仅对话"

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(cards: List<CharacterCard>): String =
            json.encodeToString<List<CharacterCard>>(cards)

        fun decode(raw: String): List<CharacterCard> = runCatching {
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<CharacterCard>>(raw)
        }.getOrElse { emptyList() }
    }
}
