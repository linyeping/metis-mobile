package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import com.mrgreenapps.a11ypilot.EventLog

/**
 * 多LLM智能路由器
 *
 * 功能: 根据任务类型智能选择最优LLM
 *
 * 策略:
 * - 视觉任务 → GPT 中转（需要图像理解）
 * - UI控制 → GPT 中转（需要工具调用+精确操作）
 * - 复杂推理 → GPT 中转（需要强推理能力）
 * - 文本生成 → DeepSeek (成本低95%)
 * - 文件处理 → DeepSeek (简单操作)
 */
class LLMRouter(private val context: Context) {

    sealed class LLMType {
        object GPT : LLMType()
        object DeepSeek : LLMType()
    }

    data class TaskAnalysis(
        val needsVision: Boolean,
        val complexity: Complexity,
        val domain: Domain,
        val confidence: Float
    )

    enum class Complexity {
        SIMPLE,    // 单步操作
        MEDIUM,    // 多步但逻辑简单
        COMPLEX    // 需要推理判断
    }

    enum class Domain {
        UI_CONTROL,        // 手机UI操作
        TEXT_GENERATION,   // 文本生成
        FILE_OPERATIONS,   // 文件处理
        REASONING,         // 逻辑推理
        MIXED             // 混合任务
    }

    /**
     * 路由决策入口
     */
    fun route(instruction: String, currentScreen: String = ""): LLMType {
        val analysis = analyzeTask(instruction, currentScreen)

        return when {
            // 1. 视觉任务优先 GPT
            analysis.needsVision -> {
                logDecision("Vision required → GPT", analysis)
                LLMType.GPT
            }

            // 2. UI控制优先 GPT
            analysis.domain == Domain.UI_CONTROL -> {
                logDecision("UI control → GPT", analysis)
                LLMType.GPT
            }

            // 3. 复杂推理优先 GPT
            analysis.complexity == Complexity.COMPLEX -> {
                logDecision("Complex reasoning → GPT", analysis)
                LLMType.GPT
            }

            // 4. 纯文本生成用DeepSeek
            analysis.domain == Domain.TEXT_GENERATION &&
            analysis.complexity != Complexity.COMPLEX -> {
                logDecision("Text generation → DeepSeek (save 95%)", analysis)
                LLMType.DeepSeek
            }

            // 5. 简单文件操作用DeepSeek
            analysis.domain == Domain.FILE_OPERATIONS &&
            analysis.complexity == Complexity.SIMPLE -> {
                logDecision("Simple file ops → DeepSeek", analysis)
                LLMType.DeepSeek
            }

            // 6. 默认保守策略: GPT
            else -> {
                logDecision("Default fallback → GPT", analysis)
                LLMType.GPT
            }
        }
    }

    private fun analyzeTask(instruction: String, screen: String): TaskAnalysis {
        val lower = instruction.lowercase()

        val needsVision = detectVisionNeed(lower, screen)
        val complexity = assessComplexity(lower)
        val domain = identifyDomain(lower)
        val confidence = 0.8f

        return TaskAnalysis(needsVision, complexity, domain, confidence)
    }

    private fun detectVisionNeed(lower: String, screen: String): Boolean {
        if (screen.contains("(canvas") ||
            screen.contains("(empty tree)") ||
            screen.contains("(video)")) {
            return true
        }

        val visionKeywords = listOf(
            "screenshot", "截图", "屏幕",
            "看", "查看", "显示",
            "图片", "照片", "image"
        )

        return visionKeywords.any { lower.contains(it) }
    }

    private fun assessComplexity(lower: String): Complexity {
        // 简单: 单步操作
        val simplePatterns = listOf(
            "^打开", "^关闭", "^点击",
            "^截图", "^保存", "^读取"
        )
        if (simplePatterns.any { Regex(it).find(lower) != null }) {
            return Complexity.SIMPLE
        }

        // 复杂: 包含条件/循环/判断
        val complexKeywords = listOf(
            "如果", "当", "直到", "除非",
            "if", "when", "until", "unless",
            "判断", "检查", "对比", "找到最"
        )
        if (complexKeywords.any { lower.contains(it) }) {
            return Complexity.COMPLEX
        }

        return Complexity.MEDIUM
    }

    private fun identifyDomain(lower: String): Domain {
        // UI控制关键词
        val uiKeywords = listOf(
            "打开", "关闭", "点击", "滑动", "输入",
            "open", "click", "tap", "scroll", "swipe",
            "应用", "app", "设置", "settings"
        )
        if (uiKeywords.any { lower.contains(it) }) {
            return Domain.UI_CONTROL
        }

        // 文本生成关键词
        val textKeywords = listOf(
            "写", "生成", "创作", "编写", "起草",
            "write", "generate", "create", "draft",
            "文章", "教程", "总结", "报告"
        )
        if (textKeywords.any { lower.contains(it) }) {
            return Domain.TEXT_GENERATION
        }

        // 文件操作关键词
        val fileKeywords = listOf(
            "文件", "file", "保存", "save",
            "读取", "read", "列出", "list",
            "搜索", "search", "markdown"
        )
        if (fileKeywords.any { lower.contains(it) }) {
            return Domain.FILE_OPERATIONS
        }

        // 推理关键词
        val reasoningKeywords = listOf(
            "分析", "建议", "推荐", "评估",
            "analyze", "recommend", "evaluate",
            "为什么", "怎么办", "哪个更好"
        )
        if (reasoningKeywords.any { lower.contains(it) }) {
            return Domain.REASONING
        }

        return Domain.REASONING
    }

    private fun logDecision(reason: String, analysis: TaskAnalysis) {
        EventLog.append(
            "router> $reason | " +
            "domain=${analysis.domain} " +
            "complexity=${analysis.complexity}"
        )
    }

    fun explainChoice(instruction: String): Pair<LLMType, String> {
        val analysis = analyzeTask(instruction, "")
        val llm = route(instruction, "")

        val reason = when {
            analysis.needsVision -> "需要视觉分析"
            analysis.domain == Domain.UI_CONTROL -> "手机UI操作"
            analysis.domain == Domain.TEXT_GENERATION -> "文本生成 (节省95%)"
            analysis.complexity == Complexity.COMPLEX -> "复杂推理"
            else -> "默认策略"
        }

        return llm to reason
    }
}
