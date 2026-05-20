package com.example.mdmapplication.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.mdmapplication.BuildConfig
import com.example.mdmapplication.data.remote.DeviceFcmTokenUpsertRequest
import com.example.mdmapplication.data.remote.MdmApi

object DeviceRuntimeIdentity {
    const val BASE_URL = "http://10.0.2.2:8080"
    const val DEVICE_USER = "device"
    const val DEVICE_PASS = "device123"

    /**
     * Debug override for matching a backend-provisioned deviceCode.
     *
     * Usage (emulator):
     *   adb shell setprop debug.mdm.deviceCode <deviceCode>
     *   (then force-stop + relaunch app)
     */
    private const val DEVICE_CODE_OVERRIDE_PROP = "debug.mdm.deviceCode"

    private const val PREFS_NAME = "mdm_runtime_prefs"
    private const val KEY_PENDING_FCM_TOKEN = "pending_fcm_token"
    private const val KEY_PENDING_FCM_TOKEN_UPDATED_AT = "pending_fcm_token_updated_at"
    private const val TAG = "DeviceRuntimeIdentity"
    private val EMULATOR_NAME_PROPS = listOf(
        "ro.boot.qemu.avd_name",
        "ro.kernel.qemu.avd_name"
    )

    data class PendingFcmToken(
        val token: String,
        val updatedAtEpochMillis: Long,
    )

    fun getDeviceCode(context: Context): String {
        val override = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            getMethod.invoke(null, DEVICE_CODE_OVERRIDE_PROP) as? String
        }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (override != null) {
            Log.i(TAG, "getDeviceCode override selected=$override")
            return override
        }

        val fromDefault = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val fromDeviceProtected = runCatching {
            Settings.Secure.getString(
                context.createDeviceProtectedStorageContext().contentResolver,
                Settings.Secure.ANDROID_ID
            )
        }.getOrNull()

        val selected = when {
            !fromDeviceProtected.isNullOrBlank() -> fromDeviceProtected
            !fromDefault.isNullOrBlank() -> fromDefault
            else -> "UNKNOWN"
        }

        Log.i(
            TAG,
            "getDeviceCode source default=$fromDefault deviceProtected=$fromDeviceProtected selected=$selected"
        )
        return selected
    }

    fun getDeviceDisplayName(): String {
        val emulatorName = EMULATOR_NAME_PROPS
            .asSequence()
            .mapNotNull { readSystemProperty(it) }
            .map { it.replace('_', ' ').trim() }
            .firstOrNull { it.isNotEmpty() }

        if (!emulatorName.isNullOrBlank()) return emulatorName

        val model = Build.MODEL?.trim().orEmpty()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        if (model.isBlank() && manufacturer.isBlank()) return "Android Device"
        if (manufacturer.isBlank()) return model
        if (model.isBlank()) return manufacturer
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    private fun readSystemProperty(key: String): String? {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            getMethod.invoke(null, key) as? String
        }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun stagePendingFcmToken(context: Context, token: String, updatedAtEpochMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_FCM_TOKEN, token)
            .putLong(KEY_PENDING_FCM_TOKEN_UPDATED_AT, updatedAtEpochMillis)
            .apply()
    }

    fun getPendingFcmToken(context: Context): PendingFcmToken? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_PENDING_FCM_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val updatedAt = prefs.getLong(KEY_PENDING_FCM_TOKEN_UPDATED_AT, 0L)
            .takeIf { it > 0L }
            ?: System.currentTimeMillis()
        return PendingFcmToken(token = token, updatedAtEpochMillis = updatedAt)
    }

    fun clearPendingFcmToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_FCM_TOKEN)
            .remove(KEY_PENDING_FCM_TOKEN_UPDATED_AT)
            .apply()
    }
}

suspend fun syncPendingFcmToken(
    context: Context,
    api: MdmApi = MdmApi(DeviceRuntimeIdentity.BASE_URL),
    authToken: String? = null,
    deviceCode: String? = null,
): Boolean {
    val pending = DeviceRuntimeIdentity.getPendingFcmToken(context) ?: return false
    val resolvedDeviceCode = deviceCode ?: DeviceRuntimeIdentity.getDeviceCode(context)
    val token = authToken ?: api.login(
        username = DeviceRuntimeIdentity.DEVICE_USER,
        password = DeviceRuntimeIdentity.DEVICE_PASS,
        deviceCode = resolvedDeviceCode,
    ).token

    api.upsertFcmToken(
        token = token,
        req = DeviceFcmTokenUpsertRequest(
            deviceCode = resolvedDeviceCode,
            fcmToken = pending.token,
            appVersion = BuildConfig.VERSION_NAME,
            updatedAtEpochMillis = pending.updatedAtEpochMillis,
        )
    )

    DeviceRuntimeIdentity.clearPendingFcmToken(context)
    return true
}
