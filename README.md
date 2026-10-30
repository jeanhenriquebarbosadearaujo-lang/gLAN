<p align="center">
  <img src="docs/icon.png" width="160" alt="gLAN Logo" />
</p>

<h1 align="center">gLAN — Rede Virtual P2P para Multiplayer</h1>

<p align="center">
  <b>Conecte salas multiplayer do GDroid e jogos LAN à distância com Salas Públicas Abertas ou Privadas, criptografia ponta a ponta, limite de até 8 jogadores e moderação em tempo real.</b>
</p>

---

## Principais Recursos

- **Salas Públicas ou Privadas:** o anfitrião escolhe se a sala ficará **Privada** (acesso somente por Código) ou **Pública** (visível ao vivo na lista de **Salas Públicas Abertas** para conexão com 1 toque).
- **Conta Única por Instalação (Apenas Nome):** na primeira abertura, o jogador informa apenas o Nome (sem senha e sem login repetido). A conta permanece salva enquanto o aplicativo não for desinstalado (`allowBackup="false"`).
- **Limite Ajustável até 8 Jogadores (`-` / `+`):** controle direto pelos botões `-` e `+` de **2 até 8 jogadores** na partida.
- **Lista de Jogadores e Moderação em Tempo Real:** exibe quem entrou na sala com opção imediata de **Remover** ou **Remover e Bloquear**.
- **Split-Tunnel (`10.10.0.0/24`) + Anti-CGNAT:** somente o tráfego `10.10.0.x` passa pelo túnel criptografado (`AES-128-GCM`), combinando conexão Direta P2P (UDP Hole-Punching) e contingência automática via Ponte TLS.

---

## Como Usar

1. **Anfitrião (`Criar Servidor`):** escolha **Privada** ou **Pública**, ajuste o limite (`2` a `8`) e toque em **INICIAR SERVIDOR**.
2. **Convidados (`Entrar na Sala`):** toque em **Entrar** em qualquer sala da lista **Salas Públicas Abertas** (ou digite um código privado).
3. **No GDroid:** toque em **Copiar IP** (`10.10.0.1`), abra o GDroid e entre na partida usando **`10.10.0.1`**.
