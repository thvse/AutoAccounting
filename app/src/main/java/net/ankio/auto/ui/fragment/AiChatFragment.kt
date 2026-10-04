package net.ankio.auto.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import net.ankio.auto.R
import net.ankio.auto.ai.chat.AiAgent
import net.ankio.auto.ai.chat.AiChatAdapter
import net.ankio.auto.ai.chat.ChatMessage
import net.ankio.auto.databinding.FragmentAiChatBinding
import net.ankio.auto.ui.api.BaseFragment
import net.ankio.auto.ui.utils.ToastUtils
import net.ankio.auto.utils.PrefManager

/**
 * AI 财务记账助手对话页面
 * 支持自然语言查账、财务结构深度分析与直接修改账单分类
 */
class AiChatFragment : BaseFragment<FragmentAiChatBinding>() {

    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: AiChatAdapter
    private var isSending = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupInputAndChips()
        checkApiKeyStatus()

        // 初始欢迎消息
        if (chatMessages.isEmpty()) {
            val welcome = ChatMessage(
                role = "assistant",
                content = """你好！我是你的专属 AI 财务助手 🤖

我可以帮你直接操作和分析本地记账数据：
• 📊 **“分析本月消费结构”**：汇总总支出及各大类目占比
• 🔍 **“查询最近消费记录”**：检索账单明细
• 🏷️ **“帮我把昨天 35 元的那笔外卖分类改成‘餐饮’类目”**：智能定位并直接写入数据库修改

有什么我可以帮你的吗？"""
            )
            chatAdapter.addMessage(welcome)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = AiChatAdapter(chatMessages)
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.recyclerViewChat.layoutManager = layoutManager
        binding.recyclerViewChat.adapter = chatAdapter
    }

    private fun checkApiKeyStatus() {
        val apiKey = PrefManager.apiKey.trim()
        if (apiKey.isBlank()) {
            binding.cardKeyWarning.visibility = View.VISIBLE
            binding.cardKeyWarning.setOnClickListener {
                runCatching {
                    findNavController().navigate(R.id.action_aiChatFragment_to_aiConfigFragment)
                }.onFailure {
                    ToastUtils.info("请前往 设置 -> AI 助理 设置您的 API Key")
                }
            }
        } else {
            binding.cardKeyWarning.visibility = View.GONE
        }
    }

    private fun setupInputAndChips() {
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                sendMessage(text)
            }
        }

        binding.chipAnalyzeMonth.setOnClickListener {
            sendMessage("帮我全面分析一下本月的消费情况和各大类目支出占比，给出省钱建议")
        }

        binding.chipQueryRecent.setOnClickListener {
            sendMessage("查询我最近的 5 笔消费流水账单")
        }

        binding.chipFixCategory.setOnClickListener {
            sendMessage("帮我查找最近一笔外卖相关的消费记录，并把它的分类修改为【餐饮】类目")
        }
    }

    private fun sendMessage(userText: String) {
        if (isSending) return
        val apiKey = PrefManager.apiKey.trim()
        if (apiKey.isBlank()) {
            ToastUtils.info("请先配置 API Key")
            checkApiKeyStatus()
            return
        }

        binding.etInput.setText("")
        val userMsg = ChatMessage(role = "user", content = userText)
        chatAdapter.addMessage(userMsg)
        binding.recyclerViewChat.smoothScrollToPosition(chatAdapter.itemCount - 1)

        val aiPlaceholder = ChatMessage(
            role = "assistant",
            content = "正在思考中...",
            isExecutingTool = false
        )
        chatAdapter.addMessage(aiPlaceholder)
        binding.recyclerViewChat.smoothScrollToPosition(chatAdapter.itemCount - 1)

        isSending = true
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSend.isEnabled = false

        launch {
            try {
                val result = AiAgent.chat(chatMessages.dropLast(1)) { toolStatus ->
                    // 在主线程更新工具执行状态
                    activity?.runOnUiThread {
                        chatAdapter.updateLastMessage(
                            content = "正在处理账单数据...",
                            isExecutingTool = true,
                            toolStatus = toolStatus
                        )
                    }
                }

                if (result.isSuccess) {
                    val reply = result.getOrNull().orEmpty()
                    chatAdapter.updateLastMessage(
                        content = reply,
                        isExecutingTool = false,
                        toolStatus = null
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "网络请求异常"
                    chatAdapter.updateLastMessage(
                        content = "❌ 出错了：$err\n\n请检查网络或 API Key 设置是否正确。",
                        isExecutingTool = false,
                        toolStatus = null
                    )
                }
            } finally {
                isSending = false
                binding.progressBar.visibility = View.GONE
                binding.btnSend.isEnabled = true
                binding.recyclerViewChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }
}
