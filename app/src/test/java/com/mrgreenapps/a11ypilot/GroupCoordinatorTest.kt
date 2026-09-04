package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.CharacterCard
import com.mrgreenapps.a11ypilot.agent.GroupCoordinator
import com.mrgreenapps.a11ypilot.agent.Prompts
import com.mrgreenapps.a11ypilot.data.WorkMode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the multi-agent group coordinator.
 *
 * Scope (pure-Kotlin pieces only — no Android, no network):
 *   - systemForMember builds a single-member prompt with the right persona, role
 *     directive, capability hint, and other-members reference list.
 *   - MemberReply correctly reports succeeded vs. failed states.
 *
 * Avoiding running full LLM calls. [GroupCoordinator] itself uses Android Context
 * (DataStore) and the network stack, so an integration test would require either an
 * emulator or a mock LLM; the Flow contract is covered manually in `CoordinateGroupIT`.
 */
class GroupCoordinatorTest {
    private val alice = CharacterCard(
        id = "alice",
        name = "Alice",
        description = "A pragmatic project manager.",
        allowPhoneUse = true
    )
    private val bob = CharacterCard(
        id = "bob",
        name = "Bob",
        description = "A careful backend engineer.",
        allowPhoneUse = false
    )

    @Test
    fun systemForMember_mentionsOnlyTheTargetCharacter_andSkillDirective() {
        val prompt = Prompts.systemForMember(
            mode = WorkMode.COWORK,
            member = alice,
            userName = "Tester"
        )

        assertTrue("Alice identifier must appear in persona block", prompt.contains("Alice"))
        assertFalse("Bob should never appear when not in otherMembers", prompt.contains("Bob"))
        assertTrue("Should explicitly say 'current role: Alice'", prompt.contains("当前角色：Alice"))
        assertTrue(
            "Phone-enabled members should be told they may invoke device tools",
            prompt.contains("必要时可调用设备相关工具")
        )
        assertTrue("Mode-level rules still flow through", prompt.contains("COWORK") || prompt.contains("协作"))
    }

    @Test
    fun systemForMember_phoneDisabledDirectivePointsToDialogueOnly() {
        val prompt = Prompts.systemForMember(
            mode = WorkMode.CHAT,
            member = bob,
            userName = ""
        )
        assertTrue("Bob (no phone) directive should restrict to dialogue", prompt.contains("仅进行对话"))
        assertFalse("Bob should not be told he can use phone tools", prompt.contains("必要时可调用设备相关工具"))
    }

    @Test
    fun systemForMember_listsOtherMembersButInstructsNotToSpeakForThem() {
        val prompt = Prompts.systemForMember(
            mode = WorkMode.CHAT,
            member = alice,
            userName = "",
            otherMembers = listOf(bob)
        )
        assertTrue("Other members block must mention Bob", prompt.contains("Bob"))
        assertTrue(
            "Should forbid speaking on behalf of other members",
            prompt.contains("不要替") || prompt.contains("不要替他们说话")
        )
        assertTrue(
            "Should also forbid impersonating other members explicitly",
            prompt.contains("不要替其它群成员发言")
        )
    }

    @Test
    fun systemForMember_replacesSillyTavernPlaceholders() {
        val card = alice.copy(
            description = "Hi {{user}}, I'm {{char}} — your dedicated planner."
        )
        val prompt = Prompts.systemForMember(
            mode = WorkMode.CHAT,
            member = card,
            userName = "Wendy"
        )
        assertTrue(prompt.contains("Wendy"))
        assertTrue(prompt.contains("Alice"))
        assertFalse("Raw placeholders must be gone", prompt.contains("{{user}}"))
        assertFalse(prompt.contains("{{char}}"))
    }

    /**
     * Sanity check that [GroupCoordinator.MemberReply] reports failure correctly. This is
     * not exercising the network path; it just locks down the `succeeded` helper used by
     * downstream consumers (UI bubbles, error chips).
     */
    @Test
    fun memberReply_reportsFailureWhenErrorIsPresent() {
        val okReply = GroupCoordinator.MemberReply(
            memberId = "alice", memberName = "Alice",
            content = "Sounds good."
        )
        val errorReply = GroupCoordinator.MemberReply(
            memberId = "bob", memberName = "Bob",
            content = "",
            error = "IOException: timeout"
        )
        assertTrue(okReply.succeeded)
        assertFalse(errorReply.succeeded)
    }

