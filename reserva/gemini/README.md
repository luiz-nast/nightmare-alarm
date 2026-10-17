# Parked Gemini (plan B for the morning conversation)

The owner chose an AI model **on the phone itself** (Gemma) for the morning conversation. This is the internet
engine (Gemini, from Google), ready and tested, parked here outside the app (it does not compile and does not go
into the APK). It only comes back if the on-phone model does not work out.

## What is here

- `Gemini.kt`: the engine (REST `generateContent`, history, retry on transient failure, reply within
  15 s, `respostaSegura` filter). It already implements `MotorDaConversa` (app/src/main/.../MotorDaConversa.kt).
- `GeminiTest.kt`: the tests for its pure functions (request, response, errors).

## To turn it back on

1. Move `Gemini.kt` to `app/src/main/java/com/implacavel/alarme/` and `GeminiTest.kt` to
   `app/src/test/java/com/implacavel/alarme/`.
2. In `app/build.gradle.kts`, in `defaultConfig`, bring the fields back (the `textoLocal` function already exists there):
   ```kotlin
   buildConfigField("String", "GEMINI_API_KEY", textoLocal("GEMINI_API_KEY", ""))
   buildConfigField("String", "GEMINI_MODELO", textoLocal("GEMINI_MODELO", "gemini-3.5-flash-lite"))
   ```
3. The key lives **only** in the root `local.properties` (outside git): `GEMINI_API_KEY=...`. Never in any other
   file, in the chat or in the log. It goes inside the APK: do not share the APK.
4. In `MotorDaConversa.kt`, make `disponivel` and `criar` use Gemini (as the fallback, after the on-phone
   model, if both exist):
   ```kotlin
   fun disponivel(ctx: Context) = Gemini.disponivel(ctx)
   fun criar(ctx: Context, instrucao: String, abertura: String, falaNaoNegocia: String): MotorDaConversa? =
       Gemini(instrucao, abertura, falaNaoNegocia)
   ```
5. Bring back the `ACCESS_NETWORK_STATE` permission in `AndroidManifest.xml` (`Gemini.disponivel` checks that
   there is real internet access).

## Good to know (verified on 2026-10-14)

- Default model: `gemini-3.5-flash-lite` (stable). No temperature (Gemini 3 models ask for the default) and with
  `thinkingLevel: MINIMAL`.
- On the free tier, Google uses the content to improve its products and humans may review it; on the paid tier,
  it does not. A morning of conversation costs about US$0.015 on the paid tier (estimate).
