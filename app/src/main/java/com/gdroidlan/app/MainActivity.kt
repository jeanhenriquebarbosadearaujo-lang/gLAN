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
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gdroidlan.app.auth.AccountManagerStore
import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.databinding.ActivityMainBinding
import com.gdroidlan.app.databinding.ItemPeerRowBinding
import com.gdroidlan.app.databinding.ItemPublicRoomBinding
import com.gdroidlan.app.net.PublicLobbyBrowser
import com.gdroidlan.app.vpn.GdroidLanVpnService
import com.gdroidlan.app.vpn.TunnelProtocol
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("gdroid_lan_prefs", Context.MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var toastAtual: Toast? = null

    private var contaAtiva: AccountManagerStore.UserAccount? = null
    private var modoHostSelecionado = true
    private var salaPublicaSelecionada = false
    private var codigoHostAtual = ""
    private var limiteJogadores = TunnelProtocol.MAX_TOTAL_PLAYERS

    private var aguardandoPermissaoParaHost = true
    private var codigoPendenteConexao = ""

    // Estado de verificação prévia da sala (evita entrar em "CONECTANDO" quando a sala não existe)
    private var verificandoSala = false
    private var badgeErroLocal: String? = null
    private var erroVerificacaoLocal: String? = null
    private var cacheSalasPublicas: List<PublicLobbyBrowser.PublicRoomEntry> = emptyList()
    private var cacheDiretorioConectado: Boolean = false

    // Cooldowns anti-spam (SystemClock.elapsedRealtime)
    private var cooldownPrincipalAteMs = 0L
    private var cooldownGerarCodigoAteMs = 0L
    private var cooldownPingAteMs = 0L
    private var cooldownLobbyRefreshAteMs = 0L
    private var cooldownDesbloquearAteMs = 0L
    private var ultimoToqueAbaMs = 0L

    private val tickerCooldownRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val agora = SystemClock.elapsedRealtime()
            var precisaContinuar = false

            // 1. Cooldown de "Gerar outro" código
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

            // 2. Cooldown de "Atualizar" lista de salas públicas
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

            // 3. Cooldown de "Testar Ping da Sala"
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

            // 4. Cooldown de "Desbloquear" removidos
            val restDesbloq = segundosRestantes(cooldownDesbloquearAteMs, agora)
            if (restDesbloq > 0) {
                b.btnDesbloquearRemovidos.isEnabled = false
                b.btnDesbloquearRemovidos.alpha = 0.5f
                precisaContinuar = true
            } else {
                b.btnDesbloquearRemovidos.isEnabled = true
                b.btnDesbloquearRemovidos.alpha = 1.0f
            }

            // 5. Cooldown da ação principal (Iniciar/Encerrar Servidor e Entrar/Sair da Sala)
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
        PublicLobbyBrowser { salas, conectado ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    cacheSalasPublicas = salas
                    cacheDiretorioConectado = conectado
                    renderizarSalasPublicas(salas, conectado)
                }
            }
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                iniciarCooldownPrincipal(3)
                dispararServicoVpn(aguardandoPermissaoParaHost, codigoPendenteConexao)
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

        pedirPermissaoNotificacaoSeNecessario()
        b.txtContaTopo.text = "Conta: ${conta.displayName}"

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

        configurarVisibilidadeHost()
        configurarAbasModo()
        configurarSeletorCapacidade()
        configurarBotoesPrincipais()

        GdroidLanVpnService.onManualPongToast = { rtt, modo ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast("Ping da sala: ${rtt} ms ($modo).")
                }
            }
        }

        GdroidLanVpnService.onModerationEventToast = { msg ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast(msg)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        GdroidLanVpnService.addListener(stateListener)
        gerenciarLobbyBrowserConformeEstado(GdroidLanVpnService.currentSnapshot)
        dispararTickerCooldown()
    }

    override fun onStop() {
        lobbyBrowser.stop()
        GdroidLanVpnService.removeListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        toastAtual?.cancel()
        super.onDestroy()
    }

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
            lobbyBrowser.start()
        }
    }

    private fun configurarVisibilidadeHost() {
        b.grupoVisibilidade.check(if (salaPublicaSelecionada) R.id.btnSalaPublica else R.id.btnSalaPrivada)
        b.layoutNomeSalaPublica.visibility = if (salaPublicaSelecionada) View.VISIBLE else View.GONE

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
        }

        b.edtNomeSalaPublica.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSessaoAtiva()) return
                val txt = s?.toString()?.trim().orEmpty()
                prefs.edit().putString("host_room_title", txt).apply()
            }
        })
    }

    private fun obterTituloSalaAtual(): String {
        val digitado = b.edtNomeSalaPublica.text?.toString()?.trim().orEmpty()
        if (digitado.isNotBlank()) return digitado.take(28)
        val nomeConta = contaAtiva?.displayName ?: "Jogador"
        return "Sala de $nomeConta".take(28)
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

    private fun configurarAbasModo() {
        val snap = GdroidLanVpnService.currentSnapshot
        val ativo = isSessaoAtiva(snap)
        modoHostSelecionado = if (ativo) snap.isHost else true

        b.grupoModo.check(if (modoHostSelecionado) R.id.btnModoHost else R.id.btnModoCliente)
        atualizarVisibilidadeModo(ativo)

        b.grupoModo.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (verificandoSala) {
                b.grupoModo.check(if (modoHostSelecionado) R.id.btnModoHost else R.id.btnModoCliente)
                return@addOnButtonCheckedListener
            }
            if (isSessaoAtiva()) {
                val idCorreto = if (GdroidLanVpnService.currentSnapshot.isHost) R.id.btnModoHost else R.id.btnModoCliente
                if (checkedId != idCorreto) {
                    b.grupoModo.check(idCorreto)
                    toast("Encerre a conexão atual antes de trocar de aba.")
                }
                return@addOnButtonCheckedListener
            }
            val agora = SystemClock.elapsedRealtime()
            val novoModoHost = (checkedId == R.id.btnModoHost)
            if (novoModoHost != modoHostSelecionado && agora - ultimoToqueAbaMs < 350L) {
                b.grupoModo.check(if (modoHostSelecionado) R.id.btnModoHost else R.id.btnModoCliente)
                return@addOnButtonCheckedListener
            }
            ultimoToqueAbaMs = agora
            modoHostSelecionado = novoModoHost
            limparErroVerificacaoLocal()
            atualizarVisibilidadeModo(ativo = false)
            renderizarEstado(GdroidLanVpnService.currentSnapshot)
        }
    }

    private fun atualizarVisibilidadeModo(ativo: Boolean) {
        if (modoHostSelecionado) {
            b.cardHost.visibility = View.VISIBLE
            b.secaoBuscaCliente.visibility = View.GONE
            b.cardIpClienteConectado.visibility = View.GONE
        } else {
            b.cardHost.visibility = View.GONE
            b.secaoBuscaCliente.visibility = if (ativo) View.GONE else View.VISIBLE
            b.cardIpClienteConectado.visibility = if (ativo) View.VISIBLE else View.GONE
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
            toast("Lista de salas públicas atualizada.")
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
                val stop = Intent(this, GdroidLanVpnService::class.java).apply {
                    action = GdroidLanVpnService.ACTION_STOP
                }
                startService(stop)
                return@setOnClickListener
            }

            limparErroVerificacaoLocal()
            if (modoHostSelecionado) {
                solicitarVpnEIniciar(asHost = true, code = codigoHostAtual)
            } else {
                val digitado = RoomCrypto.normalizeRoomCode(b.edtCodigoAmigo.text?.toString().orEmpty())
                if (digitado.length < 4) {
                    exibirErroSalaLocal("CÓDIGO INVÁLIDO", "Digite um Código Privado válido ou escolha uma Sala Pública aberta.")
                    return@setOnClickListener
                }
                prefs.edit().putString("last_friend_code", digitado).apply()
                verificarExistenciaEEntrar(digitado)
            }
        }

        b.btnDesbloquearRemovidos.setOnClickListener {
            val agora = SystemClock.elapsedRealtime()
            if (segundosRestantes(cooldownDesbloquearAteMs, agora) > 0) return@setOnClickListener
            cooldownDesbloquearAteMs = agora + 3_000L
            dispararTickerCooldown()

            val clearIntent = Intent(this, GdroidLanVpnService::class.java).apply {
                action = GdroidLanVpnService.ACTION_CLEAR_BLOCKS
            }
            startService(clearIntent)
            toast("Lista de bloqueios da sala limpa.")
        }

        b.btnTestarPing.setOnClickListener {
            val agora = SystemClock.elapsedRealtime()
            if (segundosRestantes(cooldownPingAteMs, agora) > 0) return@setOnClickListener
            cooldownPingAteMs = agora + 5_000L
            dispararTickerCooldown()

            val pingIntent = Intent(this, GdroidLanVpnService::class.java).apply {
                action = GdroidLanVpnService.ACTION_MANUAL_PING
            }
            startService(pingIntent)
            toast("Testando latência da sala...")
        }
    }

    /**
     * Verifica em tempo real se a sala realmente existe antes de entrar em modo VPN/CONECTANDO.
     * Se não existir, exibe imediatamente erro de sala inexistente sem ficar conectando.
     */
    private fun verificarExistenciaEEntrar(codigoNormalizado: String) {
        if (verificandoSala || isSessaoAtiva()) return
        val agora = SystemClock.elapsedRealtime()
        if (segundosRestantes(cooldownPrincipalAteMs, agora) > 0) return

        verificandoSala = true
        limparErroVerificacaoLocal()
        renderizarEstado(GdroidLanVpnService.currentSnapshot)

        val uid = contaAtiva?.accountUid ?: 0L
        lobbyBrowser.probeRoom(codigoNormalizado, uid) { resultado ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                verificandoSala = false

                when (resultado) {
                    is PublicLobbyBrowser.ProbeResult.Available -> {
                        limparErroVerificacaoLocal()
                        solicitarVpnEIniciar(asHost = false, code = codigoNormalizado)
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
                            mensagem = "Você foi bloqueado nesta sala pelo anfitrião."
                        )
                    }
                    is PublicLobbyBrowser.ProbeResult.Offline -> {
                        iniciarCooldownPrincipal(3)
                        exibirErroSalaLocal(
                            badge = "SEM CONEXÃO",
                            mensagem = "Sem conexão com a rede para localizar a sala. Verifique sua internet."
                        )
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
            salas.isNotEmpty() -> if (salas.size == 1) "1 aberta" else "${salas.size} abertas"
            conectadoAoDiretorio -> "Ao vivo"
            else -> "Sincronizando"
        }

        b.containerSalasPublicas.removeAllViews()
        if (salas.isEmpty()) {
            b.txtSemSalasPublicas.visibility = View.VISIBLE
            b.txtSemSalasPublicas.text = if (conectadoAoDiretorio) {
                "Nenhuma sala pública aberta no momento."
            } else {
                "Buscando salas públicas abertas..."
            }
            return
        }

        val bloqueadoPorCooldownOuBusca = verificandoSala ||
            segundosRestantes(cooldownPrincipalAteMs) > 0 ||
            isSessaoAtiva()

        b.txtSemSalasPublicas.visibility = View.GONE
        for (sala in salas) {
            val item = ItemPublicRoomBinding.inflate(layoutInflater, b.containerSalasPublicas, false)
            item.txtTituloSalaPublica.text = sala.roomTitle
            item.txtSubtituloSalaPublica.text = "Host: ${sala.hostName} • IP: ${TunnelProtocol.HOST_VIRTUAL_IP}"
            item.txtVagasSalaPublica.text = "${sala.currentPlayers} / ${sala.maxPlayers}"

            if (sala.isFull) {
                item.viewDotSala.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFCA28"))
                item.btnEntrarSalaPublica.text = "Lotada"
                item.btnEntrarSalaPublica.isEnabled = false
                item.btnEntrarSalaPublica.alpha = 0.5f
            } else {
                item.viewDotSala.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                item.btnEntrarSalaPublica.text = "Entrar"
                item.btnEntrarSalaPublica.isEnabled = !bloqueadoPorCooldownOuBusca
                item.btnEntrarSalaPublica.alpha = if (bloqueadoPorCooldownOuBusca) 0.45f else 1.0f
                item.btnEntrarSalaPublica.setOnClickListener {
                    verificarExistenciaEEntrar(sala.roomCode)
                }
            }

            b.containerSalasPublicas.addView(item.root)
        }
    }

    private fun solicitarVpnEIniciar(asHost: Boolean, code: String) {
        aguardandoPermissaoParaHost = asHost
        codigoPendenteConexao = code
        val prep = VpnService.prepare(this)
        if (prep != null) {
            vpnPermissionLauncher.launch(prep)
        } else {
            iniciarCooldownPrincipal(3)
            dispararServicoVpn(asHost, code)
        }
    }

    private fun dispararServicoVpn(asHost: Boolean, code: String) {
        lobbyBrowser.stop()
        val conta = contaAtiva
        val i = Intent(this, GdroidLanVpnService::class.java).apply {
            action = if (asHost) GdroidLanVpnService.ACTION_START_HOST else GdroidLanVpnService.ACTION_START_CLIENT
            putExtra(GdroidLanVpnService.EXTRA_ROOM_CODE, code)
            putExtra(GdroidLanVpnService.EXTRA_PLAYER_NAME, conta?.displayName ?: "Jogador")
            putExtra(GdroidLanVpnService.EXTRA_ACCOUNT_UID, conta?.accountUid ?: 0L)
            putExtra(GdroidLanVpnService.EXTRA_MAX_PLAYERS, limiteJogadores)
            putExtra(GdroidLanVpnService.EXTRA_IS_PUBLIC, asHost && salaPublicaSelecionada)
            putExtra(GdroidLanVpnService.EXTRA_ROOM_TITLE, obterTituloSalaAtual())
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun confirmarRemocaoParticipante(
        membro: TunnelProtocol.RoomMemberInfo,
        botaoLinha: MaterialButton
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remover da Sala")
            .setMessage("Como deseja desconectar ${membro.displayName} (${membro.virtualIp})?")
            .setPositiveButton("Remover e Bloquear") { _, _ ->
                botaoLinha.isEnabled = false
                botaoLinha.alpha = 0.4f
                enviarComandoKick(membro.nodeId, bloquearSessao = true)
            }
            .setNeutralButton("Apenas Remover") { _, _ ->
                botaoLinha.isEnabled = false
                botaoLinha.alpha = 0.4f
                enviarComandoKick(membro.nodeId, bloquearSessao = false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarComandoKick(nodeId: Int, bloquearSessao: Boolean) {
        val kickIntent = Intent(this, GdroidLanVpnService::class.java).apply {
            action = GdroidLanVpnService.ACTION_KICK_PEER
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
                val cor = if (ativo) "#FF5252" else if (modoHostSelecionado) "#1890FF" else "#00A8E8"
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
                b.btnAcaoPrincipal.text = if (modoHostSelecionado) "INICIAR SERVIDOR" else "CONECTAR COM CÓDIGO"
                val cor = if (modoHostSelecionado) "#1890FF" else "#00A8E8"
                b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor(cor))
                b.btnAcaoPrincipal.setTextColor(Color.WHITE)
            }
        }

        if (!ativo && !modoHostSelecionado) {
            renderizarSalasPublicas(cacheSalasPublicas, cacheDiretorioConectado)
        }
    }

    private fun renderizarEstado(snap: GdroidLanVpnService.TunnelSnapshot) {
        val ativo = isSessaoAtiva(snap)
        if (ativo) {
            limparErroVerificacaoLocal()
        }

        gerenciarLobbyBrowserConformeEstado(snap)

        if (ativo && modoHostSelecionado != snap.isHost) {
            modoHostSelecionado = snap.isHost
            b.grupoModo.check(if (modoHostSelecionado) R.id.btnModoHost else R.id.btnModoCliente)
        }
        atualizarVisibilidadeModo(ativo)
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
                GdroidLanVpnService.StatusPhase.ERROR -> {
                    b.txtBadgeStatus.text = "ERRO"
                    b.txtBadgeStatus.setTextColor(Color.parseColor("#FF5252"))
                    b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#26FF5252"))
                }
            }

            if (snap.statusText.isNotBlank() && snap.phase != GdroidLanVpnService.StatusPhase.OFFLINE) {
                b.txtDetalheStatus.text = snap.statusText
                b.txtDetalheStatus.setTextColor(
                    if (snap.phase == GdroidLanVpnService.StatusPhase.ERROR) Color.parseColor("#FF5252")
                    else Color.parseColor("#94A3B8")
                )
                b.txtDetalheStatus.visibility = View.VISIBLE
            } else {
                b.txtDetalheStatus.visibility = View.GONE
            }
        }

        b.painelSessaoAtiva.visibility = if (ativo) View.VISIBLE else View.GONE
        b.cardComoUsar.visibility = if (ativo) View.GONE else View.VISIBLE
        b.btnGerarNovoCodigo.visibility = if (ativo) View.INVISIBLE else View.VISIBLE

        if (ativo) {
            b.txtMeuIpVirtual.text = snap.myVirtualIp
            b.txtPingRota.text = if (snap.pingMs > 0) "${snap.pingMs} ms (${snap.routeMode})" else snap.routeMode
            b.txtTrafego.text = formatarBytes(snap.txBytes + snap.rxBytes)
            b.txtContadorSala.text = "${snap.totalPlayersInRoom} / ${snap.maxTotalPlayers}"
            if (segundosRestantes(cooldownPingAteMs) == 0) {
                b.btnTestarPing.isEnabled = (snap.phase == GdroidLanVpnService.StatusPhase.ONLINE)
                b.btnTestarPing.text = "Testar Ping da Sala"
            }

            if (snap.isHost && snap.blockedCount > 0) {
                b.btnDesbloquearRemovidos.visibility = View.VISIBLE
                b.btnDesbloquearRemovidos.text = "Desbloquear (${snap.blockedCount})"
            } else {
                b.btnDesbloquearRemovidos.visibility = View.GONE
            }

            renderizarListaParticipantes(snap)
        }

        b.edtCodigoAmigo.isEnabled = !ativo && !verificandoSala
        b.btnColarCodigoAmigo.isEnabled = !ativo && !verificandoSala
        b.btnModoHost.isEnabled = !verificandoSala
        b.btnModoCliente.isEnabled = !verificandoSala

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

            val pingInfo = when {
                membro.isHost && snap.isHost -> "Servidor"
                membro.pingMs > 0 -> "${membro.pingMs} ms • ${membro.routeLabel}"
                else -> membro.routeLabel
            }
            row.txtInfoRedeParticipante.text = "${membro.virtualIp} • $pingInfo"

            if (snap.isHost && !membro.isHost) {
                row.btnRemoverParticipante.visibility = View.VISIBLE
                row.btnRemoverParticipante.setOnClickListener {
                    confirmarRemocaoParticipante(membro, row.btnRemoverParticipante)
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
