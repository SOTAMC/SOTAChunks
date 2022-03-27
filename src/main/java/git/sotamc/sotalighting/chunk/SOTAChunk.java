package git.sotamc.sotalighting.chunk;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.data.ChunkData;
import net.minestom.server.network.packet.server.play.data.LightData;
import net.minestom.server.utils.MathUtils;
import net.minestom.server.utils.Utils;
import net.minestom.server.utils.binary.BinaryWriter;
import net.minestom.server.utils.binary.PooledBuffers;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.world.biomes.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnknownNullability;
import org.jglrxavpok.hephaistos.nbt.NBT;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;

import java.util.*;

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
    private final List<Section> sections;
    private long lastChangeTime;

    // Key = ChunkUtils#getBlockIndex
    protected final Int2ObjectOpenHashMap<Block> entries = new Int2ObjectOpenHashMap<>(0);
    protected final Int2ObjectOpenHashMap<Block> tickableMap = new Int2ObjectOpenHashMap<>(0);

    // Cache of generated Chunks
    @SuppressWarnings("UnstableApiUsage")
    private final CachedPacket chunkCache = new CachedPacket(this::createChunkPacket);
    //@SuppressWarnings("UnstableApiUsage")
    // TODO private final CachedPacket lightCache = new CachedPacket(this::createLightPacket);

    public SOTAChunk(@NotNull Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ, true);

        // Fills the sections list will empty sections.
        Section[] emptySection = new Section[getMaxSection()-getMaxSection()];
        Arrays.setAll(emptySection, value -> new Section());
        this.sections = List.of(emptySection);

    }

    @Override
    public void setBlock(int x, int y, int z, @NotNull Block block) {
        assert Thread.holdsLock(this) : "Chunk must be locked before access";
        lastChangeTime = System.currentTimeMillis();

        Section section = getSection(y);
        section.blockPalette().set(x, y, z, block.stateId());

        @SuppressWarnings("UnstableApiUsage")
        int index = ChunkUtils.getBlockIndex(x, y, z);
        BlockHandler handler = block.handler();

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
    public @NotNull List<Section> getSections() {
        return sections;
    }

    @Override
    public @NotNull Section getSection(int section) {
        return sections.get(section-getMinSection());
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void tick(long time) {
        if(tickableMap.isEmpty()) return;
        tickableMap.int2ObjectEntrySet().fastForEach(entry -> {
            int index = entry.getIntKey();
            Block block = entry.getValue();
            BlockHandler handler = block.handler();
            if(handler != null){
                Point blockPosition = ChunkUtils.getBlockPosition(index, chunkX, chunkZ);
                handler.tick(new BlockHandler.Tick(block, instance, blockPosition));
            }
        });
    }

    @Override
    public long getLastChangeTime() {
        return lastChangeTime;
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void sendChunk(@NotNull Player player) {
        if(!isLoaded()){
            player.sendPacket(chunkCache);
        }
        // TODO : Generates a chunk for player.
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void sendChunk() {
        if(!isLoaded()){
            sendPacketsToViewers(chunkCache);
        }
        // TODO : Generates a chunk.
    }

    @Override
    public @NotNull Chunk copy(@NotNull Instance instance, int chunkX, int chunkZ) {
        return new SOTAChunk(super.instance, super.chunkX, super.chunkZ);
    }

    @Override
    public void reset() {
        for(Section section : sections){
            section.clear();
        }
        this.entries.clear();
        // TODO : Undo generation of a chunk.
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public @UnknownNullability Block getBlock(int x, int y, int z, @NotNull Condition condition) {
        assert Thread.holdsLock(this) : "Chunk must be locked before access";
        if(y < minSection * CHUNK_SECTION_SIZE || y >= maxSection * CHUNK_SECTION_SIZE){
            return Block.AIR;
        }

        if(condition != Condition.TYPE) {
            Block entry = !entries.isEmpty() ? entries.get(ChunkUtils.getBlockIndex(x, y, z)) : null;
            if (entry != null || condition == Condition.CACHED) {
                return entry;
            }
        }

        Section section = getSection(y);
        final int blockStateID = section.blockPalette().get(x, y, z);
        return Block.fromStateId((short) blockStateID) != null ? Block.fromStateId((short) blockStateID) : Block.AIR;
    }

    @Override
    public @NotNull Block getBlock(int x, int y, int z){
        // TODO : Needs to be implemented. Finds block in chunk at coordinates.
        // TODO : Add light engine to block calculation.
        return super.getBlock(x, y, z);
    }

    @Override
    public @NotNull Biome getBiome(int x, int y, int z) {
        // TODO : This probably won't be implemented - Biomes not used.
        return Biome.PLAINS;
    }

    @Override
    public void setBiome(int x, int y, int z, @NotNull Biome biome) {
        // TODO : This probably won't be implemented - Biomes not used.
    }

    @SuppressWarnings("UnstableApiUsage")
    private synchronized @NotNull ChunkDataPacket createChunkPacket(){
        // TODO NOW
        // TODO : Don't hardcode heightmaps
        NBTCompound heightmapsNBT;

        // Hardcoded
        int dimensionHeight = getInstance().getDimensionType().getHeight();
        int[] worldSurface = new int[CHUNK_SIZE_X * CHUNK_SIZE_Z];
        int[] motionBlocking = new int[CHUNK_SIZE_X * CHUNK_SIZE_Z];

        for(int x = 0; x < CHUNK_SIZE_X; x++){
            for(int z = 0; z < CHUNK_SIZE_Z; z++){
                motionBlocking[x + z * 16] = 0;
                worldSurface[x + z * 16] = dimensionHeight - 1;
            }
        }

        int bitsForHeight = MathUtils.bitsToRepresent(dimensionHeight);
        heightmapsNBT = NBT.Compound(Map.of(
                "MOTION_BLOCKING", NBT.LongArray(Utils.encodeBlocks(motionBlocking, bitsForHeight)),
                "WORLD_SURFACE", NBT.LongArray(Utils.encodeBlocks(worldSurface, bitsForHeight))
        ));

        BinaryWriter writer = new BinaryWriter(PooledBuffers.tempBuffer());
        for(Section section : sections) {
            writer.write(section);
        }
        return new ChunkDataPacket(
                chunkX,
                chunkZ,
                new ChunkData(heightmapsNBT, writer.toByteArray(), entries),
                createLightData());

    }

    /* TODO Create Light
    private synchronized @NotNull UpdateLightPacket createLightPacket(){
        return new UpdateLightPacket(chunkX, chunkZ, createLightData());
    }
    */

    private LightData createLightData() {
        // TODO NOW
        BitSet skyMask = new BitSet();
        BitSet blockMask = new BitSet();
        BitSet emptySkyMask = new BitSet();
        BitSet emptyBlockMask = new BitSet();
        List<byte[]> skyLights = new ArrayList<>();
        List<byte[]> blockLights = new ArrayList<>();

        int index = 0;
        for(Section section : sections){
            index++;
            byte[] skyLight = section.getSkyLight();
            byte[] blockLight = section.getBlockLight();
            if(skyLight.length != 0){
                skyLights.add(skyLight);
                skyMask.set(index);
            }
            else{
                emptySkyMask.set(index);
            }
            if(blockLight.length != 0) {
                blockLights.add(blockLight);
                blockMask.set(index);
            }
            else{
                emptyBlockMask.set(index);
            }
        }
        return new LightData(
                true,
                skyMask,
                blockMask,
                emptySkyMask,
                emptyBlockMask,
                skyLights,
                blockLights
        );
    }
}
