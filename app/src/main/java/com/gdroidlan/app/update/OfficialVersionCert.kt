package com.gdroidlan.app.update

/**
 * Certificado oficial embutido em tempo de compilação pela esteira de build.
 * Assinado com ECDSA P-256 pela chave privada oficial (GLAN_RELEASE_SIGN_KEY).
 */
object OfficialVersionCert {
    const val BUILT_VERSION_CODE: Int = 15
    const val BUILT_VERSION_NAME: String = "3.3"
    const val BUILT_RELEASE_SIG_B64: String = ""
}
