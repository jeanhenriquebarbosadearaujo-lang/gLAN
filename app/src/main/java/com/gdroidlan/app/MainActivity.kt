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
import com.gdroidlan.app.crypto.RoomCrypto
import com.gdroidlan.app.databinding.ActivityMainBinding
import com.gdroidlan.app.vpn.GdroidLanVpnService
import com.gdroidlan.app.vpn.TunnelProtocol

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("gdroid_lan_prefs", Context.MODE_PRIVATE) }

    private var modoHostSelecionado = true
    private var codigoHostAtual = ""
    private var aguardandoPermissaoParaHost = true
    private var codigoPendenteConexao = ""

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                dispararServicoVpn(aguardandoPermissaoParaHost, codigoPendenteConexao)
            } else {
                toast("Aceite a permissão de VPN do Android para criar a rede LAN do jogo.")
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
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        pedirPermissaoNotificacaoSeNecessario()

        codigoHostAtual = prefs.getString("host_room_code", null)
            ?.takeIf { it.length >= 6 }
            ?: RoomCrypto.generateRoomCode().also {
                prefs.edit().putString("host_room_code", it).apply()
            }
        b.txtCodigoHost.text = codigoHostAtual

        prefs.getString("last_friend_code", null)?.let { salvo ->
            b.edtCodigoAmigo.setText(salvo)
        }

        configurarAbasModo()
        configurarBotoes()

        GdroidLanVpnService.onManualPongToast = { rtt, modo ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast("Conexão 100% ativa! Resposta em ${rtt} ms ($modo)")
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
                    toast("Desconecte a sessão atual antes de trocar entre Servidor e Cliente.")
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

    private fun configurarBotoes() {
        b.btnCopiarCodigoHost.setOnClickListener {
            copiarTexto("Código da Sala", codigoHostAtual)
            toast("Código $codigoHostAtual copiado!")
        }

        b.btnCompartilharCodigo.setOnClickListener {
            val msg = "Entre na minha sala do GDroid LAN!\n" +
                "Código da Sala: $codigoHostAtual\n" +
                "IP do Servidor no GDroid: ${TunnelProtocol.HOST_VIRTUAL_IP}"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, msg)
            }
            startActivity(Intent.createChooser(send, "Enviar código para amigo"))
        }

        b.btnGerarNovoCodigo.setOnClickListener {
            val fase = GdroidLanVpnService.currentSnapshot.phase
            if (fase != GdroidLanVpnService.StatusPhase.OFFLINE && fase != GdroidLanVpnService.StatusPhase.ERROR) {
                toast("Desligue o servidor primeiro para gerar outro código.")
                return@setOnClickListener
            }
            codigoHostAtual = RoomCrypto.generateRoomCode()
            prefs.edit().putString("host_room_code", codigoHostAtual).apply()
            b.txtCodigoHost.text = codigoHostAtual
            toast("Novo código gerado: $codigoHostAtual")
        }

        b.btnCopiarIpHost.setOnClickListener {
            copiarTexto("IP GDroid", TunnelProtocol.HOST_VIRTUAL_IP)
            toast("IP ${TunnelProtocol.HOST_VIRTUAL_IP} copiado!")
        }

        b.btnCopiarIpParaJogo.setOnClickListener {
            copiarTexto("IP GDroid", TunnelProtocol.HOST_VIRTUAL_IP)
            toast("IP ${TunnelProtocol.HOST_VIRTUAL_IP} copiado! Cole no GDroid.")
        }

        b.btnColarCodigoAmigo.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val raw = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            // Se a mensagem inteira do WhatsApp foi copiada, extrai o código de 6 caracteres após "Sala:" ou usa o texto limpo
            val extraido = Regex("""(?:Sala:\s*)?([A-Za-z0-9]{6})""").find(raw)?.groupValues?.getOrNull(1)
                ?: RoomCrypto.normalizeRoomCode(raw).take(10)
            val norm = RoomCrypto.normalizeRoomCode(extraido)
            if (norm.length >= 4) {
                b.edtCodigoAmigo.setText(norm)
                b.edtCodigoAmigo.setSelection(norm.length)
            } else {
                toast("Nenhum código válido encontrado na área de transferência.")
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
                    toast("Digite o Código da Sala do seu amigo (ex.: K7P9M2).")
                    return@setOnClickListener
                }
                prefs.edit().putString("last_friend_code", digitado).apply()
                solicitarVpnEIniciar(asHost = false, code = digitado)
            }
        }

        b.btnTestarPing.setOnClickListener {
            val pingIntent = Intent(this, GdroidLanVpnService::class.java).apply {
                action = GdroidLanVpnService.ACTION_MANUAL_PING
            }
            startService(pingIntent)
            toast("Enviando teste de ping pelo túnel...")
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
        val i = Intent(this, GdroidLanVpnService::class.java).apply {
            action = if (asHost) GdroidLanVpnService.ACTION_START_HOST else GdroidLanVpnService.ACTION_START_CLIENT
            putExtra(GdroidLanVpnService.EXTRA_ROOM_CODE, code)
        }
        ContextCompat.startForegroundService(this, i)
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
                b.txtBadgeStatus.text = "CONECTANDO..."
                b.txtBadgeStatus.setTextColor(Color.parseColor("#FFCA28"))
                b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#26FFCA28"))
            }
            GdroidLanVpnService.StatusPhase.WAITING_FRIEND -> {
                b.txtBadgeStatus.text = "AGUARDANDO AMIGO"
                b.txtBadgeStatus.setTextColor(Color.parseColor("#00B8D4"))
                b.txtBadgeStatus.setBackgroundColor(Color.parseColor("#2600B8D4"))
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

        b.txtDetalheStatus.text = snap.statusText
        b.txtMeuIpVirtual.text = snap.myVirtualIp

        val pingLabel = if (snap.pingMs > 0) "${snap.pingMs} ms • ${snap.routeMode}" else snap.routeMode
        b.txtPingRota.text = pingLabel
        b.txtAmigosConectados.text = if (snap.connectedPeersCount > 0) {
            "${snap.connectedPeersCount} online"
        } else {
            "0"
        }
        b.txtTrafego.text = "↑ ${formatarBytes(snap.txBytes)} • ↓ ${formatarBytes(snap.rxBytes)}"

        b.edtCodigoAmigo.isEnabled = !ativo
        b.btnColarCodigoAmigo.isEnabled = !ativo
        b.btnTestarPing.isEnabled = (snap.phase == GdroidLanVpnService.StatusPhase.ONLINE)

        if (ativo) {
            b.btnAcaoPrincipal.text = if (snap.isHost) "⏹ DESLIGAR SERVIDOR LAN" else "⏹ DESCONECTAR DA SALA"
            b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
            b.btnAcaoPrincipal.setTextColor(Color.WHITE)
        } else {
            b.btnAcaoPrincipal.text = if (modoHostSelecionado) "⚡ LIGAR SERVIDOR LAN" else "🚀 CONECTAR NA SALA"
            val cor = if (modoHostSelecionado) "#00E676" else "#00B8D4"
            b.btnAcaoPrincipal.backgroundTintList = ColorStateList.valueOf(Color.parseColor(cor))
            b.btnAcaoPrincipal.setTextColor(Color.parseColor("#04140B"))
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
