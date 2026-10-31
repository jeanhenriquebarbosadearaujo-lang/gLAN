package com.gdroidlan.app.update

/**
 * Certificado oficial embutido em tempo de compilação pela esteira de build.
 * Assinado com ECDSA P-256 pela chave privada oficial (GLAN_RELEASE_SIGN_KEY).
 */
object OfficialVersionCert {
    const val BUILT_VERSION_CODE: Int = 12
    const val BUILT_VERSION_NAME: String = "3.0"
    const val BUILT_RELEASE_SIG_B64: String =
        "MEUCIBBxAEmkJBS9BbqrNcsgQzAcmNq7b11h9Y1RBIo3RXmHAiEAw4f+Oyc8kfmewSjLTzh29hlU7iJOFfFA0M8Mqj8oM60="
}
