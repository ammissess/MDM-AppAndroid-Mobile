package com.example.mdmapplication.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class MdmApi(private val baseUrl: String) {

//    private val client = HttpClient(CIO) {
//        install(ContentNegotiation) {
//            json(Json {
//                ignoreUnknownKeys = true
//                isLenient = true
//            })
//        }
//    }

    //khi backend trả 423 + {error,status}, Android đọc được để set UI state đúng.
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    }


    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (status.isSuccess()) return body()
        val err = runCatching { body<ApiErrorResponse>() }.getOrNull()
        throw ApiException(
            httpCode = status.value,
            backendStatus = err?.status,
            message = err?.error ?: "HTTP ${status.value}"
        )
    }
    class ApiException(
        val httpCode: Int,
        val backendStatus: String? = null,
        override val message: String
    ) : Exception(message)

    // ===== AUTH =====
/*
    suspend fun login(username: String, password: String): LoginResponse =
        client.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.bodyOrThrow()
*/

    // ===== AUTH =====
    suspend fun login(username: String, password: String, deviceCode: String? = null): LoginResponse =
        client.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password, deviceCode))
        }.bodyOrThrow()

    // ===== USAGE (BATCH) =====
    suspend fun reportUsageBatch(token: String, req: UsageBatchReportRequest): UsageBatchReportResponse =
        client.post("$baseUrl/api/device/usage/batch") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

// (tuỳ chọn) bỏ reportUsage cũ hoặc giữ nhưng không dùng nữa
// suspend fun reportUsage(...) { ... }  // backend đã bỏ /usage

    // ===== COMMANDS =====
    suspend fun pollCommands(token: String, req: DevicePollCommandsRequest): DevicePollCommandsResponse =
        client.post("$baseUrl/api/device/poll") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

    suspend fun ackCommand(token: String, req: DeviceAckCommandRequest): DeviceAckCommandResponse =
        client.post("$baseUrl/api/device/ack") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

    // ===== DEVICE =====
    suspend fun registerDevice(token: String, req: DeviceRegisterRequest): DeviceRegisterResponse =
        client.post("$baseUrl/api/device/register") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

    suspend fun unlockDevice(token: String, req: DeviceUnlockRequest): DeviceUnlockResponse =
        client.post("$baseUrl/api/device/unlock") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

    // deviceCode bắt buộc theo backend mới
    suspend fun fetchConfig(token: String, userCode: String, deviceCode: String): DeviceConfigResponse =
        client.get("$baseUrl/api/device/config/$userCode?deviceCode=$deviceCode") {
            header("Authorization", "Bearer $token")
        }.bodyOrThrow()

    suspend fun updateLocation(token: String, req: LocationUpdateRequest) =
        client.post("$baseUrl/api/device/location") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body<Map<String, Boolean>>()

    suspend fun reportUsage(token: String, req: UsageReportRequest) =
        client.post("$baseUrl/api/device/usage") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body<Map<String, Boolean>>()

    suspend fun sendEvent(token: String, deviceCode: String, req: DeviceEventRequest) =
        client.post("$baseUrl/api/device/$deviceCode/events") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body<Map<String, Boolean>>()
}