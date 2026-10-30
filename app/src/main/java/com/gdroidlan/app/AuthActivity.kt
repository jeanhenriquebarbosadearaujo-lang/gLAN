package com.gdroidlan.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.gdroidlan.app.auth.AccountManagerStore
import com.gdroidlan.app.databinding.ActivityAuthBinding

class AuthActivity : AppCompatActivity() {

    private lateinit var b: ActivityAuthBinding
    private var modoCriarConta = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AccountManagerStore.getActiveAccount(this) != null) {
            abrirPainelPrincipal()
            return
        }

        b = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.grupoAbaConta.check(R.id.btnAbaCriarConta)
        atualizarModoTela()

        b.grupoAbaConta.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            modoCriarConta = (checkedId == R.id.btnAbaCriarConta)
            b.txtErroConta.visibility = View.GONE
            atualizarModoTela()
        }

        b.btnConfirmarConta.setOnClickListener {
            processarFormulario()
        }
    }

    private fun atualizarModoTela() {
        if (modoCriarConta) {
            b.layoutNomeExibicao.visibility = View.VISIBLE
            b.txtDescricaoConta.text =
                "Crie sua conta e defina o nome de exibição que aparecerá na sala para o anfitrião e participantes."
            b.btnConfirmarConta.text = "CRIAR CONTA E CONTINUAR"
        } else {
            b.layoutNomeExibicao.visibility = View.GONE
            b.txtDescricaoConta.text =
                "Entre com seu nome de usuário e senha cadastrados para acessar o painel de rede."
            b.btnConfirmarConta.text = "ENTRAR NA CONTA"
        }
    }

    private fun processarFormulario() {
        val nomeExibicao = b.edtNomeExibicao.text?.toString().orEmpty()
        val usuario = b.edtUsuario.text?.toString().orEmpty()
        val senha = b.edtSenha.text?.toString().orEmpty()

        val resultado = if (modoCriarConta) {
            AccountManagerStore.registerAccount(this, nomeExibicao, usuario, senha)
        } else {
            AccountManagerStore.loginAccount(this, usuario, senha)
        }

        when (resultado) {
            is AccountManagerStore.AuthResult.Success -> {
                b.txtErroConta.visibility = View.GONE
                abrirPainelPrincipal()
            }
            is AccountManagerStore.AuthResult.Error -> {
                b.txtErroConta.text = resultado.message
                b.txtErroConta.visibility = View.VISIBLE
            }
        }
    }

    private fun abrirPainelPrincipal() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
