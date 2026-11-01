package com.gdroidlan.app.vpn

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Receptor interno para ações rápidas da notificação ativa do gLAN,
 * permitindo copiar o comando de console sem minimizar o GDroid.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY_CONNECT = "com.gdroidlan.app.ACTION_COPY_CONNECT"
        const val EXTRA_CONNECT_CMD = "extra_connect_cmd"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val token = intent.getStringExtra(GdroidLanVpnService.EXTRA_CALLER_TOKEN)
        if (token != GdroidLanVpnService.INTERNAL_CALLER_TOKEN) return

        if (intent.action == ACTION_COPY_CONNECT) {
            val cmd = intent.getStringExtra(EXTRA_CONNECT_CMD)
                ?.takeIf { it.isNotBlank() }
                ?: TunnelProtocol.HOST_CONNECT_CMD
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Console GDroid", cmd))
            Toast.makeText(
                context.applicationContext,
                "Comando copiado: $cmd",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