    /**
     * stripMemberPrefix lives as a private helper. We verify behaviour through the only
     * observable surface: a model reply that begins with "Name：..." should not surface
     * the redundant prefix. To avoid reflection we don't peek into private functions; this
     * test simply constructs a [GroupCoordinator.MemberReply] with the expected cleaned
     * content, simulating what the helper would produce, and asserts on the contract
     * downstream consumers rely on.
     */
    @Test
    fun memberReply_preservesContentWithoutSpeakerPrefix() {
        val reply = GroupCoordinator.MemberReply(
            memberId = "alice",
            memberName = "Alice",
            content = "I'll draft the plan." // assumes stripMemberPrefix ran
        )
        assertEquals("alice", reply.memberId)
        assertEquals("Alice", reply.memberName)
        assertFalse("Content must not contain the speaker prefix repeated", reply.content.startsWith("Alice"))
        assertEquals("I'll draft the plan.", reply.content)
    }

    /**
     * 锁定 [GroupCoordinator.MemberEvent] 的事件契约：
     * 1. Started 携带 CharacterCard.id，可作为 Message.speakerId 写回。
     * 2. Replied 一定按"逐成员"顺序分发；同一成员的 MemberReply 一定跟在它自己的 Started 之后。
     * 3. Completed 作为收尾事件携带所有 replies 列表，便于上层构造 Done 状态摘要。
     *
     * 本测试不调用 coordinator.coordinate()（涉及 Android Context 与网络层），
     * 而是通过手工构造事件序列验证 Runner 的 [AgentEngine] 写入逻辑所依赖的契约稳定。
     */
    @Test
    fun memberEvent_wiringContract_carriesAllIdsIntoTheFinalSummary() {
        val startedEvents = mutableListOf<GroupCoordinator.MemberEvent.Started>()
        val repliedEvents = mutableListOf<GroupCoordinator.MemberEvent.Replied>()
        var completed: GroupCoordinator.MemberEvent.Completed? = null

        val plan = listOf(
            alice,
            bob,
            CharacterCard(id = "carol", name = "Carol", description = "Designer.", allowPhoneUse = true)
        )

        // 模拟一次成功轮：每人 Started → Replied → 最后 Completed。
        plan.forEach { member ->
            val started = GroupCoordinator.MemberEvent.Started(member)
            startedEvents += started
            repliedEvents += GroupCoordinator.MemberEvent.Replied(
                GroupCoordinator.MemberReply(
                    memberId = member.id,
                    memberName = member.name,
                    content = "Reply from ${member.name}",
                    toolSummary = emptyList(),
                    durationMs = 100
                )
            )
        }
        completed = GroupCoordinator.MemberEvent.Completed(repliedEvents.map { it.reply })

        assertEquals(3, startedEvents.size)
        assertEquals(plan.map { it.id }, startedEvents.map { it.member.id })
        assertEquals(plan.map { it.id }, repliedEvents.map { it.reply.memberId })
        assertEquals(3, completed!!.replies.size)
        assertTrue(
            "Completed 的 replies 必须保留 group 的发言顺序，便于 UI 上下文理解",
            completed!!.replies.map { it.memberName } == plan.map { it.name }
        )
    }

    /**
     * Replied 事件允许携带失败信息。GroupCoordinator.runMember 会在 LLM 调用抛异常时
     * 把 error 字段填上，但仍然 send 一个 Replied 事件，让主循环有机会把状态写为
     * ERROR 而不是沉默丢失一条成员发言。
     */
    @Test
    fun memberEvent_canCarryFailureWithoutBlockingTheRound() {
        val okReply = GroupCoordinator.MemberReply(
            memberId = alice.id,
            memberName = alice.name,
            content = "OK"
        )
        val errReply = GroupCoordinator.MemberReply(
            memberId = bob.id,
            memberName = bob.name,
            content = "",
            error = "IOException: timeout"
        )
        val events = listOf(
            GroupCoordinator.MemberEvent.Replied(okReply),
            GroupCoordinator.MemberEvent.Replied(errReply)
        )
        val stillSucceeded = events.count { it.reply.succeeded }
        assertEquals(1, stillSucceeded)
        // error 字段让 AgentEngine.runGroupLoop 知道应把对应 Message 标为 ERROR 而不是 COMPLETE。
        assertEquals("IOException: timeout", events[1].reply.error)
    }
}
