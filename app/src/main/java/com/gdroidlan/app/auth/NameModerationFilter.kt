package com.gdroidlan.app.auth

import com.gdroidlan.app.crypto.RoomCrypto
import java.text.Normalizer

/**
 * Filtro local anti-ofensa e anti-+18 para nomes de exibição e títulos de salas públicas:
 * - Normaliza acentos, maiúsculas/minúsculas, repetições de letras ("puuuuta" -> "puta"),
 *   separadores artificiais ("p.u.t.a", "f d p") e substituições numéricas/leetspeak ("b0c3t4", "p1r0ca").
 * - Evita falsos positivos em nomes próprios reais (ex.: Paula, Analice, Cassio, Assis, Lucas).
 */
object NameModerationFilter {

    sealed class CheckResult {
        data class Allowed(val sanitized: String) : CheckResult()
        data class Rejected(val reason: String) : CheckResult()
    }

    // Raízes inequívocas (+18, pornografia, xingamentos graves e discurso de ódio) — bloqueadas em qualquer parte
    private val BANNED_SUBSTRING_ROOTS = listOf(
        // Português +18 / explícito / ofensas graves
        "bucet", "bocet", "busset", "xoxot", "xerec", "cherec", "piroca", "pirok",
        "caralh", "karalh", "punhet", "siriric", "boquet", "chupador", "chupinh",
        "gozad", "orgasm", "prostitut", "vagabund", "arrombad", "arombbad",
        "fodase", "fudid", "fodedor", "putaria", "putinh", "safadinh",
        "estupr", "pedofil", "incest", "zoofil", "necrofil",
        "viadinh", "boiola", "baitol", "traveco", "macacad",
        // Plataformas / termos adultos explícitos
        "porno", "hentai", "xvideos", "pornhub", "redtube", "xnxx", "xhamster",
        "onlyfans", "privacy18", "rule34", "camgirl", "websexo", "sexocam",
        // Inglês +18 / ofensas graves / ódio
        "motherfuck", "fucker", "fucking", "fuckyou", "blowjob", "handjob",
        "cumshot", "creampie", "deepthroat", "gangbang", "masturbat",
        "dildo", "vibrator", "pussy", "whore", "bitch", "slutty",
        "penis", "vagina", "boobs", "titties", "shemale",
        "hitler", "nazist", "neonazi", "suastic", "swastik",
        "nigger", "nigga", "faggot", "retardad"
    )

    // Termos curtos bloqueados quando usados como palavras/siglas isoladas
    private val BANNED_EXACT_TOKENS = setOf(
        // Português
        "cu", "cuzinho", "cuzuda", "cuzao", "puta", "puto", "putas", "putos",
        "pau", "rola", "rolao", "pica", "pika", "foda", "fode", "foder", "fodo", "fudi",
        "sexo", "sexy", "anal", "oral", "bdsm", "milf", "nude", "nudes", "nsfw", "xxx",
        "porn", "vadia", "vadio", "corno", "corna", "merda", "bosta",
        "viado", "veado", "bicha", "piranha", "cacete", "kct", "krl",
        "fdp", "vsf", "vtnc", "pqp", "tmnc", "pnc", "babaca", "otario", "imbecil",
        "lixo", "escrot", "escroto", "escrota", "nazi", "kkk",
        // Inglês
        "sex", "ass", "asshole", "cock", "dick", "fuck", "shit", "cunt",
        "slut", "rape", "rapist", "horny", "tits", "cum", "jizz", "porn"
    )

    private fun stripAccentsLower(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
    }

    private fun decodeLeetChar(ch: Char): Char = when (ch) {
        '0' -> 'o'
        '1', '!' -> 'i'
        '3' -> 'e'
        '4', '@' -> 'a'
        '5', '$' -> 's'
        '7' -> 't'
        '8' -> 'b'
        else -> ch
    }

