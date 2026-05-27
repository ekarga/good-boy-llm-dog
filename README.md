# Good Boy — LLM Dog for Minecraft

A Minecraft 1.21.1 Fabric mod that adds a tamable dog you can talk to with your voice or text.

Natural language understanding (SmolLM2-135M) and speech-to-text (whisper-tiny.en) both run **fully on-device**, bundled in the jar. No Ollama install, no API keys, no internet needed after download.

## Commands the dog obeys

- *good boy / good dog* — hearts particle effect
- *sit / lie down*
- *get up / stand*
- *come here / call*
- *follow / heel*
- *attack* — whatever you're looking at
- *spin / twirl*
- *jump / hop*
- *give me diamonds*

## Install

1. Install **Fabric Loader** for Minecraft 1.21.1 from https://fabricmc.net/use/
2. Download the latest `llm-dog-*.jar` from [Releases](../../releases)
3. Drop it into your `.minecraft/mods/` folder
4. Launch Minecraft

That's it. Fabric API is bundled. No external dependencies.

## Voice

Hold **V** (push-to-talk) and speak. Whisper transcribes locally, then SmolLM2 maps the text to one of nine intents.

## What's in the jar

- Mod code (Java)
- Fabric API (via JarInJar)
- SmolLM2-135M-Instruct GGUF — Apache-2.0, HuggingFaceTB
- whisper-tiny.en GGML — MIT, OpenAI
- Native libraries for macOS arm64/x86_64, Linux x86_64/aarch64, Windows x86_64

First launch extracts the models to `.minecraft/llm_dog/` (~180MB on disk). Subsequent launches start instantly.

## Privacy

Nothing leaves your machine. No cloud calls. The mod does not open any network sockets except the standard Minecraft client/server connection.

---

Made with Orca — https://orcaengine.ai
