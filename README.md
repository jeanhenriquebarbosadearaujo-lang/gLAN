<p align="center">
  <img src="docs/icon.png" width="160" alt="GDroid LAN Logo" />
</p>

<h1 align="center">GDroid LAN — Rede Virtual P2P para Multiplayer</h1>

<p align="center">
  <b>Conecte salas multiplayer do GDroid e jogos LAN à distância com criptografia ponta a ponta, controle de capacidade e gerenciamento de participantes em tempo real.</b>
</p>

---

## Principais Recursos (v2.1)

- **Conta Única por Instalação (Apenas Nome):** ao abrir pela primeira vez, o jogador informa somente o Nome (sem senha e sem login repetido). A conta é única por instalação e fica salva automaticamente apenas enquanto o aplicativo permanecer instalado (`allowBackup="false"`).
- **Controle de Capacidade da Sala:** o anfitrião define exatamente quantos convidados podem entrar na partida (de 1 até 50 convidados por sala).
- **Lista de Participantes em Tempo Real:** visualização ao vivo de todos os jogadores conectados na sala com nome, endereço IP virtual, latência (`ms`) e rota ativa.
- **Moderação pelo Anfitrião:** remoção imediata de qualquer participante da sala (com opção de apenas desconectar ou bloquear na sessão atual).
- **Split-Tunnel (`10.10.0.0/24`):** somente o tráfego da rede virtual `10.10.0.x` passa pelo túnel criptografado (`AES-128-GCM`), preservando a velocidade normal da internet do aparelho.
- **Roteamento Híbrido Anti-CGNAT:** tentativa automática de conexão Direta P2P via UDP Hole-Punching (STUN RFC 5389) com contingência transparente via Ponte TLS.

---

## Instruções de Uso

### 1. Anfitrião da Partida (`Criar Servidor`)
1. Na primeira abertura, informe seu Nome para criar a identificação única da instalação.
2. Na aba **Criar Servidor**, ajuste o **Limite de Pessoas na Sala** e toque em **INICIAR SERVIDOR LAN**.
3. Compartilhe o **Código da Sala** com os participantes.
4. No **GDroid**, inicie o servidor normalmente. Seu IP virtual fixo é sempre **`10.10.0.1`**.

### 2. Convidados (`Entrar na Sala`)
1. Na primeira abertura, informe seu Nome.
2. Na aba **Entrar na Sala**, informe o **Código da Sala** e toque em **CONECTAR NA SALA**.
3. Após exibir **CONECTADO**, abra o **GDroid** e conecte no endereço IP:
   ```text
   10.10.0.1
   ```
