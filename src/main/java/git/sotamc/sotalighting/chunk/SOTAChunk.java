package git.sotamc.sotalighting.chunk;

import com.extollit.gaming.ai.path.model.ColumnarOcclusionFieldList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.pathfinding.PFBlock;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.packet.server.play.data.ChunkData;
import net.minestom.server.network.packet.server.play.data.LightData;
import net.minestom.server.snapshot.*;
import net.minestom.server.tag.Tag;
import net.minestom.server.tag.TagReadable;
import net.minestom.server.utils.ArrayUtils;
import net.minestom.server.utils.MathUtils;
import net.minestom.server.utils.Utils;
import net.minestom.server.utils.binary.BinaryWriter;
import net.minestom.server.utils.binary.PooledBuffers;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.utils.collection.IntMappedArray;
import net.minestom.server.utils.collection.MappedCollection;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biomes.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import org.jglrxavpok.hephaistos.nbt.NBT;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static net.minestom.server.utils.chunk.ChunkUtils.*;

public class SOTAChunk extends Chunk {

    /* Definitions
        Chunk : limited by a size of 16x256x16 blocks (X, Y, Z).
        Section : A section is a Y component of a chunk, every section is 16 blocks high. There are 16 sections in a chunk.
        Generation : Every chunk is guarded by this instance. They can be generated asynchronously.
    */

    /* Super and Local Variables
        Super:
        (Instance) instance : The instance passed by SOTAChunk.
        (int) chunkX : The X coordinate of the chunk passed by SOTAChunk.
        (int) chunkZ : The Y coordinate of the chunk passed by SOTAChunk.
        (boolean) shouldGenerate : If the chunk should be generated upon creation.

        Local:
        (List<Section> sections : A list of the sections in the chunk. Can be empty.
        (long) lastChangeTime : Time since last block update.
    */

    // Local Variables
    @UnknownNullability
    private List<Section> sections;

    // Key = ChunkUtils#getBlockIndex
    protected final Int2ObjectOpenHashMap<Block> entries = new Int2ObjectOpenHashMap<>(0);
    protected final Int2ObjectOpenHashMap<Block> tickableMap = new Int2ObjectOpenHashMap<>(0);

    private long lastChange;
    private final CachedPacket chunkCache = new CachedPacket(this::createChunkPacket);
    private final CachedPacket lightCache = new CachedPacket(this::createLightPacket);

