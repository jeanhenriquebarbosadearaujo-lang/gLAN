<p align="center">
  <img src="docs/icon.png" width="160" alt="gLAN Logo" />
</p>

<h1 align="center">gLAN — Rede Virtual P2P para Multiplayer</h1>

<p align="center">
  <b>Conecte salas multiplayer do GDroid e jogos LAN à distância com Salas Públicas Abertas ou Privadas, verificação instantânea de salas ativas, criptografia ponta a ponta, até 8 jogadores e moderação em tempo real.</b>
</p>

---

## Principais Recursos

- **Salas Públicas ou Privadas (Travadas em Sessão):** antes de abrir o servidor, o anfitrião escolhe **Privada** ou **Pública** e define o limite de **2 até 8 jogadores** (`-` / `+`). Após iniciar o servidor, a visibilidade e o limite de jogadores permanecem travados até o encerramento da sala.
- **Verificação Instantânea de Sala Existente:** ao tocar para entrar em uma sala pública ou código privado, o aplicativo confirma antes se a sala realmente está ativa; caso não exista, esteja lotada ou bloqueada, informa o erro na hora sem entrar em estado de conexão falsa.
- **Proteção com Cooldowns Anti-Spam:** cooldowns visuais em início/encerramento de sala, tentativas de entrada, geração de novo código, atualização manual da lista pública, teste de ping, desbloqueio e botões de cópia.
- **Conta Única por Instalação (Apenas Nome):** sem senha e sem login repetido; permanece salva enquanto o app não for desinstalado (`allowBackup="false"`).
- **Split-Tunnel (`10.10.0.0/24`) + Anti-CGNAT:** criptografia `AES-128-GCM` com rota Direta P2P (UDP Hole-Punching) e contingência automática via Ponte TLS.

---

## Como Usar

1. **Anfitrião (`Criar Servidor`):** escolha **Privada** ou **Pública**, defina o limite (`2` a `8`) e toque em **INICIAR SERVIDOR**.
2. **Convidados (`Entrar na Sala`):** toque em **Entrar** em uma sala da lista **Salas Públicas Abertas** ou informe o código privado.
3. **No GDroid:** toque em **Copiar IP** (`10.10.0.1`), abra o GDroid e entre usando **`10.10.0.1`**.
