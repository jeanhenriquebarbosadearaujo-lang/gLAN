<p align="center">
  <img src="docs/icon.png" width="160" alt="GDroid LAN Logo" />
</p>

<h1 align="center">GDroid LAN — Rede Virtual P2P para Multiplayer</h1>

<p align="center">
  <b>Jogue GDroid e qualquer jogo Wi-Fi/LAN à distância com seus amigos — sem cadastro, 100% gratuito e compatível com CGNAT / 4G / 5G.</b>
</p>

---

## Como funciona

O **GDroid LAN** cria uma interface VPN local (`10.10.0.0/24`) em **Split-Tunnel**:
- **Somente** os pacotes da rede virtual `10.10.0.x` passam pelo túnel criptografado (`AES-128-GCM`).
- Todo o restante da sua internet (WhatsApp, Discord, navegador, etc.) continua saindo direto na velocidade normal do seu Wi-Fi ou 4G/5G.
- **Roteamento Híbrido Automático:** tenta conexão **Direta P2P via UDP Hole-Punching (STUN RFC 5389)** para menor ping e usa **Ponte CGNAT TLS** automaticamente quando a operadora bloqueia portas de entrada.
- Qualquer um dos dois celulares pode ser **Servidor (Host)** ou **Cliente (Amigo)** usando o mesmo APK!

---

## Passo a Passo para Jogar no GDroid

### 1. Quem vai hospedar a partida (`Servidor / Host`)
1. Abra o **GDroid LAN** e deixe na aba **👑 Criar Servidor**.
2. Toque em **⚡ LIGAR SERVIDOR LAN** (aceite a permissão padrão de VPN do Android na primeira vez).
3. Envie o **Código da Sala** (ex.: `K7P9M2`) para seu amigo pelo botão **Enviar p/ Amigo**.
4. Abra o **GDroid** e inicie o servidor multiplayer normalmente. Seu IP na rede virtual é sempre **`10.10.0.1`**.

### 2. Quem vai entrar na partida (`Cliente / Amigo`)
1. Abra o **GDroid LAN** e toque na aba **🎮 Entrar na Sala**.
2. Cole ou digite o **Código da Sala** recebido e toque em **🚀 CONECTAR NA SALA**.
3. Assim que aparecer **CONECTADO** (você receberá o IP `10.10.0.2`), abra o **GDroid**, vá em conectar por IP e digite:
   ```text
   10.10.0.1
   ```

---

## Compilação Automática (GitHub Actions)

Todo push na branch `main` compila e assina automaticamente o APK de release (`GDroid-LAN-v1.0.apk` com assinatura `V1 + V2 + V3`) e publica em **GitHub Releases**.
