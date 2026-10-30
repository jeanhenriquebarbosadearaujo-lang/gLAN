package com.gdroidlan.app.auth

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

object AccountManagerStore {

    data class UserAccount(
        val username: String,
        val displayName: String,
        val accountUid: Long
    )

    sealed class AuthResult {
        data class Success(val account: UserAccount) : AuthResult()
        data class Error(val message: String) : AuthResult()
    }

    private const val PREF_FILE = "gdroid_lan_accounts_v2"
    private const val KEY_ACCOUNTS_JSON = "accounts_map"
    private const val KEY_ACTIVE_USER = "active_username"

    fun normalizeUsername(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "")

    fun sanitizeDisplayName(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").take(24)

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun deriveAccountUid(cleanUsername: String, password: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("gdroidlan-account-uid-v2:$cleanUsername:$password".toByteArray(Charsets.UTF_8))
        val raw = ByteBuffer.wrap(digest, 0, 8).long
        return if (raw == 0L) 1L else raw
    }

    fun getActiveAccount(context: Context): UserAccount? {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val activeUser = prefs.getString(KEY_ACTIVE_USER, null)?.takeIf { it.isNotBlank() } ?: return null
        val root = readAccountsJson(context)
        val obj = root.optJSONObject(activeUser) ?: return null
        return UserAccount(
            username = activeUser,
            displayName = obj.optString("displayName", activeUser).ifBlank { activeUser },
            accountUid = obj.optLong("uid", 1L)
        )
    }

    fun registerAccount(
        context: Context,
        rawDisplayName: String,
        rawUsername: String,
        password: String
    ): AuthResult {
        val displayName = sanitizeDisplayName(rawDisplayName)
        val username = normalizeUsername(rawUsername)

        if (displayName.length < 2) {
            return AuthResult.Error("Informe um Nome de Exibição válido (mínimo 2 caracteres).")
        }
        if (username.length < 3) {
            return AuthResult.Error("O Nome de Usuário deve ter pelo menos 3 caracteres (letras ou números).")
        }
        if (password.length < 4) {
            return AuthResult.Error("A senha deve ter pelo menos 4 caracteres.")
        }

        val root = readAccountsJson(context)
        if (root.has(username)) {
            return AuthResult.Error("Este nome de usuário já está cadastrado neste aparelho. Utilize a aba Entrar.")
        }

        val uid = deriveAccountUid(username, password)
        val passHash = sha256Hex("gdroidlan-pass-v2:$username:$password")

        val entry = JSONObject().apply {
            put("displayName", displayName)
            put("passHash", passHash)
            put("uid", uid)
        }
        root.put(username, entry)

        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNTS_JSON, root.toString())
            .putString(KEY_ACTIVE_USER, username)
            .apply()

        return AuthResult.Success(UserAccount(username, displayName, uid))
    }

    fun loginAccount(
        context: Context,
        rawUsername: String,
        password: String
    ): AuthResult {
        val username = normalizeUsername(rawUsername)
        if (username.length < 3) {
            return AuthResult.Error("Informe seu Nome de Usuário.")
        }
        if (password.length < 4) {
            return AuthResult.Error("Informe sua senha (mínimo 4 caracteres).")
        }

        val root = readAccountsJson(context)
        val existing = root.optJSONObject(username)
            ?: return AuthResult.Error("Conta não localizada neste aparelho. Utilize a aba Criar Conta.")

        val expectedHash = existing.optString("passHash", "")
        val providedHash = sha256Hex("gdroidlan-pass-v2:$username:$password")
        if (expectedHash != providedHash) {
            return AuthResult.Error("Senha incorreta para o usuário @$username.")
        }

        val displayName = existing.optString("displayName", username).ifBlank { username }
        val uid = existing.optLong("uid", deriveAccountUid(username, password))

        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_USER, username)
            .apply()

        return AuthResult.Success(UserAccount(username, displayName, uid))
    }

    fun updateDisplayName(context: Context, newDisplayName: String): UserAccount? {
        val cleanName = sanitizeDisplayName(newDisplayName)
        if (cleanName.length < 2) return null
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val activeUser = prefs.getString(KEY_ACTIVE_USER, null) ?: return null
        val root = readAccountsJson(context)
        val obj = root.optJSONObject(activeUser) ?: return null
        obj.put("displayName", cleanName)
        root.put(activeUser, obj)
        prefs.edit().putString(KEY_ACCOUNTS_JSON, root.toString()).apply()
        return UserAccount(activeUser, cleanName, obj.optLong("uid", 1L))
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_USER)
            .apply()
    }

    private fun readAccountsJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACCOUNTS_JSON, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
