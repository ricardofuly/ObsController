# OBS Controle (Android)

App leve (~580 KB, sem bibliotecas de UI) para controlar o OBS pelo celular:
**trocar cenas** e **mutar/desmutar fontes de áudio**. Feito para rodar bem em
aparelhos simples como o Galaxy M20 (Android 8.0 ou superior).

Usa o servidor WebSocket que já vem embutido no OBS 28+ (protocolo obs-websocket v5).

## 1. Configurar o OBS (no PC)

1. Abra o OBS → **Ferramentas → Configurações do servidor WebSocket**.
2. Marque **Ativar servidor WebSocket** (porta padrão 4455).
3. Deixe a autenticação ligada e clique em **Mostrar informações de conexão** para ver IP, porta e senha.
4. Se o Firewall do Windows perguntar, permita o OBS em **redes privadas**.

O celular e o PC precisam estar **na mesma rede Wi-Fi/roteador**.

## 2. Instalar no celular

Arquivo: `app/build/outputs/apk/release/app-release.apk`

- Copie o APK para o celular (cabo USB, Google Drive, WhatsApp etc.) e abra-o.
  Permita "instalar apps de fontes desconhecidas" quando o Android pedir.
- Ou, com depuração USB ativa: `adb install -r app/build/outputs/apk/release/app-release.apk`

## 3. Usar

**Mais fácil: QR Code.** No OBS, em Configurações do servidor WebSocket, clique em "Mostrar informações de conexão" e, no app, toque em **Ler QR Code do OBS**. Também funciona apontando a câmera do celular para o QR: o link `obsws://` abre o app direto (ele pergunta antes de conectar).

Ou digite o IP, a porta e a senha → **Conectar**. As configurações ficam salvas e o app
reconecta sozinho ao abrir ou se o Wi-Fi cair. O botão de ajustes (canto superior) reabre a conexão.

- **Deck (estilo controlador de botões)**: teclas coloridas para as cenas e uma linha de teclas de função.
  - Horizontal (uso principal): 5×3 — duas linhas de cenas + funções. Retrato: 2×5 de cenas + funções.
- **Cenas**: até 10 por página — deslize para o lado ou use as setas. A cena no ar ganha contorno vermelho e "NO AR".
  Só aparecem as cenas acima da primeira divisória (ex.: "======= Source =======").
- **Teclas de função**: Transmitir (pede confirmação, mostra o tempo no ar), Gravar (confirma para parar),
  Pausar/Retomar gravação, Replay (1º toque liga o buffer, depois salva) e Câmera virtual.
- **Ícones**: escolhidos pelo nome (Intro, BRB, Game, Chat, Cam…). Segure uma cena para escolher outro,
  ou comece o nome dela no OBS com um emoji (ex.: "☕ BRB").
- **Áudio**: abaixo do deck aparecem só as fontes da cena no ar (mais o áudio global, como Desktop e Mic),
  igual ao mixer do OBS — muda sozinho quando a cena muda.
  - **Volume na faixa**: arraste a faixa inteira para os lados. O volume parte de onde está (tocar sem querer
    não muda nada). O fundo preenchido mostra o volume e a linha de baixo mostra o nível do som.
  - **Toque** na faixa abre a janela com fader grande, passos de −1 / +1 dB, "0 dB" e mudo. **Segure** para ocultar.
  - **Mixer** (botão no topo): faders verticais grandes para todas as fontes da cena, com medidor, − / + e mudo.
  Fontes ocultas na cena (olho desligado no OBS) não aparecem. O "Ocultar" do mixer do OBS não é enviado
  pelo WebSocket, então o app tem o seu: segure a faixa → Ocultar. Para voltar: Conexão → Faixas ocultas no app.
- **Alerta de microfone**: se um microfone estiver mutado durante uma live ou gravação, a faixa dele
  pisca em vermelho com "MUTADO!".
- Tudo fica sincronizado com o OBS em tempo real. Em segundo plano o app se desconecta para economizar bateria.

## Atualização automática

O app procura novas versões nas [Releases do GitHub](https://github.com/ricardofuly/ObsController/releases)
ao abrir (no máximo a cada 6 h) e pelo botão **Verificar atualizações** na tela de conexão.
Se houver uma versão maior, ele baixa o APK e abre o instalador do Android
(na primeira vez o Android pede para permitir "Instalar apps desconhecidos" para o OBS Controle).

Para publicar uma atualização:

1. Aumente `versionCode` e `versionName` em `app/build.gradle` (ou passe `-Pvc=3 -Pvn=1.2.0`).
2. Compile: `.\gradlew.bat assembleRelease`
3. Crie a release com a tag `v<versionName>` e anexe o APK:

```powershell
gh release create v1.2.0 app/build/outputs/apk/release/app-release.apk#ObsController-1.2.0.apk --title "1.2.0" --notes "O que mudou"
```

O APK precisa ser assinado sempre com a mesma chave (hoje, a chave de debug deste PC),
senão o Android recusa instalar por cima da versão anterior.

## Compilar

Requer o JDK do Android Studio e o Android SDK 34.

```powershell
.\gradlew.bat assembleRelease
```

O APK de release é assinado com a chave de debug, então instala direto (uso pessoal).
