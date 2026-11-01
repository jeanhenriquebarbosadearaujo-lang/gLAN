<p align="center">
  <img src="docs/icon.png" width="160" alt="gLAN Logo" />
</p>

<h1 align="center">gLAN — Rede Virtual P2P para Multiplayer</h1>

<p align="center">
  <b>Conecte salas multiplayer do GDroid e jogos LAN à distância com lista inicial de Servidores Públicos com busca e ping em tempo real (ms), reconexão automática entre Wi-Fi e 4G/5G sem cair da partida, criptografia E2EE par-a-par (ECDH P-256 + ECDSA + AES-128-GCM), firewall interno, até 8 jogadores e moderação ao vivo.</b>
</p>

---

## Principais Recursos

- **Três Abas Principais Organizadas:**
  1. **Servidores Públicos (Tela Inicial):** exibe ao vivo todos os servidores abertos ordenados por menor ping (`ms`), barra de pesquisa por nome da sala ou do anfitrião e entrada com 1 toque.
  2. **Entrar na Sala:** entrada rápida por código de 6 caracteres com verificação prévia se a sala existe.
  3. **Criar Servidor:** escolha entre **Privada** ou **Pública**, limite de **2 a 8 jogadores** (`-` / `+`) e botão **Expulsar / Bloquear** jogadores em tempo real assinado digitalmente pelo Host.
- **Reconexão Automática em Oscilação de Rede (`Wi-Fi <-> 4G/5G`):** monitora mudanças na rede física do Android, refaz STUN e canal TLS automaticamente e preserva o jogador na **mesma sala e no mesmo IP `10.10.0.x`** sem derrubar a partida.
- **Ping em Tempo Real (`ms`):** medição contínua da latência nos servidores públicos e em cada participante conectado na sala.
- **Menu Lateral (Gaveta):** acesso organizado a **Perfil** (alteração de nome com filtro anti-+18/ofensas e verificação de nome único), **Configurações** (manter ativo em segundo plano sem restrição de bateria, reconexão automática Wi-Fi/4G, estatísticas de tráfego `KB/s`, ping automático na lista e avisos da sala), **Política de Privacidade**, **Termos de Serviço** e **Créditos**.
- **Proteção de IP em Salas Públicas (Somente Ponte TLS):** oculta o endereço IP real do anfitrião e dos participantes em salas públicas, roteando toda a sessão via Ponte TLS sem expor endpoints UDP/STUN, além de pré-fixar criptograficamente a chave pública do host e aplicar proteção anti-flood por convidado.
- **Segurança Reforçada:** ECDH P-256 par-a-par + assinatura digital ECDSA P-256 + AES-128-GCM com janela anti-replay + Firewall interno IPv4 + TLS 1.2/1.3 obrigatório.

---

## Créditos

- **Criador:** Jean Henrique
- **TikTok:** `.memze`
- **Versão Atual:** `3.3`
