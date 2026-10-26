package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按当前歌曲生成 Litematica 投影（.litematic）：
 *  - 找出歌曲需要的全部 (音色, 音高) 组合
 *  - 在你伸手可及的范围内自动排布音符盒（每个音符盒正下方放对应音色方块）
 *  - 位置按离你由近到远分配，机器尽量紧凑；避免上下相邻冲突、避开身体所在格子
 *  - 输出到 .minecraft/schematics/dj_<歌名>.litematic
 *
 * 用法：Litematica 加载该投影 → 放置到脚下 → litematica-printer 一键打印 → 回到 DJP 播放。
 */
public final class SchematicExporter {

    /** 每条 音色方块：Block → 该音色 */
    private static final HashMap<Block, NoteBlockInstrument> BLOCK_TO_INSTRUMENT = new HashMap<>();

    static {
        for (NoteBlockInstrument inst : NoteBlockInstrument.values()) {
            Block b = Note.INSTRUMENT_BLOCKS.get(inst);
            if (b != null && b != Blocks.AIR) BLOCK_TO_INSTRUMENT.put(b, inst);
        }
    }

    private SchematicExporter() {}

    public static class Result {
        public final boolean ok;
        public final String message;
        public final Path file;

        Result(boolean ok, String message, Path file) {
            this.ok = ok;
            this.message = message;
            this.file = file;
        }
    }

