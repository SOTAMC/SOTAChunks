package git.sotamc.sotachunks.instance;

import net.minestom.server.instance.IChunkLoader;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class SOTAInstance extends InstanceContainer {

    @SuppressWarnings("UnstableApiUsage")
    public SOTAInstance(@NotNull UUID uniqueId, @NotNull DimensionType dimensionType, @Nullable IChunkLoader loader) {
        super(uniqueId, dimensionType, loader);
    }

    public SOTAInstance(@NotNull UUID uniqueId, @NotNull DimensionType dimensionType) {
        super(uniqueId, dimensionType);
    }
}
