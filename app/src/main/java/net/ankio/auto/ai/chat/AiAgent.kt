package net.ankio.auto.ai.chat

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ankio.auto.http.api.BillAPI
import net.ankio.auto.storage.Logger
import net.ankio.auto.utils.BillTool
import net.ankio.auto.utils.DateUtils
import net.ankio.auto.utils.PrefManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * AI 智能体与工具调用管理器
 * 支持 OpenAI / DeepSeek 等标准兼容接口的 Tool Calling (Function Calling)
 */
object AiAgent {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val defaultSyncTypes = listOf(
        BillState.Synced.name,
        BillState.Edited.name,
        BillState.Wait2Edit.name
    )

    /**
     * 规范化 API 请求地址
     */
    private fun getCompletionUrl(baseUri: String): String {
        val trimmed = baseUri.trim().removeSuffix("/")
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            trimmed.isNotBlank() -> "$trimmed/v1/chat/completions"
            else -> "https://api.deepseek.com/v1/chat/completions"
        }
    }

    /**
     * 构建 Function Calling 工具声明列表
     */
    private fun buildToolsSchema(): JsonArray {
        val tools = JsonArray()

        // 1. 查询账单
        val queryFunc = JsonObject().apply {
            addProperty("name", "query_bills")
            addProperty("description", "查询用户的记账流水明细，支持按商户名称、备注关键字、年份或月份筛选")
            val params = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val keyword = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要搜索的商户名或备注关键字，如'麦当劳'、'星巴克'、'滴滴'、'外卖'")
                    }
                    val year = JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "年份，例如 2026")
                    }
                    val month = JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "月份 (1-12)，例如 10")
                    }
                    add("keyword", keyword)
                    add("year", year)
                    add("month", month)
                }
                add("properties", props)
            }
            add("parameters", params)
        }
        val queryTool = JsonObject().apply {
            addProperty("type", "function")
            add("function", queryFunc)
        }
        tools.add(queryTool)

        // 2. 修改账单分类
        val updateFunc = JsonObject().apply {
            addProperty("name", "update_bill_category")
            addProperty("description", "修改指定账单ID的分类类目，例如将某笔账单改为'餐饮'、'交通'、'购物'、'娱乐'、'医疗'、'数码'等")
            val params = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val billId = JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "账单ID (数字类型)")
                    }
                    val newCategory = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "目标分类类目名称")
                    }
                    add("bill_id", billId)
                    add("new_category", newCategory)
                }
                add("properties", props)
                val required = JsonArray().apply {
                    add("bill_id")
                    add("new_category")
                }
                add("required", required)
            }
            add("parameters", params)
        }
        val updateTool = JsonObject().apply {
            addProperty("type", "function")
            add("function", updateFunc)
        }
        tools.add(updateTool)

        // 3. 统计分析消费数据
        val statsFunc = JsonObject().apply {
            addProperty("name", "analyze_spending")
            addProperty("description", "获取指定年月的账单汇总统计数据，包含总支出、总收入、各分类消费金额统计，用于深度分析消费结构")
            val params = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val year = JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "年份，默认当年")
                    }
                    val month = JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "月份 (1-12)，默认当月")
                    }
                    add("year", year)
                    add("month", month)
                }
                add("properties", props)
            }
            add("parameters", params)
        }
        val statsTool = JsonObject().apply {
            addProperty("type", "function")
            add("function", statsFunc)
        }
        tools.add(statsTool)

        return tools
    }

    /**
     * 执行具体本地工具
     */
    private suspend fun executeTool(name: String, argsJson: String): String = withContext(Dispatchers.IO) {
        try {
            val args = JsonParser.parseString(argsJson).asJsonObject
            when (name) {
                "query_bills" -> {
                    val keyword = if (args.has("keyword") && !args.get("keyword").isJsonNull) args.get("keyword").asString else ""
                    val now = Calendar.getInstance()
                    val year = if (args.has("year") && !args.get("year").isJsonNull) args.get("year").asInt else now.get(Calendar.YEAR)
                    val month = if (args.has("month") && !args.get("month").isJsonNull) args.get("month").asInt else (now.get(Calendar.MONTH) + 1)

                    val groups = runCatching {
                        BillAPI.listGrouped(defaultSyncTypes, year, month, keyword)
                    }.getOrElse {
                        BillAPI.listGrouped(defaultSyncTypes, keyword = keyword)
                    }

                    val results = JsonArray()
                    groups.flatMap { it.bills }.take(25).forEach { bill ->
                        val item = JsonObject().apply {
                            addProperty("id", bill.id)
                            addProperty("money", bill.money.toString())
                            addProperty("category", bill.cateName)
                            addProperty("shopName", bill.shopName)
                            addProperty("remark", bill.remark)
                            addProperty("date", DateUtils.stampToDate(bill.time))
                            val type = BillTool.getType(bill.type)
                            addProperty("type", if (type == BillType.Income) "收入" else "支出")
                        }
                        results.add(item)
                    }
                    val response = JsonObject().apply {
                        addProperty("count", results.size())
                        add("bills", results)
                    }
                    response.toString()
                }

                "update_bill_category" -> {
                    val billId = args.get("bill_id").asLong
                    val newCategory = args.get("new_category").asString

                    // 检索当前账单并修改分类
                    val groups = BillAPI.listGrouped(defaultSyncTypes, keyword = "")
                    val targetBill = groups.flatMap { it.bills }.find { it.id == billId }

                    if (targetBill != null) {
                        targetBill.cateName = newCategory
                        targetBill.state = BillState.Edited.name
                        BillAPI.put(targetBill)
                        JsonObject().apply {
                            addProperty("success", true)
                            addProperty("bill_id", billId)
                            addProperty("new_category", newCategory)
                            addProperty("message", "已成功将账单ID ${billId} 的分类修改为【${newCategory}】")
                        }.toString()
                    } else {
                        JsonObject().apply {
                            addProperty("success", false)
                            addProperty("message", "未找到ID为 ${billId} 的账单记录，请先调用 query_bills 查询")
                        }.toString()
                    }
                }

                "analyze_spending" -> {
                    val now = Calendar.getInstance()
                    val year = if (args.has("year") && !args.get("year").isJsonNull) args.get("year").asInt else now.get(Calendar.YEAR)
                    val month = if (args.has("month") && !args.get("month").isJsonNull) args.get("month").asInt else (now.get(Calendar.MONTH) + 1)

                    val groups = runCatching {
                        BillAPI.listGrouped(defaultSyncTypes, year, month, "")
                    }.getOrDefault(emptyList())

                    val bills = groups.flatMap { it.bills }
                    var totalExpense = 0.0
                    var totalIncome = 0.0
                    val categorySums = mutableMapOf<String, Double>()

                    bills.forEach { bill ->
                        val amount = bill.money.toString().toDoubleOrNull() ?: 0.0
                        val type = BillTool.getType(bill.type)
                        if (type == BillType.Income) {
                            totalIncome += amount
                        } else {
                            totalExpense += amount
                            val cat = bill.cateName.ifBlank { "未分类" }
                            categorySums[cat] = (categorySums[cat] ?: 0.0) + amount
                        }
                    }

                    val catArray = JsonArray()
                    categorySums.entries.sortedByDescending { it.value }.forEach { (cat, sum) ->
                        val obj = JsonObject().apply {
                            addProperty("category", cat)
                            addProperty("amount", String.format("%.2f", sum))
                            if (totalExpense > 0) {
                                addProperty("percentage", String.format("%.1f%%", (sum / totalExpense) * 100))
                            }
                        }
                        catArray.add(obj)
                    }

                    JsonObject().apply {
                        addProperty("year", year)
                        addProperty("month", month)
                        addProperty("bill_count", bills.size)
                        addProperty("total_expense", String.format("%.2f", totalExpense))
                        addProperty("total_income", String.format("%.2f", totalIncome))
                        add("categories", catArray)
                    }.toString()
                }

                else -> "{\"error\": \"未知工具: $name\"}"
            }
        } catch (e: Exception) {
            Logger.e("工具执行异常: $name", e)
            "{\"error\": \"执行失败: ${e.message}\"}"
        }
    }

    /**
     * 发送对话请求并自动处理工具调用
     */
    suspend fun chat(
        messagesHistory: List<ChatMessage>,
        onToolStatus: (status: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val apiKey = PrefManager.apiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("尚未配置 API Key，请前往设置中心填入"))
            }

            val baseUri = PrefManager.apiUri.trim()
            val url = getCompletionUrl(baseUri)
            val model = PrefManager.apiModel.trim().ifBlank { "deepseek-chat" }

            // 构造消息数组
            val messagesArray = JsonArray()

            // 系统角色提示词
            val systemMsg = JsonObject().apply {
                addProperty("role", "system")
                addProperty(
                    "content",
                    """你是自动记账APP的专属AI财务助理。
你具备调用本地记账数据库工具的能力：
1. query_bills: 查询用户的流水账单（支持商户名、日期、关键字筛选）。
2. update_bill_category: 修改某笔账单的分类类目。当用户要求修改某笔消费的类目时，如果不知道ID，先调用 query_bills 搜索定位，再调用 update_bill_category 执行修改。
3. analyze_spending: 获取某年某月的财务汇总统计（总支出、总收入、分类占比）。

回答风格：亲切、专业、简明扼要，多用 Emoji，帮用户清晰管理个人财务。"""
                )
            }
            messagesArray.add(systemMsg)

            // 历史消息
            messagesHistory.forEach { msg ->
                val m = JsonObject().apply {
                    addProperty("role", msg.role)
                    addProperty("content", msg.content)
                }
                messagesArray.add(m)
            }

            val tools = buildToolsSchema()

            // 构建初始请求体
            val payload = JsonObject().apply {
                addProperty("model", model)
                add("messages", messagesArray)
                add("tools", tools)
                addProperty("tool_choice", "auto")
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("API 请求失败 (${response.code}): $responseBody"))
            }

            val rootJson = JsonParser.parseString(responseBody).asJsonObject
            val choice = rootJson.getAsJsonArray("choices").get(0).asJsonObject
            val messageObj = choice.getAsJsonObject("message")

            // 判断是否有工具调用
            if (messageObj.has("tool_calls") && !messageObj.get("tool_calls").isJsonNull) {
                val toolCalls = messageObj.getAsJsonArray("tool_calls")
                messagesArray.add(messageObj) // 将带 tool_calls 的 assistant 消息加入上下文

                for (i in 0 until toolCalls.size()) {
                    val toolCall = toolCalls.get(i).asJsonObject
                    val toolCallId = toolCall.get("id").asString
                    val func = toolCall.getAsJsonObject("function")
                    val funcName = func.get("name").asString
                    val funcArgs = func.get("arguments").asString

                    onToolStatus("正在执行操作: ${getToolDescription(funcName)}...")
                    val toolResult = executeTool(funcName, funcArgs)

                    val toolMsg = JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", toolCallId)
                        addProperty("content", toolResult)
                    }
                    messagesArray.add(toolMsg)
                }

                // 携带工具执行结果再次请求大模型生成最终回复
                onToolStatus("正在生成财务分析总结...")
                val nextPayload = JsonObject().apply {
                    addProperty("model", model)
                    add("messages", messagesArray)
                }

                val secondReq = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(nextPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                val secondResp = client.newCall(secondReq).execute()
                val secondBody = secondResp.body?.string().orEmpty()
                val secondRoot = JsonParser.parseString(secondBody).asJsonObject
                val finalContent = secondRoot.getAsJsonArray("choices").get(0).asJsonObject
                    .getAsJsonObject("message").get("content").asString

                Result.success(finalContent)
            } else {
                val content = messageObj.get("content").asString
                Result.success(content)
            }
        } catch (e: Exception) {
            Logger.e("AiAgent 交互失败", e)
            Result.failure(e)
        }
    }

    private fun getToolDescription(name: String): String = when (name) {
        "query_bills" -> "查询本地账单"
        "update_bill_category" -> "更新账单分类"
        "analyze_spending" -> "统计消费数据"
        else -> name
    }
}
