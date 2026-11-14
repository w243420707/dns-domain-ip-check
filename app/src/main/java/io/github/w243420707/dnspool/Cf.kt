package io.github.w243420707.dnspool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Cloudflare 调用失败。reason 为可直接展示给用户的中文提示；retryable 表示是否值得重试 */
class CfException(
    val reason: String,
    val retryable: Boolean,
    val httpStatus: Int = 0,
    val cfCode: Int = 0
) : Exception(reason)

/**
 * 写请求限流器：串行化所有写操作，保证相邻两次写之间至少间隔 minIntervalMs；
 * 收到 429 后记录全局冷却截止时间，冷却期内所有写请求一起等待。
 */
class RateLimiter(private val minIntervalMs: Long = 250L) {
    private val mutex = Mutex()
    private var lastWriteAt = 0L

    @Volatile
    private var cooldownUntil = 0L

    suspend fun acquire() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val nextAllowed = maxOf(lastWriteAt + minIntervalMs.coerceAtLeast(0L), cooldownUntil)
            val wait = nextAllowed - now
            if (wait > 0) delay(wait)
            lastWriteAt = System.currentTimeMillis()
        }
    }

    fun onRetryAfter(seconds: Int) {
        val until = System.currentTimeMillis() + seconds.coerceAtLeast(0) * 1000L
        if (until > cooldownUntil) cooldownUntil = until
    }
}

private const val API_BASE = "https://api.cloudflare.com/client/v4"
private const val MAX_PAGES = 30
private val JSON_MEDIA = "application/json".toMediaType()

private fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

