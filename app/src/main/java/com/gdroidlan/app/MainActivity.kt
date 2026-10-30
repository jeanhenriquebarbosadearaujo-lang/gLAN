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
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gdroidlan.app.auth.AccountManagerStore
import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.databinding.ActivityMainBinding
import com.gdroidlan.app.databinding.ItemPeerRowBinding
import com.gdroidlan.app.vpn.GdroidLanVpnService
import com.gdroidlan.app.vpn.TunnelProtocol
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("gdroid_lan_prefs", Context.MODE_PRIVATE) }

    private var contaAtiva: AccountManagerStore.UserAccount? = null
    private var modoHostSelecionado = true
    private var codigoHostAtual = ""
    private var limiteJogadores = TunnelProtocol.MAX_TOTAL_PLAYERS

    private var aguardandoPermissaoParaHost = true
    private var codigoPendenteConexao = ""

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
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
        renderizarSeletorCapacidade()

        prefs.getString("last_friend_code", null)?.let { salvo ->
            b.edtCodigoAmigo.setText(salvo)
        }

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
    }

    override fun onStop() {
        GdroidLanVpnService.removeListener(stateListener)
        super.onStop()
    }

    private fun configurarSeletorCapacidade() {
        b.btnMenosVagas.setOnClickListener {
            if (limiteJogadores > TunnelProtocol.MIN_TOTAL_PLAYERS) {
                definirLimiteJogadores(limiteJogadores - 1)
            }
        }
        b.btnMaisVagas.setOnClickListener {
            if (limiteJogadores < TunnelProtocol.MAX_TOTAL_PLAYERS) {
                definirLimiteJogadores(limiteJogadores + 1)
            }
        }
    }

    private fun definirLimiteJogadores(novoTotal: Int) {
        limiteJogadores = novoTotal.coerceIn(
            TunnelProtocol.MIN_TOTAL_PLAYERS,
            TunnelProtocol.MAX_TOTAL_PLAYERS
        )
        prefs.edit().putInt("max_players_v22", limiteJogadores).apply()
        renderizarSeletorCapacidade()
        sincronizarConfiguracaoEmExecucao()
    }

    private fun renderizarSeletorCapacidade() {
        val convidados = limiteJogadores - 1
        b.txtNumeroJogadores.text = limiteJogadores.toString()
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

    private fun sincronizarConfiguracaoEmExecucao() {
        val snap = GdroidLanVpnService.currentSnapshot
        val rodando = snap.phase != GdroidLanVpnService.StatusPhase.OFFLINE &&
            snap.phase != GdroidLanVpnService.StatusPhase.ERROR
        if (!rodando) return

        val cfgIntent = Intent(this, GdroidLanVpnService::class.java).apply {
            action = GdroidLanVpnService.ACTION_UPDATE_CONFIG
            putExtra(GdroidLanVpnService.EXTRA_MAX_PLAYERS, limiteJogadores)
        }
        startService(cfgIntent)
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
        val ativo = snap.phase != GdroidLanVpnService.StatusPhase.OFFLINE &&
            snap.phase != GdroidLanVpnService.StatusPhase.ERROR
        modoHostSelecionado = if (ativo) snap.isHost else true

        b.grupoModo.check(if (modoHostSelecionado) R.id.btnModoHost else R.id.btnModoCliente)
        atualizarVisibilidadeModo()

        b.grupoModo.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val rodando = GdroidLanVpnService.currentSnapshot.phase !in listOf(
                GdroidLanVpnService.StatusPhase.OFFLINE,
                GdroidLanVpnService.StatusPhase.ERROR
            )
            if (rodando) {
                val idCorreto = if (GdroidLanVpnService.currentSnapshot.isHost) R.id.btnModoHost else R.id.btnModoCliente
                if (checkedId != idCorreto) {
                    b.grupoModo.check(idCorreto)
                    toast("Encerre a conexão atual antes de trocar de aba.")
                }
                return@addOnButtonCheckedListener
            }
            modoHostSelecionado = (checkedId == R.id.btnModoHost)
            atualizarVisibilidadeModo()
            renderizarEstado(GdroidLanVpnService.currentSnapshot)
        }
    }

    private fun atualizarVisibilidadeModo() {
        b.cardHost.visibility = if (modoHostSelecionado) View.VISIBLE else View.GONE
        b.cardCliente.visibility = if (modoHostSelecionado) View.GONE else View.VISIBLE
    }

    private fun configurarBotoesPrincipais() {
        b.btnCopiarCodigoHost.setOnClickListener {
            copiarTexto("Código da Sala", codigoHostAtual)
            toast("Código $codigoHostAtual copiado.")
        }

        b.btnCompartilharCodigo.setOnClickListener {
            val anfitriao = contaAtiva?.displayName ?: "Anfitrião"
            val msg = "Sala GDroid LAN de $anfitriao\n" +
                "Código da Sala: $codigoHostAtual\n" +
                "IP no GDroid: ${TunnelProtocol.HOST_VIRTUAL_IP}"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, msg)
            }
            startActivity(Intent.createChooser(send, "Compartilhar sala"))
        }

        b.btnGerarNovoCodigo.setOnClickListener {
            val fase = GdroidLanVpnService.currentSnapshot.phase
            if (fase != GdroidLanVpnService.StatusPhase.OFFLINE && fase != GdroidLanVpnService.StatusPhase.ERROR) {
                toast("Encerre o servidor atual antes de gerar outro código.")
                return@setOnClickListener
            }
            codigoHostAtual = RoomCrypto.generateRoomCode()
            prefs.edit().putString("host_room_code", codigoHostAtual).apply()
            b.txtCodigoHost.text = codigoHostAtual
            toast("Novo código: $codigoHostAtual.")
        }

        b.btnCopiarIpHost.setOnClickListener {
            copiarTexto("IP GDroid", TunnelProtocol.HOST_VIRTUAL_IP)
            toast("IP ${TunnelProtocol.HOST_VIRTUAL_IP} copiado.")
        }

        b.btnCopiarIpParaJogo.setOnClickListener {
            copiarTexto("IP GDroid", TunnelProtocol.HOST_VIRTUAL_IP)
            toast("IP ${TunnelProtocol.HOST_VIRTUAL_IP} copiado.")
        }

        b.btnColarCodigoAmigo.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val raw = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val extraido = Regex("""(?:Sala:\s*)?([A-Za-z0-9]{6})""").find(raw)?.groupValues?.getOrNull(1)
                ?: RoomCrypto.normalizeRoomCode(raw).take(10)
            val norm = RoomCrypto.normalizeRoomCode(extraido)
            if (norm.length >= 4) {
                b.edtCodigoAmigo.setText(norm)
                b.edtCodigoAmigo.setSelection(norm.length)
            } else {
                toast("Nenhum código válido encontrado para colar.")
            }
        }

        b.btnAcaoPrincipal.setOnClickListener {
            val fase = GdroidLanVpnService.currentSnapshot.phase
            val emExecucao = fase != GdroidLanVpnService.StatusPhase.OFFLINE &&
                fase != GdroidLanVpnService.StatusPhase.ERROR

            if (emExecucao) {
                val stop = Intent(this, GdroidLanVpnService::class.java).apply {
                    action = GdroidLanVpnService.ACTION_STOP
                }
                startService(stop)
                return@setOnClickListener
            }

            if (modoHostSelecionado) {
                solicitarVpnEIniciar(asHost = true, code = codigoHostAtual)
            } else {
                val digitado = RoomCrypto.normalizeRoomCode(b.edtCodigoAmigo.text?.toString().orEmpty())
                if (digitado.length < 4) {
                    toast("Digite o Código da Sala (ex.: K7P9M2).")
                    return@setOnClickListener
                }
                prefs.edit().putString("last_friend_code", digitado).apply()
                solicitarVpnEIniciar(asHost = false, code = digitado)
            }
        }

        b.btnDesbloquearRemovidos.setOnClickListener {
            val clearIntent = Intent(this, GdroidLanVpnService::class.java).apply {
                action = GdroidLanVpnService.ACTION_CLEAR_BLOCKS
            }
            startService(clearIntent)
            toast("Lista de bloqueios da sala limpa.")
        }

        b.btnTestarPing.setOnClickListener {
            val pingIntent = Intent(this, GdroidLanVpnService::class.java).apply {
                action = GdroidLanVpnService.ACTION_MANUAL_PING
            }
            startService(pingIntent)
            toast("Testando latência...")
        }
    }

    private fun solicitarVpnEIniciar(asHost: Boolean, code: String) {
        aguardandoPermissaoParaHost = asHost
        codigoPendenteConexao = code
        val prep = VpnService.prepare(this)
        if (prep != null) {
            vpnPermissionLauncher.launch(prep)
        } else {
            dispararServicoVpn(asHost, code)
        }
    }

    private fun dispararServicoVpn(asHost: Boolean, code: String) {
        val conta = contaAtiva
        val i = Intent(this, GdroidLanVpnService::class.java).apply {
            action = if (asHost) GdroidLanVpnService.ACTION_START_HOST else GdroidLanVpnService.ACTION_START_CLIENT
            putExtra(GdroidLanVpnService.EXTRA_ROOM_CODE, code)
            putExtra(GdroidLanVpnService.EXTRA_PLAYER_NAME, conta?.displayName ?: "Jogador")
            putExtra(GdroidLanVpnService.EXTRA_ACCOUNT_UID, conta?.accountUid ?: 0L)
            putExtra(GdroidLanVpnService.EXTRA_MAX_PLAYERS, limiteJogadores)
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun confirmarRemocaoParticipante(membro: TunnelProtocol.RoomMemberInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remover da Sala")
            .setMessage("Como deseja desconectar ${membro.displayName} (${membro.virtualIp})?")
            .setPositiveButton("Remover e Bloquear") { _, _ ->
                enviarComandoKick(membro.nodeId, bloquearSessao = true)
            }
            .setNeutralButton("Apenas Remover") { _, _ ->
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

    private fun renderizarEstado(snap: GdroidLanVpnService.TunnelSnapshot) {
        val ativo = snap.phase != GdroidLanVpnService.StatusPhase.OFFLINE &&
            snap.phase != GdroidLanVpnService.StatusPhase.ERROR

        if (ativo && modoHostSelecionado != snap.isHost) {
            modoHostSelecionado = snap.isHost
            b.grupoModo.check(if (modoHostSelecionado) R.id.btnModoHost else R.id.btnModoCliente)
            atualizarVisibilidadeModo()
        }

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
                b.txtBadgeStatus.text = "AGUARDANDO"
                b.txtBadgeStatus.setTextColor(Color.parseColor("#00B8D4"))
                b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#2600B8D4"))
            }
            GdroidLanVpnService.StatusPhase.ONLINE -> {
                b.txtBadgeStatus.text = "CONECTADO"
                b.txtBadgeStatus.setTextColor(Color.parseColor("#00E676"))
                b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#2600E676"))
            }
            GdroidLanVpnService.StatusPhase.ERROR -> {
                b.txtBadgeStatus.text = "AVISO"
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

        // Alterna entre o painel de sessão ao vivo e o card compacto de resumo
        b.painelSessaoAtiva.visibility = if (ativo) View.VISIBLE else View.GONE
        b.cardComoUsar.visibility = if (ativo) View.GONE else View.VISIBLE
        b.btnGerarNovoCodigo.visibility = if (ativo) View.INVISIBLE else View.VISIBLE

        if (ativo) {
            b.txtMeuIpVirtual.text = snap.myVirtualIp
            b.txtPingRota.text = if (snap.pingMs > 0) "${snap.pingMs} ms (${snap.routeMode})" else snap.routeMode
            b.txtTrafego.text = formatarBytes(snap.txBytes + snap.rxBytes)
            b.txtContadorSala.text = "${snap.totalPlayersInRoom} / ${snap.maxTotalPlayers}"
            b.btnTestarPing.isEnabled = (snap.phase == GdroidLanVpnService.StatusPhase.ONLINE)

            if (snap.isHost && snap.blockedCount > 0) {
                b.btnDesbloquearRemovidos.visibility = View.VISIBLE
                b.btnDesbloquearRemovidos.text = "Desbloquear (${snap.blockedCount})"
            } else {
                b.btnDesbloquearRemovidos.visibility = View.GONE
            }

            renderizarListaParticipantes(snap)
        }

        b.edtCodigoAmigo.isEnabled = !ativo
        b.btnColarCodigoAmigo.isEnabled = !ativo

        if (ativo) {
            b.btnAcaoPrincipal.text = if (snap.isHost) "ENCERRAR SERVIDOR" else "SAIR DA SALA"
            b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
            b.btnAcaoPrincipal.setTextColor(Color.WHITE)
        } else {
            b.btnAcaoPrincipal.text = if (modoHostSelecionado) "INICIAR SERVIDOR" else "CONECTAR NA SALA"
            val cor = if (modoHostSelecionado) "#00E676" else "#00B8D4"
            b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor(cor))
            b.btnAcaoPrincipal.setTextColor(Color.parseColor("#04140B"))
        }
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
                    row.txtCargoParticipante.setTextColor(Color.parseColor("#00B8D4"))
                    row.txtCargoParticipante.setBackgroundColor(Color.parseColor("#2600B8D4"))
                }
                else -> {
                    row.txtCargoParticipante.text = "JOGADOR"
                    row.txtCargoParticipante.setTextColor(Color.parseColor("#00B8D4"))
                    row.txtCargoParticipante.setBackgroundColor(Color.parseColor("#2600B8D4"))
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
                    confirmarRemocaoParticipante(membro)
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
