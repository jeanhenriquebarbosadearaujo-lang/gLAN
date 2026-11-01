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
import com.gdroidlan.app.ui.UiMotion
import com.gdroidlan.app.update.OfficialVersionCert

class AuthActivity : AppCompatActivity() {

    private lateinit var b: ActivityAuthBinding
    private var ultimoCliqueMs = 0L
    private var verificandoNome = false
    private val uidCandidato by lazy { AccountManagerStore.generateCandidateUid() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AccountManagerStore.getActiveAccount(this) != null) {
            abrirPainelPrincipal(animarTransicao = false)
            return
        }

        b = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.txtRodapeAuth.text =
            "gLAN v${OfficialVersionCert.BUILT_VERSION_NAME} • Criado por Jean Henrique • TikTok: .memze"

        UiMotion.attachTactilePress(b.btnConfirmarConta)
        UiMotion.animateStaggeredEntrance(
            listOf(
                b.cardLogoAuth,
                b.txtTituloAuth,
                b.txtSubtituloAuth,
                b.cardFormAuth,
                b.txtRodapeAuth
            ),
            startDelayMs = 30L,
            stepDelayMs = 50L,
            distanceDp = 16f
        )

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

    private fun mostrarErroAuth(mensagem: String) {
        b.txtErroConta.text = mensagem
        UiMotion.setPanelVisible(b.txtErroConta, true)
        UiMotion.shakeError(b.cardFormAuth)
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
                mostrarErroAuth(mod.reason)
                return
            }
        }

        verificandoNome = true
        UiMotion.hapticTick(b.btnConfirmarConta)
        UiMotion.setPanelVisible(b.txtErroConta, false)
        b.edtNomeExibicao.isEnabled = false
        b.btnConfirmarConta.isEnabled = false
        b.btnConfirmarConta.text = "Verificando nome..."
        UiMotion.setBreathingPulse(b.btnConfirmarConta, true)

        NameRegistry.checkAndClaimStandalone(nomeLimpo, uidCandidato) { claimRes ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                verificandoNome = false
                UiMotion.setBreathingPulse(b.btnConfirmarConta, false)

                when (claimRes) {
                    is NameRegistry.ClaimResult.AlreadyTaken -> {
                        b.edtNomeExibicao.isEnabled = true
                        b.btnConfirmarConta.isEnabled = true
                        b.btnConfirmarConta.text = "Continuar"
                        mostrarErroAuth("Já existe um usuário com esse nome.")
                    }
                    is NameRegistry.ClaimResult.Claimed -> {
                        when (val res = AccountManagerStore.createSingleAccount(this, nomeLimpo, uidCandidato)) {
                            is AccountManagerStore.CreateResult.Success -> {
                                b.txtErroConta.visibility = View.GONE
                                UiMotion.hapticConfirm(b.btnConfirmarConta)
                                abrirPainelPrincipal(animarTransicao = true)
                            }
                            is AccountManagerStore.CreateResult.Error -> {
                                b.edtNomeExibicao.isEnabled = true
                                b.btnConfirmarConta.isEnabled = true
                                b.btnConfirmarConta.text = "Continuar"
                                mostrarErroAuth(res.message)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun abrirPainelPrincipal(animarTransicao: Boolean) {
        startActivity(Intent(this, MainActivity::class.java))
        if (animarTransicao) {
            UiMotion.applyActivityTransition(this)
        }
        finish()
    }
}
