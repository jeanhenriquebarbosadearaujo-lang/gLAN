package com.gdroidlan.app.auth

import android.content.Context
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
        when (val res = NameModerationFilter.validateDisplayName(raw)) {
            is NameModerationFilter.CheckResult.Allowed -> res.sanitized
            is NameModerationFilter.CheckResult.Rejected -> ""
        }

    fun generateCandidateUid(): Long {
        var uid = rng.nextLong()
        if (uid == 0L) uid = 1L
        return uid
    }

    fun getActiveAccount(context: Context): UserAccount? {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val rawSaved = prefs.getString(KEY_DISPLAY_NAME, null) ?: return null
        val savedName = com.gdroidlan.app.crypto.RoomCrypto.cleanSafeText(rawSaved, 24)
            .takeIf { it.length >= 2 }
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

    fun createSingleAccount(
        context: Context,
        rawDisplayName: String,
        preGeneratedUid: Long = 0L
    ): CreateResult {
        getActiveAccount(context)?.let { existing ->
            return CreateResult.Success(existing)
        }

        val cleanName = when (val chk = NameModerationFilter.validateDisplayName(rawDisplayName)) {
            is NameModerationFilter.CheckResult.Allowed -> chk.sanitized
            is NameModerationFilter.CheckResult.Rejected -> return CreateResult.Error(chk.reason)
        }

        val uid = if (preGeneratedUid != 0L) preGeneratedUid else generateCandidateUid()
        val tag = formatTag(uid)

        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_NAME, cleanName)
            .putLong(KEY_ACCOUNT_UID, uid)
            .putString(KEY_ACCOUNT_TAG, tag)
            .apply()

        return CreateResult.Success(UserAccount(cleanName, uid, tag))
    }

    fun renameActiveAccount(context: Context, rawNewDisplayName: String): CreateResult {
        val current = getActiveAccount(context)
            ?: return CreateResult.Error("Nenhuma conta ativa encontrada.")

        val cleanName = when (val chk = NameModerationFilter.validateDisplayName(rawNewDisplayName)) {
            is NameModerationFilter.CheckResult.Allowed -> chk.sanitized
            is NameModerationFilter.CheckResult.Rejected -> return CreateResult.Error(chk.reason)
        }

        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_NAME, cleanName)
            .apply()

        return CreateResult.Success(
            UserAccount(
                displayName = cleanName,
                accountUid = current.accountUid,
                accountTag = current.accountTag
            )
        )
    }

    private fun formatTag(uid: Long): String {
        val positive = (uid ushr 1) and 0xFFFFFFL
        return "%06X".format(positive)
    }
}
