package com.gdroidlan.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.gdroidlan.app.auth.AccountManagerStore
import com.gdroidlan.app.auth.NameModerationFilter
import com.gdroidlan.app.auth.NameRegistry
import com.gdroidlan.app.databinding.ActivityAuthBinding

class AuthActivity : AppCompatActivity() {

    private lateinit var b: ActivityAuthBinding
    private var ultimoCliqueMs = 0L
    private var verificandoNome = false
    private val uidCandidato by lazy { AccountManagerStore.generateCandidateUid() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AccountManagerStore.getActiveAccount(this) != null) {
            abrirPainelPrincipal()
            return
        }

        b = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.txtRodapeAuth.text =
            "gLAN v${com.gdroidlan.app.update.OfficialVersionCert.BUILT_VERSION_NAME} • Criado por Jean Henrique • TikTok: .memze"

        b.edtNomeExibicao.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                concluirCriacaoConta()
                true
            } else {
                false
            }
        }

        b.btnConfirmarConta.setOnClickListener {
            concluirCriacaoConta()
        }
    }

    private fun concluirCriacaoConta() {
        if (verificandoNome) return
        val agora = SystemClock.elapsedRealtime()
        if (agora - ultimoCliqueMs < 600L) return
        ultimoCliqueMs = agora

        val nomeDigitado = b.edtNomeExibicao.text?.toString().orEmpty()
        val nomeLimpo = when (val mod = NameModerationFilter.validateDisplayName(nomeDigitado)) {
            is NameModerationFilter.CheckResult.Allowed -> mod.sanitized
            is NameModerationFilter.CheckResult.Rejected -> {
                b.txtErroConta.text = mod.reason
                b.txtErroConta.visibility = View.VISIBLE
                return
            }
        }

        verificandoNome = true
        b.txtErroConta.visibility = View.GONE
        b.edtNomeExibicao.isEnabled = false
        b.btnConfirmarConta.isEnabled = false
        b.btnConfirmarConta.text = "Verificando nome..."

        NameRegistry.checkAndClaimStandalone(nomeLimpo, uidCandidato) { claimRes ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                verificandoNome = false

                when (claimRes) {
                    is NameRegistry.ClaimResult.AlreadyTaken -> {
                        b.edtNomeExibicao.isEnabled = true
                        b.btnConfirmarConta.isEnabled = true
                        b.btnConfirmarConta.text = "Continuar"
                        b.txtErroConta.text = "Já existe um usuário com esse nome."
                        b.txtErroConta.visibility = View.VISIBLE
                    }
                    is NameRegistry.ClaimResult.Claimed -> {
                        when (val res = AccountManagerStore.createSingleAccount(this, nomeLimpo, uidCandidato)) {
                            is AccountManagerStore.CreateResult.Success -> {
                                b.txtErroConta.visibility = View.GONE
                                abrirPainelPrincipal()
                            }
                            is AccountManagerStore.CreateResult.Error -> {
                                b.edtNomeExibicao.isEnabled = true
                                b.btnConfirmarConta.isEnabled = true
                                b.btnConfirmarConta.text = "Continuar"
                                b.txtErroConta.text = res.message
                                b.txtErroConta.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
    }

    private fun abrirPainelPrincipal() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
