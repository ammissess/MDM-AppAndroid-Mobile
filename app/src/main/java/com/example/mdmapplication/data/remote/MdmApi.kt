package com.example.mdmapplication.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class MdmApi(private val baseUrl: String) {

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }
    }

    class ApiException(
        val httpCode: Int,
        val backendStatus: String? = null,
        val backendCode: String? = null,
        override val message: String
    ) : Exception(message)

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (status.isSuccess()) return body()

        val err = runCatching { body<ApiErrorResponse>() }.getOrNull()
        throw ApiException(
            httpCode = status.value,
            backendStatus = err?.status,
            backendCode = err?.code,
            message = err?.error ?: "HTTP ${status.value}"
        )
    }

    suspend fun login(username: String, password: String, deviceCode: String): LoginResponse =
        client.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username = username, password = password, deviceCode = deviceCode))
        }.bodyOrThrow()

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

    suspend fun fetchCurrentConfig(token: String, deviceCode: String): DeviceConfigResponse =
        client.get("$baseUrl/api/device/config/current") {
            header("Authorization", "Bearer $token")
            parameter("deviceCode", deviceCode)
        }.bodyOrThrow()

    suspend fun updateLocation(token: String, req: LocationUpdateRequest): Map<String, Boolean> =
        client.post("$baseUrl/api/device/location") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

    suspend fun reportUsage(token: String, req: UsageReportRequest): Map<String, Boolean> =
        client.post("$baseUrl/api/device/usage") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

    suspend fun reportUsageBatch(token: String, req: UsageBatchReportRequest): UsageBatchReportResponse =
        client.post("$baseUrl/api/device/usage/batch") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

    suspend fun sendEvent(token: String, deviceCode: String, req: DeviceEventRequest): Map<String, Boolean> =
        client.post("$baseUrl/api/device/$deviceCode/events") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.bodyOrThrow()

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
}