    public SOTAChunk(@NotNull Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ, true);
        var sectionsTemp = new Section[maxSection - minSection];
        Arrays.setAll(sectionsTemp, value -> new Section());
        this.sections = List.of(sectionsTemp);
    }

    @Override
    public void setBlock(int x, int y, int z, @NotNull Block block) {
        assertLock();
        this.lastChange = System.currentTimeMillis();
        this.chunkCache.invalidate();
        this.lightCache.invalidate();
        // Update pathfinder
        if (columnarSpace != null) {
            final ColumnarOcclusionFieldList columnarOcclusionFieldList = columnarSpace.occlusionFields();
            final var blockDescription = PFBlock.get(block);
            columnarOcclusionFieldList.onBlockChanged(x, y, z, blockDescription, 0);
        }
        Section section = getSectionAt(y);
        section.blockPalette()
                .set(toSectionRelativeCoordinate(x), toSectionRelativeCoordinate(y), toSectionRelativeCoordinate(z), block.stateId());

        final int index = getBlockIndex(x, y, z);
        // Handler
        final BlockHandler handler = block.handler();
        if (handler != null || block.hasNbt() || block.registry().isBlockEntity()) {
            this.entries.put(index, block);
        } else {
            this.entries.remove(index);
        }
        // Block tick
        if (handler != null && handler.isTickable()) {
            this.tickableMap.put(index, block);
        } else {
            this.tickableMap.remove(index);
        }
    }

    @Override
    public void setBiome(int x, int y, int z, @NotNull Biome biome) {
        assertLock();
        this.chunkCache.invalidate();
        Section section = getSectionAt(y);
        section.biomePalette().set(
                toSectionRelativeCoordinate(x) / 4,
                toSectionRelativeCoordinate(y) / 4,
                toSectionRelativeCoordinate(z) / 4, biome.id());
    }

    @Override
    public @NotNull List<Section> getSections() {
        return sections;
    }

    @Override
    public @NotNull Section getSection(int section) {
        return sections.get(section - minSection);
    }

    @Override
    public void tick(long time) {
        if (tickableMap.isEmpty()) return;
        tickableMap.int2ObjectEntrySet().fastForEach(entry -> {
            final int index = entry.getIntKey();
            final Block block = entry.getValue();
            final BlockHandler handler = block.handler();
            if (handler == null) return;
            final Point blockPosition = ChunkUtils.getBlockPosition(index, chunkX, chunkZ);
            handler.tick(new BlockHandler.Tick(block, instance, blockPosition));
        });
    }

    @Override
    public @Nullable Block getBlock(int x, int y, int z, @NotNull Condition condition) {
        assertLock();
        if (y < minSection * CHUNK_SECTION_SIZE || y >= maxSection * CHUNK_SECTION_SIZE)
            return Block.AIR; // Out of bounds

        // Verify if the block object is present
        if (condition != Condition.TYPE) {
            final Block entry = !entries.isEmpty() ?
                    entries.get(getBlockIndex(x, y, z)) : null;
            if (entry != null || condition == Condition.CACHED) {
                return entry;
            }
        }
        // Retrieve the block from state id
        final Section section = getSectionAt(y);
        final int blockStateId = section.blockPalette()
                .get(toSectionRelativeCoordinate(x), toSectionRelativeCoordinate(y), toSectionRelativeCoordinate(z));
        return Objects.requireNonNullElse(Block.fromStateId((short) blockStateId), Block.AIR);
    }

    @Override
    public @NotNull Biome getBiome(int x, int y, int z) {
        assertLock();
        final Section section = getSectionAt(y);
        final int id = section.biomePalette()
                .get(toSectionRelativeCoordinate(x) / 4, toSectionRelativeCoordinate(y) / 4, toSectionRelativeCoordinate(z) / 4);
        return MinecraftServer.getBiomeManager().getById(id);
    }

    @Override
    public long getLastChangeTime() {
        return lastChange;
    }

    @Override
    public void sendChunk(@NotNull Player player) {
        if (!isLoaded()) return;
        player.sendPacket(chunkCache);
    }

    @Override
    public void sendChunk() {
        if (!isLoaded()) return;
        sendPacketToViewers(chunkCache);
    }

    @Override
    public @NotNull Chunk copy(@NotNull Instance instance, int chunkX, int chunkZ) {
        SOTAChunk chunk = new SOTAChunk(instance, chunkX, chunkZ);
        chunk.sections = sections.stream().map(Section::clone).toList();
        chunk.entries.putAll(entries);
        return chunk;
    }

    @Override
    public void reset() {
        for (Section section : sections) section.clear();
        this.entries.clear();
    }

    private synchronized @NotNull ChunkDataPacket createChunkPacket() {
        final NBTCompound heightmapsNBT;
        // TODO: don't hardcode heightmaps
        // Heightmap
        {
            int dimensionHeight = getInstance().getDimensionType().getHeight();
            int[] motionBlocking = new int[16 * 16];
            int[] worldSurface = new int[16 * 16];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    motionBlocking[x + z * 16] = 0;
                    worldSurface[x + z * 16] = dimensionHeight - 1;
                }
            }
            final int bitsForHeight = MathUtils.bitsToRepresent(dimensionHeight);
            heightmapsNBT = NBT.Compound(Map.of(
                    "MOTION_BLOCKING", NBT.LongArray(Utils.encodeBlocks(motionBlocking, bitsForHeight)),
                    "WORLD_SURFACE", NBT.LongArray(Utils.encodeBlocks(worldSurface, bitsForHeight))));
        }
        // Data
        final BinaryWriter writer = new BinaryWriter(PooledBuffers.tempBuffer());
        for (Section section : sections) writer.write(section);
        return new ChunkDataPacket(chunkX, chunkZ,
                new ChunkData(heightmapsNBT, writer.toByteArray(), entries),
                createLightData());
    }

    private synchronized @NotNull UpdateLightPacket createLightPacket() {
        return new UpdateLightPacket(chunkX, chunkZ, createLightData());
    }

    private LightData createLightData() {
        BitSet skyMask = new BitSet();
        BitSet blockMask = new BitSet();
        BitSet emptySkyMask = new BitSet();
        BitSet emptyBlockMask = new BitSet();
        List<byte[]> skyLights = new ArrayList<>();
        List<byte[]> blockLights = new ArrayList<>();

        int index = 0;
        for (Section section : sections) {
            index++;
            final byte[] skyLight = new byte[2048];
            System.out.println("Filled Light");
            Arrays.fill(skyLight, (byte) 255);
            final byte[] blockLight = section.getBlockLight();
            //if (skyLight.length != 0) {
                skyLights.add(skyLight);
                skyMask.set(index);
            //} else {
            //    emptySkyMask.set(index);
            //}
            if (blockLight.length != 0) {
                blockLights.add(blockLight);
                blockMask.set(index);
            } else {
                emptyBlockMask.set(index);
            }
        }
        return new LightData(true,
                skyMask, blockMask,
                emptySkyMask, emptyBlockMask,
                skyLights, blockLights);
    }

    @Override
    public @NotNull ChunkSnapshot updateSnapshot(@NotNull SnapshotUpdater updater) {
        Section[] clonedSections = new Section[sections.size()];
        for (int i = 0; i < clonedSections.length; i++)
            clonedSections[i] = sections.get(i).clone();
        var entities = instance.getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES);
        final int[] entityIds = ArrayUtils.mapToIntArray(entities, Entity::getEntityId);
        return new InstanceSnapshotImpl.Chunk(minSection, chunkX, chunkZ,
                clonedSections, entries.clone(), entityIds, updater.reference(instance),
                tagHandler().readableCopy());
    }

    private void assertLock() {
        assert Thread.holdsLock(this) : "Chunk must be locked before access";
    }

    final class InstanceSnapshotImpl {

        record Instance(AtomicReference<ServerSnapshot> serverRef,
                        DimensionType dimensionType, long worldAge, long time,
                        Map<Long, AtomicReference<ChunkSnapshot>> chunksMap,
                        int[] entitiesIds,
                        TagReadable tagReadable) implements InstanceSnapshot {
            @Override
            public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
                var ref = chunksMap.get(getChunkIndex(chunkX, chunkZ));
                return Objects.requireNonNull(ref, "Chunk not found").getPlain();
            }

            @Override
            public @NotNull Collection<@NotNull ChunkSnapshot> chunks() {
                return MappedCollection.plainReferences(chunksMap.values());
            }

            @Override
            public @NotNull Collection<EntitySnapshot> entities() {
                return new IntMappedArray<>(entitiesIds, id -> server().entity(id));
            }

            @Override
            public @NotNull ServerSnapshot server() {
                return serverRef.getPlain();
            }

            @Override
            public <T> @UnknownNullability T getTag(@NotNull Tag<T> tag) {
                return tagReadable.getTag(tag);
            }
        }

        record Chunk(int minSection, int chunkX, int chunkZ,
                     Section[] sections,
                     Int2ObjectOpenHashMap<Block> blockEntries,
                     int[] entitiesIds,
                     AtomicReference<InstanceSnapshot> instanceRef,
                     TagReadable tagReadable) implements ChunkSnapshot {
            @Override
            public @UnknownNullability Block getBlock(int x, int y, int z, @NotNull Condition condition) {
                // Verify if the block object is present
                if (condition != Condition.TYPE) {
                    final Block entry = !blockEntries.isEmpty() ?
                            blockEntries.get(getBlockIndex(x, y, z)) : null;
                    if (entry != null || condition == Condition.CACHED) {
                        return entry;
                    }
                }
                // Retrieve the block from state id
                final Section section = sections[getChunkCoordinate(y) - minSection];
                final int blockStateId = section.blockPalette()
                        .get(toSectionRelativeCoordinate(x), toSectionRelativeCoordinate(y), toSectionRelativeCoordinate(z));
                return Objects.requireNonNullElse(Block.fromStateId((short) blockStateId), Block.AIR);
            }

            @Override
            public @NotNull Biome getBiome(int x, int y, int z) {
                final Section section = sections[getChunkCoordinate(y) - minSection];
                final int id = section.biomePalette()
                        .get(toSectionRelativeCoordinate(x) / 4, toSectionRelativeCoordinate(y) / 4, toSectionRelativeCoordinate(z) / 4);
                return MinecraftServer.getBiomeManager().getById(id);
            }

            @Override
            public <T> @UnknownNullability T getTag(@NotNull Tag<T> tag) {
                return tagReadable.getTag(tag);
            }

            @Override
            public @NotNull InstanceSnapshot instance() {
                return instanceRef.getPlain();
            }

            @Override
            public @NotNull Collection<@NotNull EntitySnapshot> entities() {
                return new IntMappedArray<>(entitiesIds, id -> instance().server().entity(id));
            }
        }
    }
}
