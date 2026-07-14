# Good Boy — LLM Dog for Minecraft

A Minecraft 1.21.1 Fabric mod that adds a tamable dog you can talk to with your voice or text.

Speech-to-text (whisper-tiny.en) runs **fully on-device**, bundled in the jar. Understanding is hybrid:

1. **Local fast path** — exact command words ("sit", "good boy") resolve instantly via an offline keyword/fuzzy matcher. No latency, no network.
2. **Mercury dLLM** — anything the local matcher doesn't recognize goes to [Mercury 2](https://www.inceptionlabs.ai/) (Inception Labs' diffusion LLM, ~300–500ms), which maps natural language to commands: *"would you kindly take a seat"* → sit, *"go bite that zombie then come back"* → attack, come.

## Commands the dog obeys

- *good boy / good dog / any praise* — hearts particle effect
- *sit / lie down*
- *get up / stand / stop*
- *come here / call*
- *follow / heel*
- *attack* — whatever you're looking at
- *spin / twirl*
- *jump / hop*
- *give paw / high five*
- *shake it off*
- *give me diamonds*

With Mercury enabled you don't need these exact words — say it however you like.

## Install

1. Install **Fabric Loader** for Minecraft 1.21.1 from https://fabricmc.net/use/
2. Download the latest `llm-dog-*.jar` from [Releases](../../releases)
3. Drop it into your `.minecraft/mods/` folder
4. Launch Minecraft

Fabric API is bundled. Whisper works out of the box with no setup.

## Mercury setup (optional but recommended)

Get an API key from [Inception Labs](https://platform.inceptionlabs.ai/), then either:

- set `llmApiKey` in `.minecraft/config/llm_dog.json5`, or
- export `MERCURY_API_KEY` in the environment Minecraft launches from.

No key = the mod silently falls back to exact-word matching only. Set `llmEnabled: false` to turn the model off entirely.

## Voice

The mic is always listening by default (voice-activity detection segments your speech). Whisper transcribes locally, the local matcher or Mercury maps the text to intents, and every trained (tamed) wolf within 32 blocks obeys. Set `alwaysListen: false` in the config for hold-**V** push-to-talk.

## What's in the jar

- Mod code (Java)
- Fabric API (via JarInJar)
- whisper-tiny.en GGML — MIT, OpenAI
- Native libraries for macOS arm64/x86_64, Linux x86_64/aarch64, Windows x86_64

First launch extracts the whisper model to `.minecraft/llm_dog/`. Subsequent launches start instantly.

## Privacy

Speech-to-text is fully local — raw audio never leaves your machine. With Mercury enabled, **short transcripts that the local matcher couldn't resolve** are sent to the Inception Labs API for intent parsing (utterances longer than `llmMaxWords` words are never sent). Disable with `llmEnabled: false` for a fully offline mod.

---

Made with Orca — https://orcaengine.ai