    /** 主入口：导出当前选中/播放的歌曲 */
    public static Result export(Minecraft client, Song song) {
        if (client.player == null || client.level == null) {
            return new Result(false, "§c[Disc Jockey] 不在游戏里，无法生成投影", null);
        }
        if (song == null) {
            return new Result(false, "§c[Disc Jockey] 先选一首歌，再生成投影", null);
        }

        List<Note> notes = collectNotes(song);
        if (notes.isEmpty()) {
            return new Result(false, "§c[Disc Jockey] 这首歌没有音符数据", null);
        }

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        Vec3 eye = player.getEyePosition();
        BlockPos feet = player.blockPosition();

        // 原版手长（客户端交互距离），留一点余量防止边缘点不到
        double reach = Math.min(player.blockInteractionRange(), 4.5D) - 0.15D;
        double reachSq = reach * reach;

        // ---------- 1. 平面舞台：脚下 1 格 = 音符盒层，再下一格 = 音色方块层 ----------
        // 全部方块都在同一层、都在脚下，从上方一眼看清，不存在"藏在里面够不到"的问题。
        final int planeY = feet.getY() - 1;
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                BlockPos pos = new BlockPos(feet.getX() + dx, planeY, feet.getZ() + dz);
                if (new AABB(pos).distanceToSqr(eye) >= reachSq) continue;    // 手长内
                if (!level.getBlockState(pos).isAir()) continue;              // 要能放音符盒
                if (!level.getBlockState(pos.below()).isAir()) continue;      // 正下方要空（放音色方块）
                candidates.add(pos);
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> new AABB(p).distanceToSqr(eye)));

        if (candidates.isEmpty()) {
            return new Result(false,
                    "§c[Disc Jockey] 脚下两层没有空位：请站在 1×1 的柱子顶上，或把地面挖掉两层后再生成"
                            + "（音符盒需要正下方一格放音色方块）", null);
        }

        // ---------- 2. 分配位置（同一层平台，不存在上下相邻问题） ----------
        List<Placed> placed = new ArrayList<>();
        Set<BlockPos> used = new HashSet<>();
        int skipped = 0;

        for (Note note : notes) {
            BlockPos chosen = null;
            for (BlockPos c : candidates) {
                if (used.contains(c)) continue;
                chosen = c;
                break;
            }
            if (chosen == null) { skipped++; continue; }
            used.add(chosen);
            placed.add(new Placed(chosen, note));
        }

        if (placed.isEmpty()) {
            return new Result(false,
                    "§c[Disc Jockey] 附近没有空位放音符盒，请先站到空旷一点的平地上", null);
        }

        // ---------- 3. 生成 NBT ----------
        try {
            Path dir = client.gameDirectory.toPath().resolve("schematics");
            Files.createDirectories(dir);
            String safeName = song.displayName != null && !song.displayName.isBlank()
                    ? song.displayName : (song.fileName == null ? "song" : song.fileName);
            safeName = safeName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (safeName.length() > 60) safeName = safeName.substring(0, 60);
            Path out = dir.resolve("dj_" + safeName + ".litematic");

            writeLitematic(out, placed, song, feet.getX(), feet.getY(), feet.getZ());

            String msg = "§a[Disc Jockey] 投影已生成：§f" + out.getFileName()
                    + " §7(" + placed.size() + " 个音符盒"
                    + (skipped > 0 ? "，§e还有 " + skipped + " 个音符放不下" : "")
                    + ") §7→ Litematica 加载后放到脚下，再用 printer 打印";
            return new Result(true, msg, out);
        } catch (Throwable t) {
            Main.LOGGER.error("[DJP] 生成投影失败", t);
            return new Result(false, "§c[Disc Jockey] 生成投影失败：" + t.getMessage(), null);
        }
    }

    /** 把歌曲里所有需要的 (音色,音高) 去重列出来（与 Tuner 绑定逻辑一致） */
    private static List<Note> collectNotes(Song song) {
        List<Note> result = new ArrayList<>();
        if (NoteClamper.hasFoldedNotes(song)) {
            HashMap<Long, Boolean> seen = new HashMap<>();
            int version = song.formatVersion & 0xFF;
            for (long foldedNote : song.foldedNotes) {
                byte instrId = (byte) (foldedNote >> Note.INSTRUMENT_SHIFT);
                byte nid = (byte) ((foldedNote >> Note.NOTE_SHIFT) & 0xFF);
                long key = ((long) (instrId & 0xFF) << 8) | (nid & 0xFF);
                if (seen.containsKey(key)) continue;
                seen.put(key, true);
                result.add(new Note(Note.fromNbs(version, instrId & 0xFF), nid));
            }
            return result;
        }
        result.addAll(song.uniqueNotes);
        return result;
    }

    /** 玩家身体两格、以及"身体格正上方"都不能放音符盒（音色方块会卡在身体里） */
    private static boolean isBlockedCell(BlockPos pos, BlockPos feet) {
        return pos.equals(feet) || pos.equals(feet.above())
                || pos.below().equals(feet) || pos.below().equals(feet.above());
    }

    private static final class Placed {
        final BlockPos pos;
        final Note note;

        Placed(BlockPos pos, Note note) {
            this.pos = pos;
            this.note = note;
        }
    }

    // ==================== .litematic 写出 ====================

    private static void writeLitematic(Path file, List<Placed> placed, Song song,
                                       int feetX, int feetY, int feetZ) throws Exception {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        // 收集所有要放的方块：音符盒 + 其下方的音色方块
        List<Cell> cells = new ArrayList<>();
        for (Placed p : placed) {
            cells.add(new Cell(p.pos, noteBlockState(p.note.note())));
            Block instrumentBlock = Note.INSTRUMENT_BLOCKS.get(p.note.instrument());
            if (instrumentBlock != null && instrumentBlock != Blocks.AIR) {
                cells.add(new Cell(p.pos.below(), instrumentBlock.defaultBlockState()));
            }
        }
        for (Cell c : cells) {
            minX = Math.min(minX, c.pos.getX()); maxX = Math.max(maxX, c.pos.getX());
            minY = Math.min(minY, c.pos.getY()); maxY = Math.max(maxY, c.pos.getY());
            minZ = Math.min(minZ, c.pos.getZ()); maxZ = Math.max(maxZ, c.pos.getZ());
        }
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        int volume = sx * sy * sz;

        // 调色板：air + 用到的各种方块状态
        List<BlockState> palette = new ArrayList<>();
        palette.add(Blocks.AIR.defaultBlockState());
        HashMap<BlockState, Integer> indexOf = new HashMap<>();
        indexOf.put(Blocks.AIR.defaultBlockState(), 0);
        int[] grid = new int[volume];
        java.util.Arrays.fill(grid, 0);

        int totalBlocks = 0;
        for (Cell c : cells) {
            Integer idx = indexOf.get(c.state);
            if (idx == null) {
                idx = palette.size();
                palette.add(c.state);
                indexOf.put(c.state, idx);
            }
            int lx = c.pos.getX() - minX, ly = c.pos.getY() - minY, lz = c.pos.getZ() - minZ;
            grid[ly * (sx * sz) + lz * sx + lx] = idx;
            totalBlocks++;
        }

        int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] states = new long[(int) (((long) volume * bits + 63) / 64)];
        for (int i = 0; i < volume; i++) setAt(states, bits, i, grid[i]);

        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);

        CompoundTag meta = new CompoundTag();
        meta.putString("Name", "DJ: " + (song.displayName == null ? "song" : song.displayName));
        meta.putString("Author", "Disc Jockey Plus");
        meta.putString("Description",
                "由 Disc Jockey Plus 生成：平面音符盒舞台。音符盒全部铺在脚下同一层，"
                        + "正下方放好对应音色方块；从上方一眼看清、伸手可及。站在舞台中间演奏即可。");
        meta.putInt("RegionCount", 1);
        meta.putInt("TotalVolume", volume);
        meta.putInt("TotalBlocks", totalBlocks);
        meta.putLong("TimeCreated", System.currentTimeMillis());
        meta.putLong("TimeModified", System.currentTimeMillis());
        CompoundTag enclosing = new CompoundTag();
        enclosing.putInt("x", sx);
        enclosing.putInt("y", sy);
        enclosing.putInt("z", sz);
        meta.put("EnclosingSize", enclosing);
        meta.putIntArray("PreviewImageData", new int[0]);
        root.put("Metadata", meta);

        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) paletteTag.addTag(paletteTag.size(), NbtUtils.writeBlockState(state));

        CompoundTag region = new CompoundTag();
        region.put("BlockStatePalette", paletteTag);
        region.putLongArray("BlockStates", states);
        region.put("TileEntities", new ListTag());
        region.put("Entities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag pos = new CompoundTag();
        // 原点 = 生成时玩家脚下的那格，方便在 Litematica 里对齐（把橙色角块放到脚下）
        pos.putInt("x", minX - feetX);
        pos.putInt("y", minY - feetY);
        pos.putInt("z", minZ - feetZ);
        CompoundTag size = new CompoundTag();
        size.putInt("x", sx);
        size.putInt("y", sy);
        size.putInt("z", sz);
        region.put("Position", pos);
        region.put("Size", size);

        CompoundTag regions = new CompoundTag();
        regions.put("Main", region);
        root.put("Regions", regions);

        NbtIo.writeCompressed(root, file);
    }

    private static BlockState noteBlockState(byte note) {
        return Blocks.NOTE_BLOCK.defaultBlockState()
                .setValue(BlockStateProperties.NOTE, (int) (note & 0x7F) % 25);
    }

    private static void setAt(long[] arr, int bits, long index, int value) {
        long mask = (1L << bits) - 1L;
        long offset = index * bits;
        int startIndex = (int) (offset >> 6);
        int endIndex = (int) (((index + 1) * (long) bits - 1) >> 6);
        int startBit = (int) (offset & 0x3F);
        arr[startIndex] = arr[startIndex] & ~(mask << startBit) | ((long) value & mask) << startBit;
        if (startIndex != endIndex) {
            int endOffset = 64 - startBit;
            int j = bits - endOffset;
            arr[endIndex] = arr[endIndex] >>> j << j | ((long) value & mask) >> endOffset;
        }
    }

    private static final class Cell {
        final BlockPos pos;
        final BlockState state;

        Cell(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }
}