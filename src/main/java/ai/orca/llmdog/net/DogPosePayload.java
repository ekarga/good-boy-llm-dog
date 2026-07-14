package ai.orca.llmdog.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> client: "wolf {entityId} should play pose {pose} for {duration}
 * ticks." A duration of -1 means hold until cleared (used by DOWN). Drives the
 * client-side {@code WolfEntityModel} mixin — vanilla wolves have no synced
 * field for these poses, so we push them ourselves to tracking players.
 */
public record DogPosePayload(int entityId, int pose, int duration) implements CustomPayload {

    public static final int NONE = 0, PAW = 1, DOWN = 2, SHAKE = 3;

    public static final CustomPayload.Id<DogPosePayload> ID =
        new CustomPayload.Id<>(Identifier.of("llm_dog", "dog_pose"));

    public static final PacketCodec<RegistryByteBuf, DogPosePayload> CODEC = PacketCodec.tuple(
        PacketCodecs.VAR_INT, DogPosePayload::entityId,
        PacketCodecs.VAR_INT, DogPosePayload::pose,
        PacketCodecs.INTEGER, DogPosePayload::duration,
        DogPosePayload::new);

    @Override
    public CustomPayload.Id<DogPosePayload> getId() {
        return ID;
    }
}
