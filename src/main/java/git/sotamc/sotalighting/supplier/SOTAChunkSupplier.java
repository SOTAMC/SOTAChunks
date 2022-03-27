package git.sotamc.sotalighting.supplier;

import git.sotamc.sotalighting.chunk.SOTAChunk;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.utils.chunk.ChunkSupplier;
import org.jetbrains.annotations.NotNull;

public class SOTAChunkSupplier implements ChunkSupplier {
    @Override
    public @NotNull Chunk createChunk(@NotNull Instance instance, int chunkX, int chunkZ) {
        return new SOTAChunk(instance, chunkX, chunkZ);
    }
}
