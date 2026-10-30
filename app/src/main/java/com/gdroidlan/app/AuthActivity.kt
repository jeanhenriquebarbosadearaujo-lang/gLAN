package com.gdroidlan.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.gdroidlan.app.auth.AccountManagerStore
import com.gdroidlan.app.databinding.ActivityAuthBinding

class AuthActivity : AppCompatActivity() {

    private lateinit var b: ActivityAuthBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Se a conta única desta instalação já foi criada, entra direto no painel principal
        if (AccountManagerStore.getActiveAccount(this) != null) {
            abrirPainelPrincipal()
            return
        }

        b = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(b.root)

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
        val nomeDigitado = b.edtNomeExibicao.text?.toString().orEmpty()
        when (val res = AccountManagerStore.createSingleAccount(this, nomeDigitado)) {
            is AccountManagerStore.CreateResult.Success -> {
                b.txtErroConta.visibility = View.GONE
                abrirPainelPrincipal()
            }
            is AccountManagerStore.CreateResult.Error -> {
                b.txtErroConta.text = res.message
                b.txtErroConta.visibility = View.VISIBLE
            }
        }
    }

    private fun abrirPainelPrincipal() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
