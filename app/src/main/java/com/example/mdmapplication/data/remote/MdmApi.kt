package com.example.mdmapplication.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class MdmApi(private val baseUrl: String) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    // ===== AUTH =====
    suspend fun login(username: String, password: String): LoginResponse =
        client.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body()

    // ===== DEVICE =====
    suspend fun registerDevice(token: String, req: DeviceRegisterRequest): DeviceRegisterResponse =
        client.post("$baseUrl/api/device/register") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    suspend fun unlockDevice(token: String, req: DeviceUnlockRequest): DeviceUnlockResponse =
        client.post("$baseUrl/api/device/unlock") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    // deviceCode bắt buộc theo backend mới
    suspend fun fetchConfig(token: String, userCode: String, deviceCode: String): DeviceConfigResponse =
        client.get("$baseUrl/api/device/config/$userCode?deviceCode=$deviceCode") {
            header("Authorization", "Bearer $token")
        }.body()

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