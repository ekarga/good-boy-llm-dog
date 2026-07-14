package ai.orca.llmdog.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server: "run this comma-separated intent sequence on my wolves."
 * Sent by the voice path after the client resolves a transcript to intents
 * (locally or via Mercury), so spoken commands don't have to travel as chat
 * messages. The server re-validates every intent against its whitelist.
 */
public record DogCommandPayload(String intentsCsv) implements CustomPayload {

    public static final CustomPayload.Id<DogCommandPayload> ID =
        new CustomPayload.Id<>(Identifier.of("llm_dog", "dog_command"));

    public static final PacketCodec<RegistryByteBuf, DogCommandPayload> CODEC = PacketCodec.tuple(
        PacketCodecs.STRING, DogCommandPayload::intentsCsv,
        DogCommandPayload::new);

    @Override
    public CustomPayload.Id<DogCommandPayload> getId() {
        return ID;
    }
}
