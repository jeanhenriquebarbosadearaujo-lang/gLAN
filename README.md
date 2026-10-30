<p align="center">
  <img src="docs/icon.png" width="160" alt="GDroid LAN Logo" />
</p>

<h1 align="center">GDroid LAN — Rede Virtual P2P para Multiplayer</h1>

<p align="center">
  <b>Conecte salas multiplayer do GDroid e jogos LAN à distância com criptografia ponta a ponta, limite ajustável de até 8 jogadores e moderação em tempo real.</b>
</p>

---

## Principais Recursos (v2.2)

- **Conta Única por Instalação (Apenas Nome):** ao abrir pela primeira vez, o jogador informa somente o Nome (sem senha e sem login repetido). A conta fica salva automaticamente apenas enquanto o aplicativo permanecer instalado (`allowBackup="false"`).
- **Limite Ajustável até 8 Jogadores (`-` / `+`):** controle direto pelos botões `-` e `+` de **2 até 8 jogadores** na partida.
- **Interface Limpa e Organizada:** layout direto sem excesso de blocos; os painéis de **Jogadores na Sala** e **Rede Ativa** aparecem de forma contextual somente quando a sala está em uso.
- **Lista de Jogadores e Moderação em Tempo Real:** exibe quem entrou na sala com opção imediata de **Remover** ou **Remover e Bloquear**.
- **Split-Tunnel (`10.10.0.0/24`) + Anti-CGNAT:** somente o tráfego `10.10.0.x` passa pelo túnel criptografado (`AES-128-GCM`), combinando conexão Direta P2P (UDP Hole-Punching) e contingência automática via Ponte TLS.

---

## Como Usar

1. **Anfitrião (`Criar Servidor`):** ajuste o limite de jogadores (`2` a `8`), toque em **INICIAR SERVIDOR** e envie o **Código da Sala**.
2. **Convidados (`Entrar na Sala`):** informe o **Código da Sala** e toque em **CONECTAR NA SALA**.
3. **No GDroid:** o anfitrião cria o mundo normalmente e todos os convidados conectam pelo IP fixo **`10.10.0.1`**.
