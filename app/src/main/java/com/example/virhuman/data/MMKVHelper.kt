package com.example.virhuman.data

import com.tencent.mmkv.MMKV

object MMKVHelper {
    private val mmkv by lazy { MMKV.defaultMMKV() }

    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"
    private const val KEY_REMEMBER = "remember"
    private const val KEY_DEVICE_CODE = "device_code"
    private const val KEY_FACE_DETECT = "face_detect"
    private const val KEY_FACE_AUTO_DIALOG = "face_auto_dialog"
    private const val KEY_FACE_AUTO_GREET = "face_auto_greet"
    private const val KEY_RESOURCE_JSON = "resource_json"
    private const val KEY_CURRENT_CHARACTER_ID = "current_character_id"
    private const val KEY_CONTINUOUS_DIALOG = "continuous_dialog"

    fun saveAccount(account: String, password: String, remember: Boolean) {
        mmkv.encode(KEY_ACCOUNT, account)
        mmkv.encode(KEY_REMEMBER, remember)
        if (remember) {
            mmkv.encode(KEY_PASSWORD, password)
        } else {
            mmkv.removeValueForKey(KEY_PASSWORD)
        }
    }

    fun getAccount(): String = mmkv.decodeString(KEY_ACCOUNT, "").orEmpty()

    fun getPassword(): String = mmkv.decodeString(KEY_PASSWORD, "").orEmpty()

    fun isRememberPassword(): Boolean = mmkv.decodeBool(KEY_REMEMBER, false)

    fun saveDeviceCode(code: String) {
        mmkv.encode(KEY_DEVICE_CODE, code)
    }

    fun getDeviceCode(): String = mmkv.decodeString(KEY_DEVICE_CODE, "").orEmpty()

    fun setFaceDetectEnabled(enabled: Boolean) {
        mmkv.encode(KEY_FACE_DETECT, enabled)
    }

    fun isFaceDetectEnabled(): Boolean = mmkv.decodeBool(KEY_FACE_DETECT, false)

    fun setFaceAutoDialogEnabled(enabled: Boolean) {
        mmkv.encode(KEY_FACE_AUTO_DIALOG, enabled)
        if (enabled) {
            mmkv.encode(KEY_FACE_AUTO_GREET, false)
        }
    }

    fun isFaceAutoDialogEnabled(): Boolean = mmkv.decodeBool(KEY_FACE_AUTO_DIALOG, false)

    fun setFaceAutoGreetEnabled(enabled: Boolean) {
        mmkv.encode(KEY_FACE_AUTO_GREET, enabled)
        if (enabled) {
            mmkv.encode(KEY_FACE_AUTO_DIALOG, false)
        }
    }

    fun isFaceAutoGreetEnabled(): Boolean = mmkv.decodeBool(KEY_FACE_AUTO_GREET, false)

    fun saveResourceJson(json: String) {
        mmkv.encode(KEY_RESOURCE_JSON, json)
    }

    fun getResourceJson(): String = mmkv.decodeString(KEY_RESOURCE_JSON, "").orEmpty()

    fun saveVideoLocalPath(characterId: String, state: String, path: String) {
        mmkv.encode("video_path_${characterId}_$state", path)
    }

    fun getVideoLocalPath(characterId: String, state: String): String {
        return mmkv.decodeString("video_path_${characterId}_$state", "").orEmpty()
    }

    fun savePictureLocalPath(characterId: String, path: String) {
        mmkv.encode("picture_path_$characterId", path)
    }

    fun getPictureLocalPath(characterId: String): String {
        return mmkv.decodeString("picture_path_$characterId", "").orEmpty()
    }

    fun saveCurrentCharacterId(characterId: String) {
        mmkv.encode(KEY_CURRENT_CHARACTER_ID, characterId)
    }

    fun getCurrentCharacterId(): String {
        return mmkv.decodeString(KEY_CURRENT_CHARACTER_ID, "").orEmpty()
    }

    fun setContinuousDialogEnabled(enabled: Boolean) {
        mmkv.encode(KEY_CONTINUOUS_DIALOG, enabled)
    }

    fun isContinuousDialogEnabled(): Boolean {
        return mmkv.decodeBool(KEY_CONTINUOUS_DIALOG, false)
    }
}
