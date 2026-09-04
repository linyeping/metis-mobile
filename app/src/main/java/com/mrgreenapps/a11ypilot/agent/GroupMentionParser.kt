package com.mrgreenapps.a11ypilot.agent

/**
 * 群组智能体的 @提及解析。
 *
 * 参考 Grok-Bot 的 parseGroupMentions 机制：从用户指令里提取 `@名字`，
 * 支持 `@所有人` / `@all` / `@everyone` 表示群内全员。
 */
object GroupMentionParser {
    private val mentionRegex = Regex("""@([^\s@，。,.!?？、:：]+)""")
    private val everyoneAliases = setOf("所有人", "全部", "大家", "all", "everyone")

    data class Result(
        /** 被 @ 的成员（角色卡），去重保序。 */
        val mentioned: List<CharacterCard>,
        /** 是否为 @所有人（群内全员）。 */
        val everyone: Boolean,
        /** 移除 @ 前缀后的纯指令内容。 */
        val instruction: String
    )

    /**
     * 从指令里解析 @提及，并在 [cards]（群成员/全部角色卡）里匹配对应角色。
     *
     * @param instruction 用户原始指令
     * @param cards 可被 @ 的角色卡集合（通常是当前会话绑定的群成员，或全部角色卡）
     * @param defaultCard 未 @ 任何人时默认回应的角色（可为 null，表示不强制某人）
     */
    fun parse(instruction: String, cards: List<CharacterCard>, defaultCard: CharacterCard? = null): Result {
        val raw = mentionRegex.findAll(instruction).map { it.groupValues[1].trim() }.toList()
        val everyone = raw.any { it.lowercase() in everyoneAliases }

        val mentioned = if (everyone) {
            cards
        } else if (raw.isEmpty()) {
            defaultCard?.let { listOf(it) } ?: emptyList()
        } else {
            // 按 @ 出现顺序匹配角色名（支持精确名与「名字」前缀匹配）
            raw.mapNotNull { name ->
                cards.firstOrNull { it.name == name || it.name.startsWith(name) }
            }.distinctBy { it.id }
        }

        val cleaned = instruction.replace(mentionRegex, "").trim()

        return Result(mentioned, everyone, cleaned)
    }
}
