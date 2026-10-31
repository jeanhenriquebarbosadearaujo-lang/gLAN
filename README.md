<p align="center">
  <img src="docs/icon.png" width="160" alt="gLAN Logo" />
</p>

<h1 align="center">gLAN — Rede Virtual P2P para Multiplayer</h1>

<p align="center">
  <b>Conecte salas multiplayer do GDroid e jogos LAN à distância com Salas Públicas Abertas ou Privadas, criptografia E2EE par-a-par (ECDH P-256 + ECDSA + AES-128-GCM), firewall interno de portas, até 8 jogadores e moderação em tempo real.</b>
</p>

---

## Principais Recursos

- **Segurança Reforçada (v2.6):**
  - Acordo de chaves **ECDH P-256** individual por jogador (Forward Secrecy par-a-par) + **AES-128-GCM** com janela anti-replay de 512 pacotes.
  - Assinatura digital **ECDSA P-256 (`SHA256withECDSA`)** nos anúncios de Salas Públicas, lista de participantes, expulsões e encerramento de sala (impede falsificação ou derrubada de salas públicas).
  - **Firewall IPv4 no Túnel Virtual:** bloqueio estrito contra falsificação de IP interno (*Anti-IP-Spoofing*) e bloqueio automático de portas críticas do Android (ADB, SSH, Telnet, SMB).
  - Conexão de sinalização exclusivamente via **TLS 1.2/1.3 (porta 8883)** com validação estrita de hostname X.509 e `usesCleartextTraffic="false"`.
- **Salas Públicas ou Privadas (Travadas em Sessão):** visibilidade e limite de **2 a 8 jogadores** (`-` / `+`) configuráveis antes de abrir e travados durante a partida.
- **Verificação Instantânea de Sala Existente + Cooldowns Anti-Spam:** detecta imediatamente salas inexistentes, lotadas ou bloqueadas sem entrar em falso estado de conexão.
- **Conta Única por Instalação (Apenas Nome):** vinculada ao aparelho (`allowBackup="false"`), sem senha.

---

## Créditos

- **Criador:** Jean Henrique
- **TikTok:** `.memze`
- **Versão Atual:** `2.6`
