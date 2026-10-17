# Gemini guardado (plano B da conversa da manhã)

O dono escolheu um modelo de IA **no próprio celular** (Gemma) para a conversa da manhã. Este é o motor
pela internet (Gemini, do Google), pronto e testado, guardado aqui fora do app (não compila e não vai pro
APK). Ele só volta se o modelo do celular não servir.

## O que tem aqui

- `Gemini.kt`: o motor (REST `generateContent`, histórico, nova tentativa em falha passageira, resposta em
  até 15 s, filtro `respostaSegura`). Já implementa `MotorDaConversa` (app/src/main/.../MotorDaConversa.kt).
- `GeminiTest.kt`: os testes das funções puras dele (pedido, resposta, erros).

## Pra religar

1. Mover `Gemini.kt` pra `app/src/main/java/com/implacavel/alarme/` e `GeminiTest.kt` pra
   `app/src/test/java/com/implacavel/alarme/`.
2. Em `app/build.gradle.kts`, no `defaultConfig`, voltar os campos (a função `textoLocal` já existe lá):
   ```kotlin
   buildConfigField("String", "GEMINI_API_KEY", textoLocal("GEMINI_API_KEY", ""))
   buildConfigField("String", "GEMINI_MODELO", textoLocal("GEMINI_MODELO", "gemini-3.5-flash-lite"))
   ```
3. A chave fica **só** no `local.properties` da raiz (fora do git): `GEMINI_API_KEY=...`. Nunca em outro
   arquivo, no chat ou no log. Ela vai dentro do APK: não compartilhe o APK.
4. Em `MotorDaConversa.kt`, fazer `disponivel` e `criar` usarem o Gemini (como ordem de reserva, depois do
   modelo do celular, se os dois existirem):
   ```kotlin
   fun disponivel(ctx: Context) = Gemini.disponivel(ctx)
   fun criar(ctx: Context, instrucao: String, abertura: String, falaNaoNegocia: String): MotorDaConversa? =
       Gemini(instrucao, abertura, falaNaoNegocia)
   ```
5. Voltar no `AndroidManifest.xml` a permissão `ACCESS_NETWORK_STATE` (o `Gemini.disponivel` confere se há
   internet de verdade).

## Bom saber (verificado em 14/10/2026)

- Modelo padrão: `gemini-3.5-flash-lite` (estável). Sem temperatura (os Gemini 3 pedem a padrão) e com
  `thinkingLevel: MINIMAL`.
- No plano grátis o Google usa o conteúdo pra melhorar os produtos e pessoas podem revisar; no pago, não.
  Uma manhã de conversa custa uns US$ 0,015 no pago (estimativa).