class CloudflareClient(
    private val tokenProvider: () -> String,
    private val limiter: RateLimiter = RateLimiter(),
    private val http: OkHttpClient = defaultHttp()
) {

    suspend fun verifyToken(): CfTokenInfo {
        val json = request("GET", "$API_BASE/user/tokens/verify")
        val result = json.optJSONObject("result") ?: JSONObject()
        return CfTokenInfo(
            status = result.optString("status", ""),
            id = result.optString("id", "")
        )
    }

    suspend fun listZones(name: String): List<CfZone> {
        val json = request("GET", "$API_BASE/zones?name=${enc(name)}&per_page=50")
        val arr = json.optJSONArray("result") ?: return emptyList()
        val zones = ArrayList<CfZone>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            zones += CfZone(id = id, name = o.optString("name", ""))
        }
        return zones
    }

    suspend fun listAllARecords(zoneId: String, name: String? = null): List<CfRecord> {
        val out = mutableListOf<CfRecord>()
        val nameFilter = if (name.isNullOrBlank()) "" else "&name=${enc(name)}"
        var page = 1
        while (page <= MAX_PAGES) {
            val url = "$API_BASE/zones/$zoneId/dns_records?type=A&per_page=100&page=$page$nameFilter"
            val json = request("GET", url)
            val arr = json.optJSONArray("result")
            if (arr == null || arr.length() == 0) break
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out += toRecord(it) }
            }
            val info = json.optJSONObject("result_info")
            val totalPages = if (info != null && info.has("total_pages")) {
                info.optInt("total_pages", page)
            } else {
                page + 1
            }
            // 双保险：total_pages 判定 + 本页不足 100 条即停，另用 MAX_PAGES 防御死循环
            if (page >= totalPages || arr.length() < 100) break
            page++
        }
        return out
    }

    suspend fun countRecords(zoneId: String): Int {
        val json = request("GET", "$API_BASE/zones/$zoneId/dns_records?per_page=1&page=1")
        val info = json.optJSONObject("result_info") ?: return -1
        if (!info.has("total_count")) return -1
        return info.optInt("total_count", -1)
    }

    suspend fun createRecord(zoneId: String, draft: CfRecordDraft): CfRecord {
        limiter.acquire()
        val body = JSONObject()
            .put("type", "A")
            .put("name", draft.name)
            .put("content", draft.content)
            .put("ttl", draft.ttl.coerceAtLeast(60))
            // proxied 固定 false（灰云）：Cloudflare 代理只转发 HTTP(S)，把 IP 池记录设为橙云会直接不可用
            .put("proxied", false)
            .put("comment", draft.comment)
        val json = request("POST", "$API_BASE/zones/$zoneId/dns_records", body)
        val result = json.optJSONObject("result")
            ?: throw CfException("创建成功但响应缺少记录数据", true, 0)
        return toRecord(result)
    }

    suspend fun patchRecord(zoneId: String, id: String, patch: CfRecordPatch): CfRecord {
        limiter.acquire()
        // 只携带非 null 字段；永不携带 proxied，避免把用户的记录意外改成橙云
        val body = JSONObject()
        patch.content?.let { body.put("content", it) }
        patch.comment?.let { body.put("comment", it) }
        patch.ttl?.let { body.put("ttl", it) }
        val json = request("PATCH", "$API_BASE/zones/$zoneId/dns_records/$id", body)
        val result = json.optJSONObject("result")
            ?: throw CfException("修改成功但响应缺少记录数据", true, 0)
        return toRecord(result)
    }

    suspend fun deleteRecord(zoneId: String, id: String) {
        limiter.acquire()
        request("DELETE", "$API_BASE/zones/$zoneId/dns_records/$id")
    }

    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        http.cache?.close()
    }

    /** 统一出入口：网络、HTTP 状态、业务 success 三层依次处理，任何异常都归一为 CfException */
    private suspend fun request(method: String, url: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${tokenProvider()}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "dnspool-android")
                .method(method, requestBody)
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    val status = resp.code
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val retryAfter = resp.header("Retry-After")?.trim()?.toIntOrNull() ?: 0
                        if (status == 429) limiter.onRetryAfter(retryAfter)
                        throw classify(status, text)
                    }
                    val json = if (text.isBlank()) {
                        JSONObject()
                    } else {
                        runCatching { JSONObject(text) }.getOrNull()
                            ?: throw CfException("Cloudflare 响应无法解析", true, status)
                    }
                    ensureSuccess(json, status, text)
                    json
                }
            } catch (e: CfException) {
                throw e
            } catch (e: IOException) {
                throw CfException("网络不可达：${e.message ?: e.javaClass.simpleName}", true, 0)
            }
        }

    private fun ensureSuccess(json: JSONObject, status: Int, raw: String) {
        // DELETE 等操作可能返回空 body，2xx 即视为成功
        if (raw.isBlank()) return
        if (json.optBoolean("success", false)) return
        val message = errorMessage(raw).ifBlank { raw }
        throw CfException("Cloudflare 返回错误：${message.take(120)}", false, status)
    }

    private fun classify(status: Int, text: String): CfException {
        val message = errorMessage(text)
        val lower = message.lowercase()
        return when {
            status == 401 -> CfException("Token 无效或已撤销，请重新生成", false, 401)
            status == 403 -> CfException("Token 权限不足：需要 Zone:Read 与 Zone:DNS:Edit", false, 403)
            status == 404 -> CfException("Zone 或记录不存在，检查根域名与 Zone ID", false, 404)
            status == 429 -> CfException("触发 Cloudflare 限流，稍后自动重试", true, 429)
            status in 500..599 -> CfException("Cloudflare 服务端错误(HTTP $status)", true, status)
            status == 400 && containsAny(lower, "maximum", "quota", "limit") ->
                CfException("该 Zone 记录数已达套餐上限，请降低 N 或清理记录", false, 400)
            status == 400 && lower.contains("already exists") ->
                CfException("该内容已存在同名记录", false, 400)
            else -> {
                val detail = message.ifBlank { text }.trim().take(120)
                CfException("Cloudflare 返回错误：$detail", false, status)
            }
        }
    }

    private fun errorMessage(text: String): String {
        if (text.isBlank()) return ""
        return try {
            val arr = JSONObject(text).optJSONArray("errors") ?: return ""
            val messages = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i)?.optString("message", "").orEmpty()
                if (m.isNotBlank()) messages += m
            }
            messages.joinToString(" | ")
        } catch (e: Exception) {
            ""
        }
    }

    private fun containsAny(lower: String, vararg keys: String): Boolean =
        keys.any { lower.contains(it) }

    private fun toRecord(o: JSONObject): CfRecord {
        val comment = if (o.isNull("comment")) {
            null
        } else {
            o.optString("comment", "").takeIf { it.isNotEmpty() }
        }
        return CfRecord(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            type = o.optString("type", "A"),
            content = o.optString("content", ""),
            ttl = o.optInt("ttl", 60),
            proxied = o.optBoolean("proxied", false),
            comment = comment
        )
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
