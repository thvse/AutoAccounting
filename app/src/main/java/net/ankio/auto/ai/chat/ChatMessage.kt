package net.ankio.auto.ai.chat

import java.util.UUID

/**
 * 聊天消息数据类
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "system"
    var content: String,
    val time: Long = System.currentTimeMillis(),
    var isExecutingTool: Boolean = false,
    var toolStatus: String? = null,
    var imageUri: String? = null,
    var imageBase64: String? = null
)