    private fun decodeLeet(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(decodeLeetChar(c))
        }
        return sb.toString()
    }

    private fun collapseRepeats(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var prev = '\u0000'
        for (c in text) {
            if (c != prev) {
                sb.append(c)
                prev = c
            }
        }
        return sb.toString()
    }

    fun canonicalNameKey(raw: String): String {
        val clean = RoomCrypto.cleanSafeText(raw, 24)
        return stripAccentsLower(clean)
            .replace(Regex("[^a-z0-9]+"), "")
            .trim()
    }

    fun containsBlockedContent(raw: String): Boolean {
        val base = stripAccentsLower(raw)
        if (base.isBlank()) return false

        val leet = decodeLeet(base)

        // 1. Verifica raízes proibidas em versão compacta (captura "p.i.r.o.c.a", "b_0_c_3_t_4", "puuuutaria")
        val compactRaw = base.replace(Regex("[^a-z0-9]"), "")
        val compactLeet = leet.replace(Regex("[^a-z]"), "")
        val collapsedRaw = collapseRepeats(compactRaw)
        val collapsedLeet = collapseRepeats(compactLeet)

        for (root in BANNED_SUBSTRING_ROOTS) {
            val collapsedRoot = collapseRepeats(root)
            if (compactRaw.contains(root) ||
                compactLeet.contains(root) ||
                (collapsedRoot.length >= 5 && (collapsedRaw.contains(collapsedRoot) || collapsedLeet.contains(collapsedRoot)))
            ) {
                return true
            }
        }

        // 2. Verifica palavras isoladas (tokens puros de letras e tokens com leetspeak)
        val rawAlphaTokens = base.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val leetTokens = leet.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

        for (tok in rawAlphaTokens) {
            if (tok in BANNED_EXACT_TOKENS || collapseRepeats(tok) in BANNED_EXACT_TOKENS) {
                return true
            }
        }

        // Em tokens com números convertidos, só aplica se o token original não terminava apenas em número comum (ex.: "lucas123")
        val rawAlnumTokens = base.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        for (alnum in rawAlnumTokens) {
            val pureLettersPrefix = alnum.replace(Regex("[0-9]+$"), "")
            if (pureLettersPrefix.isNotEmpty() && pureLettersPrefix in BANNED_EXACT_TOKENS) {
                return true
            }
            val decodedTok = decodeLeet(alnum).replace(Regex("[^a-z]"), "")
            if (decodedTok in BANNED_EXACT_TOKENS || collapseRepeats(decodedTok) in BANNED_EXACT_TOKENS) {
                return true
            }
        }

        // 3. Captura soletração espaçada/pontuada de palavras curtas ("p u t a", "f.d.p", "s-e-x-o")
        if (rawAlphaTokens.count { it.length == 1 } >= 2) {
            val joinedSingles = StringBuilder()
            for (tok in rawAlphaTokens) {
                if (tok.length == 1) {
                    joinedSingles.append(tok)
                    val cur = joinedSingles.toString()
                    if (cur in BANNED_EXACT_TOKENS || collapseRepeats(cur) in BANNED_EXACT_TOKENS) {
                        return true
                    }
                } else {
                    joinedSingles.setLength(0)
                }
            }
        }
        if (leetTokens.count { it.length == 1 } >= 2) {
            val joinedLeetSingles = StringBuilder()
            for (tok in leetTokens) {
                if (tok.length == 1) {
                    joinedLeetSingles.append(tok)
                    val cur = joinedLeetSingles.toString()
                    if (cur in BANNED_EXACT_TOKENS || collapseRepeats(cur) in BANNED_EXACT_TOKENS) {
                        return true
                    }
                } else {
                    joinedLeetSingles.setLength(0)
                }
            }
        }

        // 4. Bloqueia referências explícitas "+18" / "18+"
        if (Regex("(\\+\\s*18|18\\s*\\+)").containsMatchIn(base)) {
            return true
        }

        return false
    }

    fun validateDisplayName(raw: String): CheckResult {
        val clean = RoomCrypto.cleanSafeText(raw, 24)
        if (clean.length < 2) {
            return CheckResult.Rejected("Informe um nome válido com pelo menos 2 caracteres.")
        }
        val alnumCount = clean.count { it.isLetterOrDigit() }
        if (alnumCount < 2) {
            return CheckResult.Rejected("O nome precisa conter pelo menos 2 letras ou números.")
        }
        if (containsBlockedContent(clean)) {
            return CheckResult.Rejected("Nome não permitido: contém palavras ofensivas ou conteúdo +18.")
        }
        return CheckResult.Allowed(clean)
    }

    fun validateRoomTitle(raw: String): CheckResult {
        val clean = RoomCrypto.cleanSafeText(raw, 28)
        if (clean.isEmpty()) return CheckResult.Allowed("")
        if (containsBlockedContent(clean)) {
            return CheckResult.Rejected("O nome da sala contém palavras ofensivas ou conteúdo +18.")
        }
        return CheckResult.Allowed(clean)
    }
}
