package com.gdroidlan.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import com.gdroidlan.app.auth.AccountManagerStore
import com.gdroidlan.app.auth.NameModerationFilter
import com.gdroidlan.app.auth.NameRegistry
import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.databinding.ActivityMainBinding
import com.gdroidlan.app.databinding.DialogProfileBinding
import com.gdroidlan.app.databinding.DialogSettingsBinding
import com.gdroidlan.app.databinding.ItemPeerRowBinding
import com.gdroidlan.app.databinding.ItemPublicRoomBinding
import com.gdroidlan.app.net.PublicLobbyBrowser
import com.gdroidlan.app.update.OfficialVersionCert
import com.gdroidlan.app.update.UpdateNotifier
import com.gdroidlan.app.vpn.GdroidLanVpnService
import com.gdroidlan.app.vpn.TunnelProtocol
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.Normalizer

class MainActivity : AppCompatActivity() {

    private enum class AbaPrincipal {
        SERVIDORES_PUBLICOS,
        ENTRAR_SALA,
        CRIAR_SERVIDOR
    }

    companion object {
        private const val CREDITO_AUTOR = "Jean Henrique"
        private const val CREDITO_TIKTOK_HANDLE = ".memze"
        private const val CREDITO_TIKTOK_URL = "https://www.tiktok.com/@.memze"

        private const val PREF_SHOW_TRAFFIC = "pref_show_traffic_stats"
        private const val PREF_AUTO_PING_LOBBY = "pref_auto_ping_lobby"
        private const val PREF_ROOM_ALERTS = "pref_room_join_leave_alerts"
        private const val PREF_AUTO_RECONNECT = "pref_auto_reconnect_net"
        private const val PREF_PROTECT_PUBLIC_IP = "pref_protect_public_ip"
    }

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("gdroid_lan_prefs", Context.MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var toastAtual: Toast? = null
    private var bindingConfiguracoesAberto: DialogSettingsBinding? = null

    private var contaAtiva: AccountManagerStore.UserAccount? = null
    private var abaSelecionada: AbaPrincipal = AbaPrincipal.SERVIDORES_PUBLICOS
    private var salaPublicaSelecionada = false
    private var codigoHostAtual = ""
    private var limiteJogadores = TunnelProtocol.MAX_TOTAL_PLAYERS
    private var filtroPesquisaServidor = ""

    private var prefProtegerIpPublico = true
    private var prefReconectarAuto = true
    private var prefMostrarTrafego = true
    private var prefPingLobby = true
    private var prefAvisosSala = true

    private var ultimoTotalBytes = 0L
    private var ultimoTimestampBytesMs = 0L
    private var ultimaTaxaBytesPorSeg = 0L

    private var aguardandoPermissaoParaHost = true
    private var codigoPendenteConexao = ""
    private var protegerIpPendenteConexao = false
    private var pubKeyHostEsperadaPendente = ""

    private var verificandoSala = false
    private var badgeErroLocal: String? = null
    private var erroVerificacaoLocal: String? = null
    private var cacheSalasPublicas: List<PublicLobbyBrowser.PublicRoomEntry> = emptyList()
    private var cacheDiretorioConectado: Boolean = false

    private var cooldownPrincipalAteMs = 0L
    private var cooldownGerarCodigoAteMs = 0L
    private var cooldownPingAteMs = 0L
    private var cooldownLobbyRefreshAteMs = 0L
    private var cooldownDesbloquearAteMs = 0L
    private var cooldownRenomearAteMs = 0L
    private var ultimoToqueAbaMs = 0L
    private var ultimoCodigoAvisoPopup = 0

    private val tickerCooldownRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val agora = SystemClock.elapsedRealtime()
            var precisaContinuar = false

            val restGerar = segundosRestantes(cooldownGerarCodigoAteMs, agora)
            if (restGerar > 0) {
                b.btnGerarNovoCodigo.isEnabled = false
                b.btnGerarNovoCodigo.alpha = 0.5f
                b.btnGerarNovoCodigo.text = "Aguarde ${restGerar}s"
                precisaContinuar = true
            } else if (!isSessaoAtiva()) {
                b.btnGerarNovoCodigo.isEnabled = true
                b.btnGerarNovoCodigo.alpha = 1.0f
                b.btnGerarNovoCodigo.text = "Gerar outro"
            }

            val restLobby = segundosRestantes(cooldownLobbyRefreshAteMs, agora)
            if (restLobby > 0) {
                b.btnAtualizarLobby.isEnabled = false
                b.btnAtualizarLobby.alpha = 0.5f
                b.btnAtualizarLobby.text = "${restLobby}s"
                precisaContinuar = true
            } else {
                b.btnAtualizarLobby.isEnabled = !verificandoSala
                b.btnAtualizarLobby.alpha = if (verificandoSala) 0.5f else 1.0f
                b.btnAtualizarLobby.text = "Atualizar"
            }

            val snap = GdroidLanVpnService.currentSnapshot
            val restPing = segundosRestantes(cooldownPingAteMs, agora)
            if (restPing > 0) {
                b.btnTestarPing.isEnabled = false
                b.btnTestarPing.text = "Testar Ping (${restPing}s)"
                precisaContinuar = true
            } else {
                b.btnTestarPing.isEnabled = (snap.phase == GdroidLanVpnService.StatusPhase.ONLINE)
                b.btnTestarPing.text = "Testar Ping da Sala"
            }

            val restDesbloq = segundosRestantes(cooldownDesbloquearAteMs, agora)
            if (restDesbloq > 0) {
                b.btnDesbloquearRemovidos.isEnabled = false
                b.btnDesbloquearRemovidos.alpha = 0.5f
                precisaContinuar = true
            } else {
                b.btnDesbloquearRemovidos.isEnabled = true
                b.btnDesbloquearRemovidos.alpha = 1.0f
            }

            val restPrincipal = segundosRestantes(cooldownPrincipalAteMs, agora)
            if (restPrincipal > 0) {
                precisaContinuar = true
            }
            atualizarBotaoPrincipalESalas(snap, agora)

            if (precisaContinuar) {
                uiHandler.postDelayed(this, 250L)
            }
        }
    }

    private val lobbyBrowser by lazy {
        PublicLobbyBrowser(installedVersionCode = obterVersionCodeInstalado()) { salas, conectado ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    cacheSalasPublicas = salas
                    cacheDiretorioConectado = conectado
                    renderizarSalasPublicas(salas, conectado)
                }
            }
        }
    }

    private val updateListener: (UpdateNotifier.VerifiedRelease) -> Unit = { novaVersao ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                mostrarAvisoNovaVersao(novaVersao)
            }
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                iniciarCooldownPrincipal(3)
                dispararServicoVpn(
                    asHost = aguardandoPermissaoParaHost,
                    code = codigoPendenteConexao,
                    protectIp = protegerIpPendenteConexao,
                    expectedHostPubKeyB64 = pubKeyHostEsperadaPendente
                )
            } else {
                toast("Autorize a permissão de VPN do Android para ativar a sala.")
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val stateListener: (GdroidLanVpnService.TunnelSnapshot) -> Unit = { snap ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                renderizarEstado(snap)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conta = AccountManagerStore.getActiveAccount(this)
        if (conta == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        contaAtiva = conta

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefProtegerIpPublico = prefs.getBoolean(PREF_PROTECT_PUBLIC_IP, true)
        prefReconectarAuto = prefs.getBoolean(PREF_AUTO_RECONNECT, true)
        prefMostrarTrafego = prefs.getBoolean(PREF_SHOW_TRAFFIC, true)
        prefPingLobby = prefs.getBoolean(PREF_AUTO_PING_LOBBY, true)
        prefAvisosSala = prefs.getBoolean(PREF_ROOM_ALERTS, true)
        lobbyBrowser.setAutoPingEnabled(prefPingLobby)

        val versaoInstalada = obterVersaoInstalada()
        b.txtVersaoTopo.text = "v$versaoInstalada"
        b.txtVersaoMenu.text = "v$versaoInstalada"
        b.txtRodapeMenu.text = "TikTok: $CREDITO_TIKTOK_HANDLE • Versão $versaoInstalada"
        b.txtContaTopo.text = "Conta: ${conta.displayName}"
        b.txtContaMenu.text = "Conta: ${conta.displayName}"

        pedirPermissaoNotificacaoSeNecessario()

        codigoHostAtual = prefs.getString("host_room_code", null)
            ?.takeIf { it.length >= 6 }
            ?: RoomCrypto.generateRoomCode().also {
                prefs.edit().putString("host_room_code", it).apply()
            }
        b.txtCodigoHost.text = codigoHostAtual

        limiteJogadores = prefs.getInt("max_players_v22", TunnelProtocol.MAX_TOTAL_PLAYERS)
            .coerceIn(TunnelProtocol.MIN_TOTAL_PLAYERS, TunnelProtocol.MAX_TOTAL_PLAYERS)
        renderizarSeletorCapacidade(travadoEmSessao = false, totalAtivo = limiteJogadores)

        salaPublicaSelecionada = prefs.getBoolean("host_is_public", false)
        val tituloSalvo = prefs.getString("host_room_title", null).orEmpty()
        if (tituloSalvo.isNotBlank()) {
            b.edtNomeSalaPublica.setText(tituloSalvo)
        } else {
            b.layoutNomeSalaPublica.hint = "Nome da Sala (padrão: Sala de ${conta.displayName})"
        }

        prefs.getString("last_friend_code", null)?.let { salvo ->
            b.edtCodigoAmigo.setText(salvo)
        }

        configurarMenuGaveta(versaoInstalada)
        configurarPesquisaServidores()
        configurarVisibilidadeHost()
        configurarAbasPrincipais()
        configurarSeletorCapacidade()
        configurarBotoesPrincipais()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.drawerPrincipal.isDrawerOpen(GravityCompat.END)) {
                    b.drawerPrincipal.closeDrawer(GravityCompat.END)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        GdroidLanVpnService.onManualPongToast = { rtt, modo ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast("Ping da sala: ${rtt} ms ($modo).")
                }
            }
        }

        GdroidLanVpnService.onModerationEventToast = { msg ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed && prefAvisosSala) {
                    toast(msg)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        GdroidLanVpnService.addListener(stateListener)
        UpdateNotifier.addListener(updateListener)
        gerenciarLobbyBrowserConformeEstado(GdroidLanVpnService.currentSnapshot)
        contaAtiva?.let { c ->
            lobbyBrowser.syncMyActiveNameIfNeeded(c.displayName, c.accountUid)
        }
        dispararTickerCooldown()
    }

    override fun onResume() {
        super.onResume()
        bindingConfiguracoesAberto?.let { atualizarStatusBateriaNoDialogo(it) }
    }

    override fun onStop() {
        lobbyBrowser.stop()
        if (verificandoSala) {
            verificandoSala = false
        }
        UpdateNotifier.removeListener(updateListener)
        GdroidLanVpnService.removeListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        bindingConfiguracoesAberto = null
        uiHandler.removeCallbacksAndMessages(null)
        toastAtual?.cancel()
        super.onDestroy()
    }

    private fun obterVersaoInstalada(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: "3.3"
        } catch (_: Exception) {
            "3.3"
        }
    }

    private fun obterVersionCodeInstalado(): Int {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            OfficialVersionCert.BUILT_VERSION_CODE
        }
    }

    private fun mostrarAvisoNovaVersao(novaVersao: UpdateNotifier.VerifiedRelease) {
        if (novaVersao.versionCode <= obterVersionCodeInstalado()) return
        val versaoAtual = obterVersaoInstalada()

        b.txtTituloAvisoAtualizacao.text = "ATUALIZAÇÃO DISPONÍVEL • v${novaVersao.versionName}"
        b.txtCorpoAvisoAtualizacao.text =
            "Você está usando a versão v$versaoAtual. Atualize o gLAN para a versão v${novaVersao.versionName} para manter todos os recursos e compatibilidade nas salas."
        b.cardAvisoAtualizacao.visibility = View.VISIBLE

        if (ultimoCodigoAvisoPopup < novaVersao.versionCode) {
            ultimoCodigoAvisoPopup = novaVersao.versionCode
            MaterialAlertDialogBuilder(this)
                .setTitle("Atualização Disponível (v${novaVersao.versionName})")
                .setMessage(
                    "Uma nova versão oficial do gLAN já foi lançada.\n\n" +
                    "Sua versão instalada: v$versaoAtual\n" +
                    "Versão atual: v${novaVersao.versionName}\n\n" +
                    "Atualize seu aplicativo para continuar com todos os recursos mais recentes."
                )
                .setPositiveButton("Entendi", null)
                .show()
        }
    }

    private fun criarIntentServicoAutenticado(acao: String): Intent {
        return Intent(this, GdroidLanVpnService::class.java).apply {
            action = acao
            putExtra(GdroidLanVpnService.EXTRA_CALLER_TOKEN, GdroidLanVpnService.INTERNAL_CALLER_TOKEN)
        }
    }

    private fun corPorPing(pingMs: Long): Int = when {
        pingMs <= 0L -> Color.parseColor("#94A3B8")
        pingMs <= 80L -> Color.parseColor("#00E676")
        pingMs <= 170L -> Color.parseColor("#FFCA28")
        else -> Color.parseColor("#FF5252")
    }

    // =========================================================================
    // MENU LATERAL (TRÊS TRACINHOS): CONFIGURAÇÕES, PRIVACIDADE, TERMOS, CRÉDITOS
    // =========================================================================

    private fun configurarMenuGaveta(versao: String) {
        b.btnMenuLateral.setOnClickListener {
            if (b.drawerPrincipal.isDrawerOpen(GravityCompat.END)) {
                b.drawerPrincipal.closeDrawer(GravityCompat.END)
            } else {
                b.drawerPrincipal.openDrawer(GravityCompat.END)
            }
        }

        b.btnMenuPerfil.setOnClickListener {
            b.drawerPrincipal.closeDrawer(GravityCompat.END)
            mostrarDialogoPerfil()
        }

        b.btnMenuConfiguracoes.setOnClickListener {
            b.drawerPrincipal.closeDrawer(GravityCompat.END)
            mostrarDialogoConfiguracoes()
        }

        b.btnMenuPrivacidade.setOnClickListener {
            b.drawerPrincipal.closeDrawer(GravityCompat.END)
            mostrarDialogoPrivacidade()
        }

        b.btnMenuTermos.setOnClickListener {
            b.drawerPrincipal.closeDrawer(GravityCompat.END)
            mostrarDialogoTermos()
        }

        b.btnMenuCreditos.setOnClickListener {
            b.drawerPrincipal.closeDrawer(GravityCompat.END)
            mostrarDialogoCreditos(versao)
        }
    }

    private fun estaSemRestricaoBateria(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun atualizarStatusBateriaNoDialogo(cfg: DialogSettingsBinding) {
        if (estaSemRestricaoBateria()) {
            cfg.txtStatusBateriaConfig.text = "Status atual: Sem restrição de bateria (Ativo)"
            cfg.txtStatusBateriaConfig.setTextColor(Color.parseColor("#00E676"))
            cfg.btnAbrirAjusteBateria.text = "Revisar Ajuste de Bateria"
        } else {
            cfg.txtStatusBateriaConfig.text = "Status atual: Economia de bateria ativa"
            cfg.txtStatusBateriaConfig.setTextColor(Color.parseColor("#FFCA28"))
            cfg.btnAbrirAjusteBateria.text = "Desativar Restrição de Bateria"
        }
    }

    private fun abrirAjustesDeBateriaDoSistema() {
        val intentsTentativa = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (intent in intentsTentativa) {
            try {
                startActivity(intent)
                toast("Em Bateria, selecione 'Sem restrições' para manter o gLAN ativo.")
                return
            } catch (_: Exception) { }
        }
        toast("Abra as Configurações do Android > Aplicativos > gLAN > Bateria.")
    }

    private fun mostrarDialogoPerfil() {
        val conta = contaAtiva ?: return
        val prof = DialogProfileBinding.inflate(layoutInflater)

        prof.txtPerfilNomeAtual.text = conta.displayName
        prof.txtPerfilTagConta.text = "ID da instalação: #${conta.accountTag}"
        prof.edtNovoNomePerfil.setText(conta.displayName)
        prof.edtNovoNomePerfil.setSelection(conta.displayName.length)

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Perfil")
            .setView(prof.root)
            .create()

        if (isSessaoAtiva()) {
            prof.edtNovoNomePerfil.isEnabled = false
            prof.btnSalvarPerfil.isEnabled = false
            prof.btnSalvarPerfil.alpha = 0.45f
            prof.txtMensagemPerfil.text = "Encerre ou saia da sala atual antes de alterar seu nome."
            prof.txtMensagemPerfil.setTextColor(Color.parseColor("#FFCA28"))
            prof.txtMensagemPerfil.visibility = View.VISIBLE
        }

        prof.edtNovoNomePerfil.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isSessaoAtiva()) {
                    prof.txtMensagemPerfil.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        prof.btnFecharPerfil.setOnClickListener {
            dlg.dismiss()
        }

        prof.btnSalvarPerfil.setOnClickListener {
            if (isSessaoAtiva()) {
                prof.txtMensagemPerfil.text = "Encerre a sala atual antes de alterar seu nome."
                prof.txtMensagemPerfil.setTextColor(Color.parseColor("#FFCA28"))
                prof.txtMensagemPerfil.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val agora = SystemClock.elapsedRealtime()
            val rest = segundosRestantes(cooldownRenomearAteMs, agora)
            if (rest > 0) {
                prof.txtMensagemPerfil.text = "Aguarde ${rest}s antes de alterar o nome novamente."
                prof.txtMensagemPerfil.setTextColor(Color.parseColor("#FFCA28"))
                prof.txtMensagemPerfil.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val digitado = prof.edtNovoNomePerfil.text?.toString().orEmpty()
            val validado = when (val mod = NameModerationFilter.validateDisplayName(digitado)) {
                is NameModerationFilter.CheckResult.Allowed -> mod.sanitized
                is NameModerationFilter.CheckResult.Rejected -> {
                    prof.txtMensagemPerfil.text = mod.reason
                    prof.txtMensagemPerfil.setTextColor(Color.parseColor("#FF5252"))
                    prof.txtMensagemPerfil.visibility = View.VISIBLE
                    return@setOnClickListener
                }
            }

            val contaAtual = contaAtiva ?: return@setOnClickListener
            if (validado == contaAtual.displayName) {
                dlg.dismiss()
                return@setOnClickListener
            }

            prof.edtNovoNomePerfil.isEnabled = false
            prof.btnSalvarPerfil.isEnabled = false
            prof.btnFecharPerfil.isEnabled = false
            prof.btnSalvarPerfil.text = "Verificando..."
            prof.txtMensagemPerfil.text = "Verificando disponibilidade do nome..."
            prof.txtMensagemPerfil.setTextColor(Color.parseColor("#00D4FF"))
            prof.txtMensagemPerfil.visibility = View.VISIBLE

            lobbyBrowser.verifyAndClaimRename(
                oldDisplayName = contaAtual.displayName,
                newDisplayName = validado,
                accountUid = contaAtual.accountUid
            ) { resultado ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || !dlg.isShowing) return@runOnUiThread

                    when (resultado) {
                        is NameRegistry.ClaimResult.AlreadyTaken -> {
                            prof.edtNovoNomePerfil.isEnabled = true
                            prof.btnSalvarPerfil.isEnabled = true
                            prof.btnFecharPerfil.isEnabled = true
                            prof.btnSalvarPerfil.text = "Salvar Nome"
                            prof.txtMensagemPerfil.text = "Já existe um usuário com esse nome."
                            prof.txtMensagemPerfil.setTextColor(Color.parseColor("#FF5252"))
                            prof.txtMensagemPerfil.visibility = View.VISIBLE
                        }
                        is NameRegistry.ClaimResult.Claimed -> {
                            when (val salvo = AccountManagerStore.renameActiveAccount(this, validado)) {
                                is AccountManagerStore.CreateResult.Success -> {
                                    contaAtiva = salvo.account
                                    b.txtContaTopo.text = "Conta: ${salvo.account.displayName}"
                                    b.txtContaMenu.text = "Conta: ${salvo.account.displayName}"
                                    if (b.edtNomeSalaPublica.text.isNullOrBlank()) {
                                        b.layoutNomeSalaPublica.hint =
                                            "Nome da Sala (padrão: Sala de ${salvo.account.displayName})"
                                    }
                                    cooldownRenomearAteMs = SystemClock.elapsedRealtime() + 5_000L
                                    dlg.dismiss()
                                    toast("Nome atualizado para ${salvo.account.displayName}.")
                                }
                                is AccountManagerStore.CreateResult.Error -> {
                                    prof.edtNovoNomePerfil.isEnabled = true
                                    prof.btnSalvarPerfil.isEnabled = true
                                    prof.btnFecharPerfil.isEnabled = true
                                    prof.btnSalvarPerfil.text = "Salvar Nome"
                                    prof.txtMensagemPerfil.text = salvo.message
                                    prof.txtMensagemPerfil.setTextColor(Color.parseColor("#FF5252"))
                                    prof.txtMensagemPerfil.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }
            }
        }

        dlg.show()
    }

    private fun mostrarDialogoConfiguracoes() {
        val cfg = DialogSettingsBinding.inflate(layoutInflater)
        bindingConfiguracoesAberto = cfg

        atualizarStatusBateriaNoDialogo(cfg)
        cfg.switchProtegerIpPublico.isChecked = prefProtegerIpPublico
        cfg.switchReconectarAuto.isChecked = prefReconectarAuto
        cfg.switchMostrarTrafego.isChecked = prefMostrarTrafego
        cfg.switchPingLobby.isChecked = prefPingLobby
        cfg.switchAvisosSala.isChecked = prefAvisosSala

        if (isSessaoAtiva()) {
            cfg.switchProtegerIpPublico.isEnabled = false
            cfg.switchProtegerIpPublico.alpha = 0.55f
            cfg.txtDescProtegerIpPublico.text =
                "Usa exclusivamente a Ponte TLS ao criar ou entrar em salas públicas, mantendo seu IP real oculto. (Encerre a sala ativa para alterar.)"
        } else {
            cfg.switchProtegerIpPublico.setOnCheckedChangeListener { _, isChecked ->
                prefProtegerIpPublico = isChecked
                prefs.edit().putBoolean(PREF_PROTECT_PUBLIC_IP, isChecked).apply()
                atualizarAvisoProtecaoIpHost()
            }
        }

        cfg.btnAbrirAjusteBateria.setOnClickListener {
            abrirAjustesDeBateriaDoSistema()
        }

        cfg.switchReconectarAuto.setOnCheckedChangeListener { _, isChecked ->
            prefReconectarAuto = isChecked
            prefs.edit().putBoolean(PREF_AUTO_RECONNECT, isChecked).apply()
            if (isSessaoAtiva()) {
                val upd = criarIntentServicoAutenticado(GdroidLanVpnService.ACTION_SET_AUTO_RECONNECT).apply {
                    putExtra(GdroidLanVpnService.EXTRA_AUTO_RECONNECT, isChecked)
                }
                startService(upd)
            }
        }

        cfg.switchMostrarTrafego.setOnCheckedChangeListener { _, isChecked ->
            prefMostrarTrafego = isChecked
            prefs.edit().putBoolean(PREF_SHOW_TRAFFIC, isChecked).apply()
            b.cardEstatisticasRede.visibility = if (isChecked && isSessaoAtiva()) View.VISIBLE else View.GONE
        }

        cfg.switchPingLobby.setOnCheckedChangeListener { _, isChecked ->
            prefPingLobby = isChecked
            prefs.edit().putBoolean(PREF_AUTO_PING_LOBBY, isChecked).apply()
            lobbyBrowser.setAutoPingEnabled(isChecked)
            renderizarSalasPublicas(cacheSalasPublicas, cacheDiretorioConectado)
        }

        cfg.switchAvisosSala.setOnCheckedChangeListener { _, isChecked ->
            prefAvisosSala = isChecked
            prefs.edit().putBoolean(PREF_ROOM_ALERTS, isChecked).apply()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Configurações")
            .setView(cfg.root)
            .setPositiveButton("Concluir", null)
            .setOnDismissListener {
                if (bindingConfiguracoesAberto === cfg) {
                    bindingConfiguracoesAberto = null
                }
            }
            .show()
    }

    private fun mostrarDialogoPrivacidade() {
        val texto =
            "1. Identificação Local\n" +
            "O gLAN solicita apenas um nome de exibição na primeira abertura para identificar você dentro das salas. " +
            "Não exigimos senha, e-mail, número de telefone, contatos, fotos nem localização GPS. " +
            "Sua conta única fica gravada somente no armazenamento interno desta instalação (sem backup em nuvem).\n\n" +
            "2. Uso da Permissão de VPN (Split-Tunnel)\n" +
            "A interface VPN do Android é usada exclusivamente para rotear a sub-rede virtual privada 10.10.0.0/24 entre os jogadores da mesma sala. " +
            "O restante do tráfego de internet do seu aparelho não é interceptado, armazenado nem redirecionado pelo gLAN.\n\n" +
            "3. Criptografia, Proteção de IP e Dados de Sessão\n" +
            "Os pacotes da sala utilizam criptografia ponta a ponta (ECDH P-256 + AES-128-GCM) e assinatura digital ECDSA. " +
            "Em Salas Públicas com Proteção de IP ativa, toda a comunicação ocorre exclusivamente via Ponte TLS, sem transmitir nem expor seu endereço IP real aos outros participantes."

        MaterialAlertDialogBuilder(this)
            .setTitle("Política de Privacidade")
            .setMessage(texto)
            .setPositiveButton("Entendi", null)
            .show()
    }

    private fun mostrarDialogoTermos() {
        val texto =
            "1. Finalidade do Aplicativo\n" +
            "O gLAN é uma ferramenta de rede virtual P2P criada para facilitar partidas multiplayer em estilo LAN entre amigos e na lista de servidores públicos.\n\n" +
            "2. Regras de Uso e Convivência\n" +
            "Ao criar ou entrar em Salas Públicas, você concorda em não utilizar nomes ou títulos ofensivos e em não tentar sobrecarregar, fraudar ou prejudicar a conexão de outros usuários. " +
            "O anfitrião de cada sala possui autonomia para expulsar ou bloquear participantes da sua sessão.\n\n" +
            "3. Segurança e Responsabilidade\n" +
            "Mantenha códigos de Salas Privadas apenas com pessoas de confiança. " +
            "O aplicativo é disponibilizado de forma gratuita no estado em que se encontra, sem vínculo oficial com marcas ou jogos de terceiros."

        MaterialAlertDialogBuilder(this)
            .setTitle("Termos de Serviço")
            .setMessage(texto)
            .setPositiveButton("Aceitar e Fechar", null)
            .show()
    }

    private fun mostrarDialogoCreditos(versao: String) {
        val texto =
            "Criador e Desenvolvedor:\n" +
            "$CREDITO_AUTOR\n\n" +
            "TikTok Oficial:\n" +
            "$CREDITO_TIKTOK_HANDLE\n\n" +
            "Versão do Aplicativo:\n" +
            "gLAN v$versao\n\n" +
            "Arquitetura:\n" +
            "Rede Virtual LAN com Criptografia E2EE (ECDH P-256 + ECDSA + AES-128-GCM), Proteção de IP via Ponte TLS e Firewall Interno."

        MaterialAlertDialogBuilder(this)
            .setTitle("Créditos e Versão")
            .setMessage(texto)
            .setPositiveButton("Abrir TikTok") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CREDITO_TIKTOK_URL)))
                } catch (_: Exception) {
                    copiarTexto("TikTok $CREDITO_AUTOR", CREDITO_TIKTOK_HANDLE)
                    toast("TikTok $CREDITO_TIKTOK_HANDLE copiado.")
                }
            }
            .setNeutralButton("Copiar TikTok") { _, _ ->
                copiarTexto("TikTok $CREDITO_AUTOR", CREDITO_TIKTOK_HANDLE)
                toast("TikTok $CREDITO_TIKTOK_HANDLE copiado.")
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // =========================================================================
    // PESQUISA EM SERVIDORES PÚBLICOS
    // =========================================================================

    private fun normalizarParaBusca(texto: String): String {
        val semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return semAcento.lowercase().trim()
    }

    private fun configurarPesquisaServidores() {
        b.edtPesquisaServidor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filtroPesquisaServidor = s?.toString().orEmpty()
                renderizarSalasPublicas(cacheSalasPublicas, cacheDiretorioConectado)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // =========================================================================
    // CONTROLES DE ESTADO E COOLDOWNS
    // =========================================================================

    private fun isSessaoAtiva(snap: GdroidLanVpnService.TunnelSnapshot = GdroidLanVpnService.currentSnapshot): Boolean {
        return snap.phase != GdroidLanVpnService.StatusPhase.OFFLINE &&
            snap.phase != GdroidLanVpnService.StatusPhase.ERROR
    }

    private fun segundosRestantes(ateMs: Long, agora: Long = SystemClock.elapsedRealtime()): Int {
        val diff = ateMs - agora
        if (diff <= 0L) return 0
        return ((diff + 999L) / 1000L).toInt()
    }

    private fun iniciarCooldownPrincipal(segundos: Int) {
        cooldownPrincipalAteMs = SystemClock.elapsedRealtime() + segundos * 1_000L
        dispararTickerCooldown()
    }

    private fun dispararTickerCooldown() {
        uiHandler.removeCallbacks(tickerCooldownRunnable)
        uiHandler.post(tickerCooldownRunnable)
    }

    private fun aplicarCooldownBotaoRapido(
        botao: MaterialButton,
        duracaoMs: Long = 1_500L,
        textoTemporario: String? = null
    ) {
        val textoOriginal = botao.text?.toString().orEmpty()
        botao.isEnabled = false
        botao.alpha = 0.6f
        if (textoTemporario != null) {
            botao.text = textoTemporario
        }
        uiHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                botao.isEnabled = true
                botao.alpha = 1.0f
                if (textoTemporario != null) {
                    botao.text = textoOriginal
                }
            }
        }, duracaoMs)
    }

    private fun gerenciarLobbyBrowserConformeEstado(snap: GdroidLanVpnService.TunnelSnapshot) {
        if (isSessaoAtiva(snap)) {
            lobbyBrowser.stop()
        } else {
            lobbyBrowser.setAutoPingEnabled(prefPingLobby)
            lobbyBrowser.start()
        }
    }

    private fun atualizarAvisoProtecaoIpHost() {
        if (!salaPublicaSelecionada) {
            b.txtInfoProtecaoIpPublica.visibility = View.GONE
            return
        }
        b.txtInfoProtecaoIpPublica.visibility = View.VISIBLE
        if (prefProtegerIpPublico) {
            b.txtInfoProtecaoIpPublica.text =
                "Proteção de IP ativa: somente Ponte TLS (seu IP real fica oculto)."
            b.txtInfoProtecaoIpPublica.setTextColor(Color.parseColor("#00E676"))
        } else {
            b.txtInfoProtecaoIpPublica.text =
                "Modo P2P híbrido ativo (ative Proteção de IP em Configurações para ocultar seu IP)."
            b.txtInfoProtecaoIpPublica.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun configurarVisibilidadeHost() {
        b.grupoVisibilidade.check(if (salaPublicaSelecionada) R.id.btnSalaPublica else R.id.btnSalaPrivada)
        b.layoutNomeSalaPublica.visibility = if (salaPublicaSelecionada) View.VISIBLE else View.GONE
        atualizarAvisoProtecaoIpHost()

        b.grupoVisibilidade.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (isSessaoAtiva()) {
                val idTravado = if (salaPublicaSelecionada) R.id.btnSalaPublica else R.id.btnSalaPrivada
                if (checkedId != idTravado) {
                    b.grupoVisibilidade.check(idTravado)
                    toast("Encerre o servidor antes de mudar a visibilidade da sala.")
                }
                return@addOnButtonCheckedListener
            }
            salaPublicaSelecionada = (checkedId == R.id.btnSalaPublica)
            prefs.edit().putBoolean("host_is_public", salaPublicaSelecionada).apply()
            b.layoutNomeSalaPublica.visibility = if (salaPublicaSelecionada) View.VISIBLE else View.GONE
            atualizarAvisoProtecaoIpHost()
        }

        b.edtNomeSalaPublica.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSessaoAtiva()) return
                val txt = RoomCrypto.cleanSafeText(s?.toString().orEmpty(), 28)
                prefs.edit().putString("host_room_title", txt).apply()
            }
        })
    }

    private fun valResSalaRecusada(tituloDigitado: String): Boolean {
        return when (val chk = NameModerationFilter.validateRoomTitle(tituloDigitado)) {
            is NameModerationFilter.CheckResult.Allowed -> false
            is NameModerationFilter.CheckResult.Rejected -> {
                exibirErroSalaLocal("NOME BLOQUEADO", chk.reason)
                true
            }
        }
    }

    private fun obterTituloSalaAtual(): String {
        val digitado = RoomCrypto.cleanSafeText(b.edtNomeSalaPublica.text?.toString().orEmpty(), 28)
        if (digitado.isNotBlank()) return digitado
        val nomeConta = contaAtiva?.displayName ?: "Jogador"
        return RoomCrypto.cleanSafeText("Sala de $nomeConta", 28)
    }

    private fun configurarSeletorCapacidade() {
        b.btnMenosVagas.setOnClickListener {
            if (isSessaoAtiva()) {
                toast("Encerre o servidor antes de alterar o limite de jogadores.")
                return@setOnClickListener
            }
            if (limiteJogadores > TunnelProtocol.MIN_TOTAL_PLAYERS) {
                definirLimiteJogadores(limiteJogadores - 1)
            }
        }
        b.btnMaisVagas.setOnClickListener {
            if (isSessaoAtiva()) {
                toast("Encerre o servidor antes de alterar o limite de jogadores.")
                return@setOnClickListener
            }
            if (limiteJogadores < TunnelProtocol.MAX_TOTAL_PLAYERS) {
                definirLimiteJogadores(limiteJogadores + 1)
            }
        }
    }

    private fun definirLimiteJogadores(novoTotal: Int) {
        if (isSessaoAtiva()) return
        limiteJogadores = novoTotal.coerceIn(
            TunnelProtocol.MIN_TOTAL_PLAYERS,
            TunnelProtocol.MAX_TOTAL_PLAYERS
        )
        prefs.edit().putInt("max_players_v22", limiteJogadores).apply()
        renderizarSeletorCapacidade(travadoEmSessao = false, totalAtivo = limiteJogadores)
    }

    private fun renderizarSeletorCapacidade(travadoEmSessao: Boolean, totalAtivo: Int) {
        val exibido = if (travadoEmSessao) totalAtivo else limiteJogadores
        b.txtNumeroJogadores.text = exibido.toString()

        if (travadoEmSessao) {
            b.txtSubtituloLimite.text = "Travado em $exibido jogadores enquanto a sala estiver ativa"
            b.btnMenosVagas.isEnabled = false
            b.btnMenosVagas.alpha = 0.30f
            b.btnMaisVagas.isEnabled = false
            b.btnMaisVagas.alpha = 0.30f
            return
        }

        val convidados = limiteJogadores - 1
        b.txtSubtituloLimite.text = if (convidados == 1) {
            "Você + 1 convidado (máx. 8)"
        } else {
            "Você + $convidados convidados (máx. 8)"
        }

        val podeDiminuir = limiteJogadores > TunnelProtocol.MIN_TOTAL_PLAYERS
        val podeAumentar = limiteJogadores < TunnelProtocol.MAX_TOTAL_PLAYERS
        b.btnMenosVagas.isEnabled = podeDiminuir
        b.btnMenosVagas.alpha = if (podeDiminuir) 1.0f else 0.35f
        b.btnMaisVagas.isEnabled = podeAumentar
        b.btnMaisVagas.alpha = if (podeAumentar) 1.0f else 0.35f
    }

    private fun atualizarTravaConfiguracoesHost(ativoComoHost: Boolean, snap: GdroidLanVpnService.TunnelSnapshot) {
        b.btnSalaPrivada.isEnabled = !ativoComoHost
        b.btnSalaPublica.isEnabled = !ativoComoHost
        b.grupoVisibilidade.alpha = if (ativoComoHost) 0.45f else 1.0f
        b.edtNomeSalaPublica.isEnabled = !ativoComoHost
        b.layoutNomeSalaPublica.alpha = if (ativoComoHost) 0.55f else 1.0f
        b.txtLabelVisibilidade.text = if (ativoComoHost) {
            "VISIBILIDADE DA SALA (TRAVADA EM SESSÃO)"
        } else {
            "VISIBILIDADE DA SALA"
        }
        renderizarSeletorCapacidade(
            travadoEmSessao = ativoComoHost,
            totalAtivo = snap.maxTotalPlayers
        )
    }

    private fun pedirPermissaoNotificacaoSeNecessario() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // =========================================================================
    // TRÊS ABAS PRINCIPAIS: SERVIDORES PÚBLICOS | ENTRAR NA SALA | CRIAR SERVIDOR
    // =========================================================================

    private fun idBotaoDaAba(aba: AbaPrincipal): Int = when (aba) {
        AbaPrincipal.SERVIDORES_PUBLICOS -> R.id.btnModoPublicos
        AbaPrincipal.ENTRAR_SALA -> R.id.btnModoEntrar
        AbaPrincipal.CRIAR_SERVIDOR -> R.id.btnModoCriar
    }

    private fun abaPeloIdBotao(checkedId: Int): AbaPrincipal = when (checkedId) {
        R.id.btnModoEntrar -> AbaPrincipal.ENTRAR_SALA
        R.id.btnModoCriar -> AbaPrincipal.CRIAR_SERVIDOR
        else -> AbaPrincipal.SERVIDORES_PUBLICOS
    }

    private fun estilizarAbasSelecionadas() {
        val botoes = listOf(
            b.btnModoPublicos to (abaSelecionada == AbaPrincipal.SERVIDORES_PUBLICOS),
            b.btnModoEntrar to (abaSelecionada == AbaPrincipal.ENTRAR_SALA),
            b.btnModoCriar to (abaSelecionada == AbaPrincipal.CRIAR_SERVIDOR)
        )
        for ((botao, selecionado) in botoes) {
            if (selecionado) {
                botao.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2900D4FF"))
                botao.setTextColor(Color.parseColor("#00D4FF"))
                botao.strokeColor = ColorStateList.valueOf(Color.parseColor("#00D4FF"))
            } else {
                botao.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                botao.setTextColor(Color.parseColor("#E2E8F0"))
                botao.strokeColor = ColorStateList.valueOf(Color.parseColor("#2A3A52"))
            }
        }
    }

    private fun configurarAbasPrincipais() {
        val snap = GdroidLanVpnService.currentSnapshot
        val ativo = isSessaoAtiva(snap)
        abaSelecionada = if (ativo) {
            if (snap.isHost) AbaPrincipal.CRIAR_SERVIDOR else AbaPrincipal.SERVIDORES_PUBLICOS
        } else {
            AbaPrincipal.SERVIDORES_PUBLICOS
        }

        b.grupoModo.check(idBotaoDaAba(abaSelecionada))
        estilizarAbasSelecionadas()
        atualizarVisibilidadeAbas(ativo)

        b.grupoModo.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (verificandoSala) {
                b.grupoModo.check(idBotaoDaAba(abaSelecionada))
                estilizarAbasSelecionadas()
                return@addOnButtonCheckedListener
            }
            if (isSessaoAtiva()) {
                val idTravado = idBotaoDaAba(abaSelecionada)
                if (checkedId != idTravado) {
                    b.grupoModo.check(idTravado)
                    estilizarAbasSelecionadas()
                    toast("Encerre a conexão atual antes de trocar de aba.")
                }
                return@addOnButtonCheckedListener
            }

            val novaAba = abaPeloIdBotao(checkedId)
            val agora = SystemClock.elapsedRealtime()
            if (novaAba != abaSelecionada && agora - ultimoToqueAbaMs < 300L) {
                b.grupoModo.check(idBotaoDaAba(abaSelecionada))
                estilizarAbasSelecionadas()
                return@addOnButtonCheckedListener
            }
            ultimoToqueAbaMs = agora
            abaSelecionada = novaAba
            estilizarAbasSelecionadas()
            limparErroVerificacaoLocal()
            atualizarVisibilidadeAbas(ativo = false)
            renderizarEstado(GdroidLanVpnService.currentSnapshot)
        }
    }

    private fun atualizarVisibilidadeAbas(ativo: Boolean) {
        when (abaSelecionada) {
            AbaPrincipal.SERVIDORES_PUBLICOS -> {
                b.cardHost.visibility = View.GONE
                b.cardEntrarCodigo.visibility = View.GONE
                b.cardServidoresPublicos.visibility = if (ativo) View.GONE else View.VISIBLE
                b.cardIpClienteConectado.visibility = if (ativo) View.VISIBLE else View.GONE
            }
            AbaPrincipal.ENTRAR_SALA -> {
                b.cardHost.visibility = View.GONE
                b.cardServidoresPublicos.visibility = View.GONE
                b.cardEntrarCodigo.visibility = if (ativo) View.GONE else View.VISIBLE
                b.cardIpClienteConectado.visibility = if (ativo) View.VISIBLE else View.GONE
            }
            AbaPrincipal.CRIAR_SERVIDOR -> {
                b.cardServidoresPublicos.visibility = View.GONE
                b.cardEntrarCodigo.visibility = View.GONE
                b.cardIpClienteConectado.visibility = View.GONE
                b.cardHost.visibility = View.VISIBLE
            }
        }
    }

    private fun configurarBotoesPrincipais() {
        b.edtCodigoAmigo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (erroVerificacaoLocal != null) {
                    limparErroVerificacaoLocal()
                    renderizarEstado(GdroidLanVpnService.currentSnapshot)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        b.btnCopiarCodigoHost.setOnClickListener {
            copiarTexto("Código da Sala", codigoHostAtual)
            aplicarCooldownBotaoRapido(b.btnCopiarCodigoHost, 1_500L, "Copiado")
            toast("Código $codigoHostAtual copiado.")
        }

        b.btnCompartilharCodigo.setOnClickListener {
            aplicarCooldownBotaoRapido(b.btnCompartilharCodigo, 1_500L)
            val anfitriao = contaAtiva?.displayName ?: "Anfitrião"
            val msg = "Sala gLAN de $anfitriao\n" +
                "Código da Sala: $codigoHostAtual\n" +
                "IP no GDroid: ${TunnelProtocol.HOST_VIRTUAL_IP}"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, msg)
            }
            startActivity(Intent.createChooser(send, "Compartilhar sala"))
        }

        b.btnGerarNovoCodigo.setOnClickListener {
            if (isSessaoAtiva()) {
                toast("Encerre o servidor atual antes de gerar outro código.")
                return@setOnClickListener
            }
            val agora = SystemClock.elapsedRealtime()
            if (segundosRestantes(cooldownGerarCodigoAteMs, agora) > 0) return@setOnClickListener

            codigoHostAtual = RoomCrypto.generateRoomCode()
            prefs.edit().putString("host_room_code", codigoHostAtual).apply()
            b.txtCodigoHost.text = codigoHostAtual
            cooldownGerarCodigoAteMs = agora + 4_000L
            dispararTickerCooldown()
            toast("Novo código gerado: $codigoHostAtual.")
        }

        b.btnCopiarIpHost.setOnClickListener {
            copiarTexto("IP GDroid", TunnelProtocol.HOST_VIRTUAL_IP)
            aplicarCooldownBotaoRapido(b.btnCopiarIpHost, 1_500L, "Copiado")
            toast("IP ${TunnelProtocol.HOST_VIRTUAL_IP} copiado.")
        }

        b.btnCopiarIpParaJogo.setOnClickListener {
            copiarTexto("IP GDroid", TunnelProtocol.HOST_VIRTUAL_IP)
            aplicarCooldownBotaoRapido(b.btnCopiarIpParaJogo, 1_500L, "Copiado")
            toast("IP ${TunnelProtocol.HOST_VIRTUAL_IP} copiado. Cole no GDroid.")
        }

        b.btnAtualizarLobby.setOnClickListener {
            val agora = SystemClock.elapsedRealtime()
            if (verificandoSala || segundosRestantes(cooldownLobbyRefreshAteMs, agora) > 0) {
                return@setOnClickListener
            }
            cooldownLobbyRefreshAteMs = agora + 5_000L
            dispararTickerCooldown()
            lobbyBrowser.refreshNow()
            toast("Lista de servidores atualizada.")
        }

        b.btnColarCodigoAmigo.setOnClickListener {
            if (verificandoSala || isSessaoAtiva()) return@setOnClickListener
            aplicarCooldownBotaoRapido(b.btnColarCodigoAmigo, 1_200L)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val raw = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val extraido = Regex("""(?:Sala:\s*)?([A-Za-z0-9]{6})""").find(raw)?.groupValues?.getOrNull(1)
                ?: RoomCrypto.normalizeRoomCode(raw).take(10)
            val norm = RoomCrypto.normalizeRoomCode(extraido)
            if (norm.length >= 4) {
                limparErroVerificacaoLocal()
                b.edtCodigoAmigo.setText(norm)
                b.edtCodigoAmigo.setSelection(norm.length)
            } else {
                toast("Nenhum código válido encontrado para colar.")
            }
        }

        b.btnAcaoPrincipal.setOnClickListener {
            if (verificandoSala) return@setOnClickListener
            val agora = SystemClock.elapsedRealtime()
            if (segundosRestantes(cooldownPrincipalAteMs, agora) > 0) return@setOnClickListener

            if (isSessaoAtiva()) {
                iniciarCooldownPrincipal(3)
                startService(criarIntentServicoAutenticado(GdroidLanVpnService.ACTION_STOP))
                return@setOnClickListener
            }

            limparErroVerificacaoLocal()
            when (abaSelecionada) {
                AbaPrincipal.CRIAR_SERVIDOR -> {
                    if (salaPublicaSelecionada) {
                        val tituloDigitado = b.edtNomeSalaPublica.text?.toString().orEmpty()
                        if (valResSalaRecusada(tituloDigitado)) return@setOnClickListener
                    }
                    solicitarVpnEIniciar(
                        asHost = true,
                        code = codigoHostAtual,
                        protectIp = salaPublicaSelecionada && prefProtegerIpPublico,
                        expectedHostPubKeyB64 = ""
                    )
                }
                AbaPrincipal.ENTRAR_SALA -> {
                    val digitado = RoomCrypto.normalizeRoomCode(b.edtCodigoAmigo.text?.toString().orEmpty())
                    if (digitado.length < 4) {
                        exibirErroSalaLocal("CÓDIGO INVÁLIDO", "Digite um Código da Sala válido antes de conectar.")
                        return@setOnClickListener
                    }
                    prefs.edit().putString("last_friend_code", digitado).apply()
                    verificarExistenciaEEntrar(digitado)
                }
                AbaPrincipal.SERVIDORES_PUBLICOS -> { }
            }
        }

        b.btnDesbloquearRemovidos.setOnClickListener {
            val agora = SystemClock.elapsedRealtime()
            if (segundosRestantes(cooldownDesbloquearAteMs, agora) > 0) return@setOnClickListener
            mostrarDialogoBloqueados(GdroidLanVpnService.currentSnapshot)
        }

        b.btnTestarPing.setOnClickListener {
            val agora = SystemClock.elapsedRealtime()
            if (segundosRestantes(cooldownPingAteMs, agora) > 0) return@setOnClickListener
            cooldownPingAteMs = agora + 5_000L
            dispararTickerCooldown()

            startService(criarIntentServicoAutenticado(GdroidLanVpnService.ACTION_MANUAL_PING))
            toast("Testando latência da sala...")
        }
    }

    private fun mostrarDialogoBloqueados(snap: GdroidLanVpnService.TunnelSnapshot) {
        val nomes = if (snap.blockedNames.isNotEmpty()) {
            snap.blockedNames.joinToString("\n") { "• $it" }
        } else {
            "${snap.blockedCount} jogador(es) bloqueado(s) nesta sessão."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Jogadores Bloqueados na Sala")
            .setMessage("Bloqueados atualmente:\n$nomes\n\nDeseja liberar a entrada novamente nesta sessão?")
            .setPositiveButton("Desbloquear Todos") { _, _ ->
                cooldownDesbloquearAteMs = SystemClock.elapsedRealtime() + 3_000L
                dispararTickerCooldown()
                startService(criarIntentServicoAutenticado(GdroidLanVpnService.ACTION_CLEAR_BLOCKS))
                toast("Bloqueios da sala removidos.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun verificarExistenciaEEntrar(
        codigoNormalizado: String,
        entradaSalaPublica: PublicLobbyBrowser.PublicRoomEntry? = null
    ) {
        if (verificandoSala || isSessaoAtiva()) return
        val agora = SystemClock.elapsedRealtime()
        if (segundosRestantes(cooldownPrincipalAteMs, agora) > 0) return

        verificandoSala = true
        limparErroVerificacaoLocal()
        renderizarEstado(GdroidLanVpnService.currentSnapshot)

        val salaConhecida = entradaSalaPublica ?: lobbyBrowser.findKnownPublicRoom(codigoNormalizado)
        val chaveHostConhecida = salaConhecida?.ownerPubKeyB64.orEmpty()
        val uid = contaAtiva?.accountUid ?: 0L
        lobbyBrowser.probeRoom(
            rawCode = codigoNormalizado,
            accountUid = uid,
            expectedHostPubKeyB64 = chaveHostConhecida.ifBlank { null }
        ) { resultado ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                verificandoSala = false

                when (resultado) {
                    is PublicLobbyBrowser.ProbeResult.Available -> {
                        limparErroVerificacaoLocal()
                        val salaAtivaAgora = entradaSalaPublica ?: lobbyBrowser.findKnownPublicRoom(codigoNormalizado) ?: salaConhecida
                        val ehSalaPublica = (salaAtivaAgora != null)
                        val protegerIpSessao = (prefProtegerIpPublico && ehSalaPublica) ||
                            (salaAtivaAgora?.ipProtected == true)
                        solicitarVpnEIniciar(
                            asHost = false,
                            code = codigoNormalizado,
                            protectIp = protegerIpSessao,
                            expectedHostPubKeyB64 = salaAtivaAgora?.ownerPubKeyB64 ?: chaveHostConhecida
                        )
                    }
                    is PublicLobbyBrowser.ProbeResult.NotFound -> {
                        iniciarCooldownPrincipal(3)
                        exibirErroSalaLocal(
                            badge = "SALA INEXISTENTE",
                            mensagem = "Essa sala não existe ou já foi encerrada."
                        )
                    }
                    is PublicLobbyBrowser.ProbeResult.Full -> {
                        iniciarCooldownPrincipal(3)
                        exibirErroSalaLocal(
                            badge = "SALA LOTADA",
                            mensagem = "Essa sala está lotada (${resultado.current}/${resultado.max} jogadores)."
                        )
                    }
                    is PublicLobbyBrowser.ProbeResult.Blocked -> {
                        iniciarCooldownPrincipal(3)
                        exibirErroSalaLocal(
                            badge = "BLOQUEADO",
                            mensagem = "Você foi expulso e bloqueado nesta sala pelo anfitrião."
                        )
                    }
                    is PublicLobbyBrowser.ProbeResult.Offline -> {
                        iniciarCooldownPrincipal(3)
                        exibirErroSalaLocal(
                            badge = "SEM CONEXÃO",
                            mensagem = "Sem conexão com a rede para localizar a sala. Verifique sua internet."
                        )
                    }
                    is PublicLobbyBrowser.ProbeResult.Aborted -> {
                        renderizarEstado(GdroidLanVpnService.currentSnapshot)
                    }
                }
            }
        }
    }

    private fun limparErroVerificacaoLocal() {
        badgeErroLocal = null
        erroVerificacaoLocal = null
    }

    private fun exibirErroSalaLocal(badge: String, mensagem: String) {
        badgeErroLocal = badge
        erroVerificacaoLocal = mensagem
        toast(mensagem)
        renderizarEstado(GdroidLanVpnService.currentSnapshot)
    }

    private fun renderizarSalasPublicas(
        salas: List<PublicLobbyBrowser.PublicRoomEntry>,
        conectadoAoDiretorio: Boolean
    ) {
        b.txtStatusLobby.text = when {
            salas.isNotEmpty() -> if (salas.size == 1) "1 aberto" else "${salas.size} abertos"
            conectadoAoDiretorio -> "Ao vivo"
            else -> "Sincronizando"
        }

        val termoBusca = normalizarParaBusca(filtroPesquisaServidor)
        val salasFiltradas = if (termoBusca.isBlank()) {
            salas
        } else {
            salas.filter { s ->
                normalizarParaBusca(s.roomTitle).contains(termoBusca) ||
                    normalizarParaBusca(s.hostName).contains(termoBusca)
            }
        }

        b.containerSalasPublicas.removeAllViews()
        if (salasFiltradas.isEmpty()) {
            b.txtSemSalasPublicas.visibility = View.VISIBLE
            b.txtSemSalasPublicas.text = when {
                salas.isNotEmpty() && termoBusca.isNotBlank() ->
                    "Nenhum servidor encontrado para \"${filtroPesquisaServidor.trim()}\"."
                conectadoAoDiretorio ->
                    "Nenhum servidor público aberto no momento."
                else ->
                    "Buscando servidores públicos abertos..."
            }
            return
        }

        val bloqueadoPorCooldownOuBusca = verificandoSala ||
            segundosRestantes(cooldownPrincipalAteMs) > 0 ||
            isSessaoAtiva()

        b.txtSemSalasPublicas.visibility = View.GONE
        for (sala in salasFiltradas) {
            val item = ItemPublicRoomBinding.inflate(layoutInflater, b.containerSalasPublicas, false)
            item.txtTituloSalaPublica.text = sala.roomTitle
            item.txtSubtituloSalaPublica.text = if (sala.ipProtected) {
                "Host: ${sala.hostName} • IP Oculto • ${TunnelProtocol.HOST_VIRTUAL_IP}"
            } else {
                "Host: ${sala.hostName} • IP: ${TunnelProtocol.HOST_VIRTUAL_IP}"
            }
            item.txtVagasSalaPublica.text = "${sala.currentPlayers} / ${sala.maxPlayers}"

            if (prefPingLobby) {
                item.txtPingSalaPublica.visibility = View.VISIBLE
                if (sala.pingMs > 0L) {
                    item.txtPingSalaPublica.text = "${sala.pingMs} ms"
                    item.txtPingSalaPublica.setTextColor(corPorPing(sala.pingMs))
                } else {
                    item.txtPingSalaPublica.text = "-- ms"
                    item.txtPingSalaPublica.setTextColor(Color.parseColor("#94A3B8"))
                }
            } else {
                item.txtPingSalaPublica.visibility = View.GONE
            }

            if (sala.isFull) {
                item.viewDotSala.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFCA28"))
                item.btnEntrarSalaPublica.text = "Lotada"
                item.btnEntrarSalaPublica.isEnabled = false
                item.btnEntrarSalaPublica.alpha = 0.5f
            } else {
                val corDot = if (prefPingLobby && sala.pingMs > 0L) corPorPing(sala.pingMs) else Color.parseColor("#00E676")
                item.viewDotSala.backgroundTintList = ColorStateList.valueOf(corDot)
                item.btnEntrarSalaPublica.text = "Entrar"
                item.btnEntrarSalaPublica.isEnabled = !bloqueadoPorCooldownOuBusca
                item.btnEntrarSalaPublica.alpha = if (bloqueadoPorCooldownOuBusca) 0.45f else 1.0f
                item.btnEntrarSalaPublica.setOnClickListener {
                    verificarExistenciaEEntrar(sala.roomCode, entradaSalaPublica = sala)
                }
            }

            b.containerSalasPublicas.addView(item.root)
        }
    }

    private fun solicitarVpnEIniciar(
        asHost: Boolean,
        code: String,
        protectIp: Boolean,
        expectedHostPubKeyB64: String
    ) {
        aguardandoPermissaoParaHost = asHost
        codigoPendenteConexao = code
        protegerIpPendenteConexao = protectIp
        pubKeyHostEsperadaPendente = expectedHostPubKeyB64
        val prep = VpnService.prepare(this)
        if (prep != null) {
            vpnPermissionLauncher.launch(prep)
        } else {
            iniciarCooldownPrincipal(3)
            dispararServicoVpn(
                asHost = asHost,
                code = code,
                protectIp = protectIp,
                expectedHostPubKeyB64 = expectedHostPubKeyB64
            )
        }
    }

    private fun dispararServicoVpn(
        asHost: Boolean,
        code: String,
        protectIp: Boolean,
        expectedHostPubKeyB64: String
    ) {
        lobbyBrowser.stop()
        ultimoTotalBytes = 0L
        ultimoTimestampBytesMs = 0L
        ultimaTaxaBytesPorSeg = 0L

        val conta = contaAtiva
        val acao = if (asHost) GdroidLanVpnService.ACTION_START_HOST else GdroidLanVpnService.ACTION_START_CLIENT
        val i = criarIntentServicoAutenticado(acao).apply {
            putExtra(GdroidLanVpnService.EXTRA_ROOM_CODE, code)
            putExtra(GdroidLanVpnService.EXTRA_PLAYER_NAME, conta?.displayName ?: "Jogador")
            putExtra(GdroidLanVpnService.EXTRA_ACCOUNT_UID, conta?.accountUid ?: 0L)
            putExtra(GdroidLanVpnService.EXTRA_MAX_PLAYERS, limiteJogadores)
            putExtra(GdroidLanVpnService.EXTRA_IS_PUBLIC, asHost && salaPublicaSelecionada)
            putExtra(GdroidLanVpnService.EXTRA_ROOM_TITLE, obterTituloSalaAtual())
            putExtra(GdroidLanVpnService.EXTRA_AUTO_RECONNECT, prefReconectarAuto)
            putExtra(GdroidLanVpnService.EXTRA_PROTECT_IP, protectIp)
            if (!asHost && expectedHostPubKeyB64.isNotBlank()) {
                putExtra(GdroidLanVpnService.EXTRA_EXPECTED_HOST_PUBKEY, expectedHostPubKeyB64)
            }
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun confirmarExpulsaoParticipante(
        membro: TunnelProtocol.RoomMemberInfo,
        botaoLinha: MaterialButton
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Expulsar Jogador")
            .setMessage("Deseja expulsar ${membro.displayName} (${membro.virtualIp}) desta sala?")
            .setPositiveButton("Expulsar e Bloquear") { _, _ ->
                botaoLinha.isEnabled = false
                botaoLinha.alpha = 0.4f
                enviarComandoKick(membro.nodeId, bloquearSessao = true)
            }
            .setNeutralButton("Apenas Expulsar") { _, _ ->
                botaoLinha.isEnabled = false
                botaoLinha.alpha = 0.4f
                enviarComandoKick(membro.nodeId, bloquearSessao = false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarComandoKick(nodeId: Int, bloquearSessao: Boolean) {
        val kickIntent = criarIntentServicoAutenticado(GdroidLanVpnService.ACTION_KICK_PEER).apply {
            putExtra(GdroidLanVpnService.EXTRA_TARGET_NODE_ID, nodeId)
            putExtra(GdroidLanVpnService.EXTRA_BLOCK_SESSION, bloquearSessao)
        }
        startService(kickIntent)
    }

    private fun atualizarBotaoPrincipalESalas(
        snap: GdroidLanVpnService.TunnelSnapshot,
        agora: Long = SystemClock.elapsedRealtime()
    ) {
        val ativo = isSessaoAtiva(snap)
        val restPrincipal = segundosRestantes(cooldownPrincipalAteMs, agora)

        if (!ativo && abaSelecionada == AbaPrincipal.SERVIDORES_PUBLICOS && !verificandoSala && restPrincipal == 0) {
            b.btnAcaoPrincipal.visibility = View.GONE
        } else {
            b.btnAcaoPrincipal.visibility = View.VISIBLE
            when {
                verificandoSala -> {
                    b.btnAcaoPrincipal.isEnabled = false
                    b.btnAcaoPrincipal.alpha = 0.7f
                    b.btnAcaoPrincipal.text = "VERIFICANDO SALA..."
                    b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00A8E8"))
                    b.btnAcaoPrincipal.setTextColor(Color.WHITE)
                }
                restPrincipal > 0 -> {
                    b.btnAcaoPrincipal.isEnabled = false
                    b.btnAcaoPrincipal.alpha = 0.6f
                    b.btnAcaoPrincipal.text = if (ativo) {
                        if (snap.isHost) "SERVIDOR ATIVO (${restPrincipal}s)" else "CONECTADO (${restPrincipal}s)"
                    } else {
                        "AGUARDE ${restPrincipal}s..."
                    }
                    val cor = if (ativo) "#FF5252" else if (abaSelecionada == AbaPrincipal.CRIAR_SERVIDOR) "#1890FF" else "#00A8E8"
                    b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor(cor))
                    b.btnAcaoPrincipal.setTextColor(Color.WHITE)
                }
                ativo -> {
                    b.btnAcaoPrincipal.isEnabled = true
                    b.btnAcaoPrincipal.alpha = 1.0f
                    b.btnAcaoPrincipal.text = if (snap.isHost) "ENCERRAR SERVIDOR" else "SAIR DA SALA"
                    b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
                    b.btnAcaoPrincipal.setTextColor(Color.WHITE)
                }
                else -> {
                    b.btnAcaoPrincipal.isEnabled = true
                    b.btnAcaoPrincipal.alpha = 1.0f
                    val ehCriar = (abaSelecionada == AbaPrincipal.CRIAR_SERVIDOR)
                    b.btnAcaoPrincipal.text = if (ehCriar) "INICIAR SERVIDOR" else "CONECTAR NA SALA"
                    val cor = if (ehCriar) "#1890FF" else "#00A8E8"
                    b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor(cor))
                    b.btnAcaoPrincipal.setTextColor(Color.WHITE)
                }
            }
        }

        if (!ativo && abaSelecionada == AbaPrincipal.SERVIDORES_PUBLICOS) {
            renderizarSalasPublicas(cacheSalasPublicas, cacheDiretorioConectado)
        }
    }

    private fun calcularTextoTrafego(totalBytes: Long): String {
        val agoraMs = SystemClock.elapsedRealtime()
        if (ultimoTimestampBytesMs > 0L && totalBytes >= ultimoTotalBytes) {
            val dtMs = agoraMs - ultimoTimestampBytesMs
            if (dtMs >= 600L) {
                ultimaTaxaBytesPorSeg = ((totalBytes - ultimoTotalBytes) * 1000L) / dtMs
                ultimoTotalBytes = totalBytes
                ultimoTimestampBytesMs = agoraMs
            }
        } else {
            ultimoTotalBytes = totalBytes
            ultimoTimestampBytesMs = agoraMs
            ultimaTaxaBytesPorSeg = 0L
        }

        val totalFmt = formatarBytes(totalBytes)
        return if (ultimaTaxaBytesPorSeg >= 1024L) {
            "$totalFmt (${ultimaTaxaBytesPorSeg / 1024L} KB/s)"
        } else {
            totalFmt
        }
    }

    private fun renderizarEstado(snap: GdroidLanVpnService.TunnelSnapshot) {
        val ativo = isSessaoAtiva(snap)
        if (ativo) {
            limparErroVerificacaoLocal()
        } else {
            ultimoTotalBytes = 0L
            ultimoTimestampBytesMs = 0L
            ultimaTaxaBytesPorSeg = 0L
        }

        gerenciarLobbyBrowserConformeEstado(snap)

        if (ativo) {
            if (snap.isHost && abaSelecionada != AbaPrincipal.CRIAR_SERVIDOR) {
                abaSelecionada = AbaPrincipal.CRIAR_SERVIDOR
                b.grupoModo.check(R.id.btnModoCriar)
                estilizarAbasSelecionadas()
            } else if (!snap.isHost && abaSelecionada == AbaPrincipal.CRIAR_SERVIDOR) {
                abaSelecionada = AbaPrincipal.SERVIDORES_PUBLICOS
                b.grupoModo.check(R.id.btnModoPublicos)
                estilizarAbasSelecionadas()
            }
        }

        atualizarVisibilidadeAbas(ativo)
        atualizarTravaConfiguracoesHost(ativoComoHost = ativo && snap.isHost, snap = snap)

        val erroLocal = erroVerificacaoLocal
        val badgeLocal = badgeErroLocal

        if (!ativo && erroLocal != null && badgeLocal != null) {
            b.txtBadgeStatus.text = badgeLocal
            b.txtBadgeStatus.setTextColor(Color.parseColor("#FF5252"))
            b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#26FF5252"))

            b.txtDetalheStatus.text = erroLocal
            b.txtDetalheStatus.setTextColor(Color.parseColor("#FF5252"))
            b.txtDetalheStatus.visibility = View.VISIBLE
        } else {
            when (snap.phase) {
                GdroidLanVpnService.StatusPhase.OFFLINE -> {
                    b.txtBadgeStatus.text = "OFFLINE"
                    b.txtBadgeStatus.setTextColor(Color.parseColor("#94A3B8"))
                    b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#2694A3B8"))
                }
                GdroidLanVpnService.StatusPhase.CONNECTING -> {
                    b.txtBadgeStatus.text = "CONECTANDO"
                    b.txtBadgeStatus.setTextColor(Color.parseColor("#FFCA28"))
                    b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#26FFCA28"))
                }
                GdroidLanVpnService.StatusPhase.WAITING_FRIEND -> {
                    b.txtBadgeStatus.text = if (snap.isPublicRoom) "PÚBLICA" else "PRIVADA"
                    b.txtBadgeStatus.setTextColor(Color.parseColor("#00D4FF"))
                    b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#2600D4FF"))
                }
                GdroidLanVpnService.StatusPhase.ONLINE -> {
                    b.txtBadgeStatus.text = "CONECTADO"
                    b.txtBadgeStatus.setTextColor(Color.parseColor("#00E676"))
                    b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#2600E676"))
                }
                GdroidLanVpnService.StatusPhase.RECONNECTING -> {
                    b.txtBadgeStatus.text = "RECONECTANDO"
                    b.txtBadgeStatus.setTextColor(Color.parseColor("#FFCA28"))
                    b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#26FFCA28"))
                }
                GdroidLanVpnService.StatusPhase.ERROR -> {
                    b.txtBadgeStatus.text = "ERRO"
                    b.txtBadgeStatus.setTextColor(Color.parseColor("#FF5252"))
                    b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#26FF5252"))
                }
            }

            if (snap.statusText.isNotBlank() && snap.phase != GdroidLanVpnService.StatusPhase.OFFLINE) {
                b.txtDetalheStatus.text = snap.statusText
                b.txtDetalheStatus.setTextColor(
                    when (snap.phase) {
                        GdroidLanVpnService.StatusPhase.ERROR -> Color.parseColor("#FF5252")
                        GdroidLanVpnService.StatusPhase.RECONNECTING -> Color.parseColor("#FFCA28")
                        else -> Color.parseColor("#94A3B8")
                    }
                )
                b.txtDetalheStatus.visibility = View.VISIBLE
            } else {
                b.txtDetalheStatus.visibility = View.GONE
            }
        }

        b.painelSessaoAtiva.visibility = if (ativo) View.VISIBLE else View.GONE
        b.cardEstatisticasRede.visibility = if (ativo && prefMostrarTrafego) View.VISIBLE else View.GONE
        b.cardComoUsar.visibility = if (ativo) View.GONE else View.VISIBLE
        b.btnGerarNovoCodigo.visibility = if (ativo) View.INVISIBLE else View.VISIBLE

        if (ativo) {
            b.txtMeuIpVirtual.text = snap.myVirtualIp
            if (snap.phase == GdroidLanVpnService.StatusPhase.RECONNECTING) {
                b.txtPingRota.text = "Reconectando..."
                b.txtPingRota.setTextColor(Color.parseColor("#FFCA28"))
            } else if (snap.pingMs > 0L) {
                b.txtPingRota.text = "${snap.pingMs} ms (${snap.routeMode})"
                b.txtPingRota.setTextColor(corPorPing(snap.pingMs))
            } else {
                b.txtPingRota.text = snap.routeMode
                b.txtPingRota.setTextColor(Color.parseColor("#00D4FF"))
            }
            b.txtTrafego.text = calcularTextoTrafego(snap.txBytes + snap.rxBytes)
            b.txtContadorSala.text = "${snap.totalPlayersInRoom} / ${snap.maxTotalPlayers}"
            if (segundosRestantes(cooldownPingAteMs) == 0) {
                b.btnTestarPing.isEnabled = (snap.phase == GdroidLanVpnService.StatusPhase.ONLINE)
                b.btnTestarPing.text = "Testar Ping da Sala"
            }

            if (snap.isHost && snap.blockedCount > 0) {
                b.btnDesbloquearRemovidos.visibility = View.VISIBLE
                b.btnDesbloquearRemovidos.text = "Bloqueados (${snap.blockedCount})"
            } else {
                b.btnDesbloquearRemovidos.visibility = View.GONE
            }

            renderizarListaParticipantes(snap)
        }

        b.edtPesquisaServidor.isEnabled = !ativo
        b.edtCodigoAmigo.isEnabled = !ativo && !verificandoSala
        b.btnColarCodigoAmigo.isEnabled = !ativo && !verificandoSala
        b.btnModoPublicos.isEnabled = !verificandoSala
        b.btnModoEntrar.isEnabled = !verificandoSala
        b.btnModoCriar.isEnabled = !verificandoSala

        atualizarBotaoPrincipalESalas(snap)
    }

    private fun renderizarListaParticipantes(snap: GdroidLanVpnService.TunnelSnapshot) {
        b.containerParticipantes.removeAllViews()
        if (snap.members.isEmpty()) {
            b.txtSemParticipantes.visibility = View.VISIBLE
            b.txtSemParticipantes.text = "Sincronizando sala..."
            return
        }

        b.txtSemParticipantes.visibility = View.GONE
        for (membro in snap.members) {
            val row = ItemPeerRowBinding.inflate(layoutInflater, b.containerParticipantes, false)
            row.txtNomeParticipante.text = membro.displayName

            when {
                membro.isHost -> {
                    row.txtCargoParticipante.text = if (snap.isHost) "VOCÊ" else "HOST"
                    row.txtCargoParticipante.setTextColor(Color.parseColor("#00E676"))
                    row.txtCargoParticipante.setBackgroundColor(Color.parseColor("#2600E676"))
                }
                !snap.isHost && membro.nodeId == snap.myNodeId -> {
                    row.txtCargoParticipante.text = "VOCÊ"
                    row.txtCargoParticipante.setTextColor(Color.parseColor("#00D4FF"))
                    row.txtCargoParticipante.setBackgroundColor(Color.parseColor("#2600D4FF"))
                }
                else -> {
                    row.txtCargoParticipante.text = "JOGADOR"
                    row.txtCargoParticipante.setTextColor(Color.parseColor("#00D4FF"))
                    row.txtCargoParticipante.setBackgroundColor(Color.parseColor("#2600D4FF"))
                }
            }

            val rotaExibida = if (membro.isHost && snap.isHost) "Servidor" else membro.routeLabel
            row.txtInfoRedeParticipante.text = "${membro.virtualIp} • $rotaExibida"

            when {
                membro.isHost && snap.isHost -> {
                    row.txtPingParticipante.text = "0 ms"
                    row.txtPingParticipante.setTextColor(Color.parseColor("#00E676"))
                    row.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                }
                membro.routeLabel == "Reconectando" -> {
                    row.txtPingParticipante.text = "..."
                    row.txtPingParticipante.setTextColor(Color.parseColor("#FFCA28"))
                    row.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFCA28"))
                }
                membro.pingMs > 0L -> {
                    val cor = corPorPing(membro.pingMs)
                    row.txtPingParticipante.text = "${membro.pingMs} ms"
                    row.txtPingParticipante.setTextColor(cor)
                    row.viewStatusDot.backgroundTintList = ColorStateList.valueOf(cor)
                }
                else -> {
                    row.txtPingParticipante.text = "-- ms"
                    row.txtPingParticipante.setTextColor(Color.parseColor("#94A3B8"))
                    row.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                }
            }

            if (snap.isHost && !membro.isHost) {
                row.btnRemoverParticipante.visibility = View.VISIBLE
                row.btnRemoverParticipante.setOnClickListener {
                    confirmarExpulsaoParticipante(membro, row.btnRemoverParticipante)
                }
            } else {
                row.btnRemoverParticipante.visibility = View.GONE
            }

            b.containerParticipantes.addView(row.root)
        }
    }

    private fun formatarBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
            else -> String.format("%.1f MB", bytes / (1024f * 1024f))
        }
    }

    private fun copiarTexto(label: String, valor: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, valor))
    }

    private fun toast(msg: String) {
        toastAtual?.cancel()
        toastAtual = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }
}
