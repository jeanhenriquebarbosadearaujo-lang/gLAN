package com.gdroidlan.app.auth

import android.content.Context
import com.gdroidlan.app.crypto.RoomCrypto
import java.security.SecureRandom

object AccountManagerStore {

    data class UserAccount(
        val displayName: String,
        val accountUid: Long,
        val accountTag: String
    )

    sealed class CreateResult {
        data class Success(val account: UserAccount) : CreateResult()
        data class Error(val message: String) : CreateResult()
    }

    private const val PREF_FILE = "gdroid_lan_single_session_v21"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_ACCOUNT_UID = "account_uid"
    private const val KEY_ACCOUNT_TAG = "account_tag"

    private val rng = SecureRandom()

    fun sanitizeDisplayName(raw: String): String =
        RoomCrypto.cleanSafeText(raw, 24)

    fun getActiveAccount(context: Context): UserAccount? {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val savedName = prefs.getString(KEY_DISPLAY_NAME, null)
            ?.let { sanitizeDisplayName(it) }
            ?.takeIf { it.length >= 2 }
            ?: return null
        val uid = prefs.getLong(KEY_ACCOUNT_UID, 0L)
        if (uid == 0L) return null
        val tag = prefs.getString(KEY_ACCOUNT_TAG, null) ?: formatTag(uid)
        return UserAccount(
            displayName = savedName,
            accountUid = uid,
            accountTag = tag
        )
    }

    fun createSingleAccount(context: Context, rawDisplayName: String): CreateResult {
        getActiveAccount(context)?.let { existing ->
            return CreateResult.Success(existing)
        }

        val cleanName = sanitizeDisplayName(rawDisplayName)
        if (cleanName.length < 2) {
            return CreateResult.Error("Informe um nome válido com pelo menos 2 caracteres.")
        }

        var uid = rng.nextLong()
        if (uid == 0L) uid = 1L
        val tag = formatTag(uid)

        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_NAME, cleanName)
            .putLong(KEY_ACCOUNT_UID, uid)
            .putString(KEY_ACCOUNT_TAG, tag)
            .apply()

        return CreateResult.Success(UserAccount(cleanName, uid, tag))
    }

    private fun formatTag(uid: Long): String {
        val positive = (uid ushr 1) and 0xFFFFFFL
        return "%06X".format(positive)
    }
}
