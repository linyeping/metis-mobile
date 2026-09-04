package com.mrgreenapps.a11ypilot.phoneuse

import android.content.Context
import com.mrgreenapps.a11ypilot.EventLog
import kotlinx.coroutines.delay

/**
 * PhoneUse tool wrapper for MCP integration
 * Provides high-level automation commands
 */
object PhoneUseTool {

    /**
     * Check if PhoneUse service is available
     */
    fun isAvailable(): Boolean {
        return PhoneUseService.isRunning()
    }

    /**
     * Execute screen tap action
     */
    suspend fun tap(x: Float, y: Float): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val success = service.tapAt(x, y)
        return if (success) {
            Result.success("Tapped at ($x, $y)")
        } else {
            Result.failure(Exception("Tap failed"))
        }
    }

    /**
     * Execute swipe gesture
     */
    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 300
    ): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val success = service.swipe(startX, startY, endX, endY, duration)
        return if (success) {
            Result.success("Swiped from ($startX, $startY) to ($endX, $endY)")
        } else {
            Result.failure(Exception("Swipe failed"))
        }
    }

    /**
     * Click element by text
     */
    fun clickByText(text: String, exactMatch: Boolean = false): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val success = service.clickByText(text, exactMatch)
        return if (success) {
            Result.success("Clicked element with text: $text")
        } else {
            Result.failure(Exception("Element not found or not clickable: $text"))
        }
    }

    /**
     * Click element by view ID
     */
    fun clickByViewId(viewId: String): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val success = service.clickByViewId(viewId)
        return if (success) {
            Result.success("Clicked element with ID: $viewId")
        } else {
            Result.failure(Exception("Element not found or not clickable: $viewId"))
        }
    }

    /**
     * Input text to focused field
     */
    fun inputText(text: String): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val success = service.inputText(text)
        return if (success) {
            Result.success("Input text: $text")
        } else {
            Result.failure(Exception("No focused input field"))
        }
    }

    /**
     * Press back button
     */
    fun pressBack(): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val success = service.pressBack()
        return if (success) {
            Result.success("Pressed back button")
        } else {
            Result.failure(Exception("Back action failed"))
        }
    }

    /**
     * Press home button
     */
    fun pressHome(): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val success = service.pressHome()
        return if (success) {
            Result.success("Pressed home button")
        } else {
            Result.failure(Exception("Home action failed"))
        }
    }

    /**
     * Launch application
     */
    fun launchApp(packageName: String): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val success = service.launchApp(packageName)
        return if (success) {
            Result.success("Launched app: $packageName")
        } else {
            Result.failure(Exception("Failed to launch app: $packageName"))
        }
    }

    /**
     * Set alarm
     */
    suspend fun setAlarm(hour: Int, minute: Int, message: String = ""): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        return service.setAlarm(hour, minute, message).fold(
            onSuccess = { alarm ->
                Result.success("已确认登记 Metis 闹钟 ${alarm.id}，时间 $hour:${minute.toString().padStart(2, '0')}")
            },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * Get screen hierarchy
     */
    fun getScreenHierarchy(): Result<String> {
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))

        val hierarchy = service.getScreenHierarchy()
        return Result.success(hierarchy)
    }

    /**
     * Open Bilibili and search for video
     */
    suspend fun searchBilibiliVideo(query: String): Result<String> {
        EventLog.append("phoneuse>bilibili:start query=${query.take(80)}")
        val service = PhoneUseService.getInstance()
            ?: run { EventLog.append("phoneuse>bilibili:fail service_unavailable"); return Result.failure<String>(Exception("PhoneUse service not running")) }

        return try {
            // Launch Bilibili
            EventLog.append("phoneuse>bilibili:launch physical_display")
            if (!service.launchApp("tv.danmaku.bili")) {
                EventLog.append("phoneuse>bilibili:fail launch")
                return Result.failure<String>(Exception("哔哩哔哩无法在当前屏幕启动"))
            }
            if (service.awaitPackageRoot(AppPackages.BILIBILI, 5000) == null) {
                EventLog.append("phoneuse>bilibili:fail physical_window_unavailable")
                return Result.failure(Exception("哔哩哔哩已启动，但无障碍服务未读取到当前窗口"))
            }
            delay(700) // Let the first layout settle before querying controls.

            // Find and click search button
            val searchClicked = service.clickByText("搜索") || listOf("search", "iv_search", "search_bar")
                .any(service::clickByViewId)
            if (!searchClicked) {
                EventLog.append("phoneuse>bilibili:fail search_entry_not_found")
                return Result.failure(Exception("Could not find search button"))
            }
            EventLog.append("phoneuse>bilibili:search_entry_ok")

            delay(500)

            // Input search query
            if (!service.inputText(query)) {
                EventLog.append("phoneuse>bilibili:fail query_input")
                return Result.failure(Exception("哔哩哔哩搜索框不可输入"))
            }
            EventLog.append("phoneuse>bilibili:query_input_ok")
            delay(500)

            // Submit through the IME when available, then fall back to a visible search button.
            if (!service.pressImeEnter() && !service.clickByText("搜索")) {
                EventLog.append("phoneuse>bilibili:fail submit")
                return Result.failure(Exception("哔哩哔哩搜索提交失败"))
            }
            delay(1200)
            val hierarchy = service.getScreenHierarchy()
            EventLog.append("phoneuse>bilibili:results_ready chars=${hierarchy.length}")

            Result.success(
                "已在哔哩哔哩搜索“$query”，结果页已打开。以下是当前屏幕可访问文本，请从中确认最新视频标题/链接后再调用微信发送：\n" +
                    hierarchy.take(12_000)
            )
        } catch (e: Exception) {
            EventLog.append("phoneuse>bilibili:exception ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(Exception("Bilibili search failed: ${e.message}"))
        }
    }

    suspend fun sendWechatMessage(contact: String, message: String): Result<String> {
        EventLog.append("phoneuse>wechat:start contact=${contact.take(40)}")
        val service = PhoneUseService.getInstance()
            ?: run { EventLog.append("phoneuse>wechat:fail service_unavailable"); return Result.failure<String>(Exception("PhoneUse service not running")) }
        if (contact.isBlank() || message.isBlank()) return Result.failure(Exception("联系人和消息不能为空"))
        return try {
            EventLog.append("phoneuse>wechat:launch physical_display")
            if (!service.launchApp(AppPackages.WECHAT)) {
                EventLog.append("phoneuse>wechat:fail launch")
                return Result.failure<String>(Exception("微信无法在当前屏幕启动"))
            }
            if (service.awaitPackageRoot(AppPackages.WECHAT, 5000) == null) {
                EventLog.append("phoneuse>wechat:fail physical_window_unavailable")
                return Result.failure(Exception("微信已启动，但无障碍服务未读取到当前窗口"))
            }
            delay(700)
            val openedSearch = service.clickByText("搜索") || listOf("search", "action_bar_search")
                .any(service::clickByViewId)
            if (!openedSearch) { EventLog.append("phoneuse>wechat:fail search_entry_not_found"); return Result.failure<String>(Exception("微信搜索入口未找到")) }
            delay(400)
            if (!service.inputText(contact)) { EventLog.append("phoneuse>wechat:fail contact_input"); return Result.failure<String>(Exception("微信搜索框不可输入")) }
            delay(900)
            // === 联系人消歧 ============================================================
            // 微信搜索会同时给出「联系人 / 群聊 / 聊天记录 / 公众号」等多个分区的同名候选。
            // 旧实现只点第一个文本匹配项，点中聊天记录或群聊就会进错人、或进不去会话。
            // 这里枚举全部候选，逐个尝试：进入会话后校验顶部标题栏是否真的是目标联系人，
            // 校验失败就返回搜索页换下一个候选。
            // ===========================================================================
            var candidates = service.findAllNodesByText(contact)
            if (candidates.isEmpty()) {
                EventLog.append("phoneuse>wechat:no_candidate contact=${contact.take(40)}")
                return Result.failure(Exception("微信搜索「$contact」没有找到任何联系人，请确认名字是否准确"))
            }
            // 同名候选过多时只尝试前几个，避免在错误结果里反复空转
            val maxCandidates = 4
            if (candidates.size > maxCandidates) {
                EventLog.append("phoneuse>wechat:many_candidates count=${candidates.size} truncated=$maxCandidates")
                candidates = candidates.take(maxCandidates)
            }

            var messageEntered = false
            var verifiedContact: String? = null
            for ((index, candidate) in candidates.withIndex()) {
                EventLog.append("phoneuse>wechat:try_candidate index=$index text=${candidate.text.take(20)} bounds=$candidate.bounds")
                // Search rows often expose the label on a non-clickable child; fall back to a
                // physical tap on the row's center when the semantic click is rejected.
                val semanticClick = service.clickByText(contact, exactMatch = true) || service.clickByText(contact)
                val bounds = candidate.bounds
                if (!semanticClick && !bounds.isEmpty) {
                    service.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }
                delay(650)

                // WeChat exposes the chat composer as `bkk`. Its presence means we really left the
                // search results and opened a conversation (a chat-record hit would not show it).
                var chatEditorReady = service.awaitViewId("bkk", 1200)
                if (!chatEditorReady && !bounds.isEmpty) {
                    service.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    chatEditorReady = service.awaitViewId("bkk", 1800)
                }
                if (!chatEditorReady) {
                    EventLog.append("phoneuse>wechat:candidate_no_chat index=$index")
                    service.pressBack()
                    delay(400)
                    continue
                }

                // 进入会话后校验标题栏：确认打开的确实是目标联系人，而不是同名群聊/公众号。
                val topTexts = service.topBarTexts()
                val titleMatched = topTexts.any { it.contains(contact, ignoreCase = true) }
                EventLog.append("phoneuse>wechat:title_check index=$index matched=$titleMatched top=${topTexts.take(4)}")
                if (!titleMatched) {
                    // 进错会话：立刻返回，绝不输入内容（避免把消息发到错误的会话）
                    service.pressBack()
                    delay(450)
                    continue
                }

                if (service.inputTextByViewId("bkk", message)) {
                    messageEntered = true
                    verifiedContact = topTexts.firstOrNull { it.contains(contact, ignoreCase = true) }
                    EventLog.append("phoneuse>wechat:candidate_accepted index=$index verified=$verifiedContact")
                    break
                }
                EventLog.append("phoneuse>wechat:candidate_editor_input_failed index=$index")
                service.pressBack()
                delay(400)
            }

            if (!messageEntered) {
                EventLog.append("phoneuse>wechat:fail no_verified_contact contact=${contact.take(40)} candidates=${candidates.size}")
                return Result.failure(
                    Exception(
                        "微信里找到 ${candidates.size} 个「$contact」相关结果，但都无法确认进入的是本人会话" +
                            "（可能是群聊/公众号/聊天记录）。请用更完整的备注名重试，或在微信里确认对方显示名。"
                    )
                )
            }
            delay(250)
            // The send icon is unlabeled on some WeChat builds; the editor's IME action is the
            // stable semantic path, with a visible "发送" label as the first fallback.
            val sent = service.clickByText("发送", exactMatch = true) || service.pressImeEnter()
            if (!sent) { EventLog.append("phoneuse>wechat:fail send_button"); return Result.failure<String>(Exception("已输入内容但未找到微信发送按钮，消息可能未发出，请到微信确认")) }
            EventLog.append("phoneuse>wechat:sent")
            val who = verifiedContact ?: contact
            Result.success("已向「$who」的微信会话发送消息（标题栏已校验）")
        } catch (e: Exception) {
            EventLog.append("phoneuse>wechat:exception ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(Exception("微信操作失败：${e.message}"))
        }
    }

    /** Search Bilibili, extract a visible video URL from the physical screen tree, then send it to WeChat. */
    suspend fun shareLatestBilibiliToWechat(query: String, contact: String): Result<String> {
        EventLog.append("phoneuse>workflow:bilibili_to_wechat:start")
        val search = searchBilibiliVideo(query)
        if (search.isFailure) return Result.failure(search.exceptionOrNull() ?: Exception("哔哩哔哩搜索失败"))
        val service = PhoneUseService.getInstance()
            ?: return Result.failure(Exception("PhoneUse service not running"))
        val hierarchy = service.getScreenHierarchy()
        val url = Regex("https?://[^\\s<>\\\"']+")
            .findAll(hierarchy)
            .map { it.value.trimEnd('.', ',', ')', ']', '}', '。', '，') }
            .firstOrNull { it.contains("bilibili.com", ignoreCase = true) || it.contains("b23.tv", ignoreCase = true) }
            ?: return Result.failure(Exception("已完成搜索，但当前屏幕节点树没有暴露视频链接；请先在结果中打开视频后重试"))
        EventLog.append("phoneuse>workflow:bilibili_to_wechat:url_found")
        return sendWechatMessage(contact, "哔哩哔哩“$query”最新视频：$url")
    }

    /**
     * Common app package names
     */
    object AppPackages {
        const val WECHAT = "com.tencent.mm"
        const val QQ = "com.tencent.mobileqq"
        const val ALIPAY = "com.eg.android.AlipayGphone"
        const val TAOBAO = "com.taobao.taobao"
        const val BILIBILI = "tv.danmaku.bili"
        const val DOUYIN = "com.ss.android.ugc.aweme"
        const val CHROME = "com.android.chrome"
        const val SETTINGS = "com.android.settings"
        const val CAMERA = "com.android.camera"
        const val GALLERY = "com.android.gallery3d"
    }
}
