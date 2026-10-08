package com.rasteck7.ptdroid.pkg;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Takes P.T.'s three game archives out of a fake PKG (fPKG), as the PC installer's extractor does
 * (installer/Extractor/Program.cs, docs/android.md).
 *
 * The package: its EKPFS is opened with the fake keysets (FakeKeys), the outer PFS image is decrypted with XTS-AES
 * (keys from the EKPFS and the image's seed), its pfs_image.dat is a PFSC (zlib blocks) of the inner PFS image, and the
 * inner image holds the game files. A PlayStation Store package's EKPFS does not open with the fake keys: it is refused
 * with {@link RetailException}.
 *
 * The PKG, PFS and PFSC reading follows LibOrbisPkg (https://github.com/maxton/LibOrbisPkg: PKG/Pkg.cs, PKG/PkgReader.cs,
 * PFS/PfsReader.cs, PFS/PfsStructs.cs, PFS/PFSCReader.cs, PFS/XtsDecryptReader.cs, Util/Crypto.cs), so this file and
 * FakeKeys.java are under LibOrbisPkg's license, the GNU LGPL 3.0 or later (LICENSE-LibOrbisPkg.txt beside them).
 */
public final class PkgExtractor {

    public static final String[] GAME_FILES = { "chunk1.psarc", "texture.qar", "pathid_list_ps4.bin" };

    /** A PlayStation Store package (or a damaged one): its keys are not the fake ones, nothing can be read. */
    public static final class RetailException extends IOException {
        private static final long serialVersionUID = 1L;

        RetailException() {
            super("retail");
        }
    }

    /** A readable package without P.T.'s data. */
    public static final class NotPtException extends IOException {
        private static final long serialVersionUID = 1L;

        NotPtException() {
            super("not P.T.");
        }
    }

    /** Random access to the package file. */
    public interface Source {
        long size() throws IOException;

        /** Reads exactly len bytes at pos. */
        void read(long pos, byte[] buffer, int offset, int len) throws IOException;
    }

    public interface Progress {
        void update(String file, long done, long total);
    }

    /** What a package holds: the files found for the three names, and where they came from. */
    public static final class Contents {
        public String contentId = "";
        public String titleId = "";
        public final List<String> notes = new ArrayList<>();
        final PfsFile[] files = new PfsFile[GAME_FILES.length];

        public long totalSize() {
            long total = 0;
            for (PfsFile f : files) {
                if (f != null) total += f.size;
            }
            return total;
        }
    }

    // ---- readers ----------------------------------------------------------------------------------------------------

    private interface Reader {
        void read(long pos, byte[] buffer, int offset, int len) throws IOException;
    }

    /** The PFS image inside the package, read through a 1 MiB window (the XTS reader asks for one sector at a time). */
    private static final class OffsetReader implements Reader {
        private static final int WINDOW = 1 << 20;
        private final Source source;
        private final long base;
        private final long length;
        private final byte[] window = new byte[WINDOW];
        private long windowStart = -1;
        private int windowLength = 0;

        OffsetReader(Source source, long base, long length) {
            this.source = source;
            this.base = base;
            this.length = length;
        }

        @Override
        public void read(long pos, byte[] buffer, int offset, int len) throws IOException {
            if (pos < 0 || pos + len > length) throw new IOException("read past the PFS image");
            if (len >= WINDOW) {
                source.read(base + pos, buffer, offset, len);
                return;
            }
            while (len > 0) {
                if (windowStart < 0 || pos < windowStart || pos >= windowStart + windowLength) {
                    windowStart = pos - (pos % 0x1000);
                    windowLength = (int) Math.min(WINDOW, length - windowStart);
                    source.read(base + windowStart, window, 0, windowLength);
                }
                int within = (int) (pos - windowStart);
                int n = Math.min(len, windowLength - within);
                System.arraycopy(window, within, buffer, offset, n);
                pos += n;
                offset += n;
                len -= n;
            }
        }
    }

    /** XTS-AES-128 over 0x1000-byte sectors, the sector number as the tweak; sectors before startSector are plain. */
    private static final class XtsReader implements Reader {
        private static final int SECTOR = 0x1000;
        private final Reader inner;
        private final long startSector;
        private final Cipher data;
        private final Cipher tweak;
        private final byte[] sector = new byte[SECTOR];
        private final byte[] masks = new byte[SECTOR];
        private final byte[] tweakIn = new byte[16];
        private final byte[] tweakOut = new byte[16];
        private long cached = -1;

        XtsReader(Reader inner, byte[] dataKey, byte[] tweakKey, long startSector) throws GeneralSecurityException {
            this.inner = inner;
            this.startSector = startSector;
            data = Cipher.getInstance("AES/ECB/NoPadding");
            data.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"));
            tweak = Cipher.getInstance("AES/ECB/NoPadding");
            tweak.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(tweakKey, "AES"));
        }

        private void load(long index) throws IOException {
            if (index == cached) return;
            inner.read(index * SECTOR, sector, 0, SECTOR);
            if (index >= startSector) {
                try {
                    ByteBuffer.wrap(tweakIn).order(ByteOrder.LITTLE_ENDIAN).putLong(0, index).putLong(8, 0);
                    tweak.doFinal(tweakIn, 0, 16, tweakOut, 0);
                    // the tweak of every 16-byte block: multiplied by x in GF(2^128) from one block to the next
                    for (int block = 0; block < SECTOR; block += 16) {
                        System.arraycopy(tweakOut, 0, masks, block, 16);
                        int carry = 0;
                        for (int k = 0; k < 16; ++k) {
                            int b = tweakOut[k] & 0xFF;
                            tweakOut[k] = (byte) ((b << 1) | carry);
                            carry = b >>> 7;
                        }
                        if (carry != 0) tweakOut[0] ^= (byte) 0x87;
                    }
                    for (int i = 0; i < SECTOR; ++i) sector[i] ^= masks[i];
                    data.doFinal(sector, 0, SECTOR, sector, 0);
                    for (int i = 0; i < SECTOR; ++i) sector[i] ^= masks[i];
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
                }
            }
            cached = index;
        }

        @Override
        public void read(long pos, byte[] buffer, int offset, int len) throws IOException {
            while (len > 0) {
                long index = pos / SECTOR;
                int within = (int) (pos % SECTOR);
                int n = Math.min(len, SECTOR - within);
                load(index);
                System.arraycopy(sector, within, buffer, offset, n);
                pos += n;
                offset += n;
                len -= n;
            }
        }
    }

    /** A file's bytes inside a PFS image: one run of blocks, or a list of blocks of blockSize each. */
    private static final class FileReader implements Reader {
        private final Reader image;
        private final long offset;
        private final int[] blocks;
        private final long blockSize;
        private final long size;

        FileReader(Reader image, long offset, int[] blocks, long blockSize, long size) {
            this.image = image;
            this.offset = offset;
            this.blocks = blocks;
            this.blockSize = blockSize;
            this.size = size;
        }

        @Override
        public void read(long pos, byte[] buffer, int off, int len) throws IOException {
            if (pos < 0 || pos + len > Math.max(size, 0)) throw new IOException("read past the end of a PFS file");
            if (blocks == null) {
                image.read(offset + pos, buffer, off, len);
                return;
            }
            while (len > 0) {
                int index = (int) (pos / blockSize);
                int within = (int) (pos % blockSize);
                int n = (int) Math.min(len, blockSize - within);
                image.read((long) blocks[index] * blockSize + within, buffer, off, n);
                pos += n;
                off += n;
                len -= n;
            }
        }
    }

    /** A PFSC image: blocks of blockSize, each stored as is, zlib-compressed, or (larger than a block) as zeroes. */
    private static final class PfscReader implements Reader {
        private final Reader file;
        private final int blockSize;
        private final long blockSize2;
        private final long dataLength;
        private final long[] map;
        private final byte[] block;
        private byte[] compressed = new byte[0];
        private final Inflater inflater = new Inflater(true);
        private long cached = -1;

        PfscReader(Reader file) throws IOException {
            this.file = file;
            byte[] header = new byte[0x30];
            file.read(0, header, 0, header.length);
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getInt(0) != 0x43534650 || h.getInt(4) != 0) throw new IOException("pfs_image.dat is not a PFSC image");
            blockSize = h.getInt(0x0C);
            blockSize2 = h.getLong(0x10);
            long offsets = h.getLong(0x18);
            dataLength = h.getLong(0x28);
            if (blockSize <= 0 || blockSize != blockSize2 || blockSize > (1 << 24) || dataLength < 0) {
                throw new IOException("unexpected PFSC block size");
            }
            long count = dataLength / blockSize2;
            if (count + 1 > Integer.MAX_VALUE / 8) throw new IOException("PFSC image too large");
            byte[] raw = new byte[(int) (count + 1) * 8];
            file.read(offsets, raw, 0, raw.length);
            map = new long[(int) count + 1];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(map);
            block = new byte[blockSize];
        }

        long length() {
            return dataLength;
        }

        private void load(long index) throws IOException {
            if (index == cached) return;
            if (index < 0 || index + 1 >= map.length) throw new IOException("PFSC block out of range");
            long start = map[(int) index];
            long length = map[(int) index + 1] - start;
            if (length == blockSize2) {
                file.read(start, block, 0, blockSize);
            } else if (length > blockSize2) {
                Arrays.fill(block, (byte) 0);
            } else {
                int stored = (int) length - 2;  // a 2-byte zlib header, then raw deflate
                if (stored <= 0) throw new IOException("damaged PFSC block");
                if (compressed.length < stored) compressed = new byte[stored];
                file.read(start + 2, compressed, 0, stored);
                inflater.reset();
                inflater.setInput(compressed, 0, stored);
                int filled = 0;
                try {
                    while (filled < blockSize) {
                        int n = inflater.inflate(block, filled, blockSize - filled);
                        if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                        filled += n;
                    }
                } catch (DataFormatException e) {
                    throw new IOException("damaged PFSC block", e);
                }
                if (filled < blockSize) Arrays.fill(block, filled, blockSize, (byte) 0);
            }
            cached = index;
        }

        @Override
        public void read(long pos, byte[] buffer, int offset, int len) throws IOException {
            if (pos < 0 || pos + len > dataLength) throw new IOException("read past the end of the PFSC image");
            while (len > 0) {
                long index = pos / blockSize;
                int within = (int) (pos % blockSize);
                int n = Math.min(len, blockSize - within);
                load(index);
                System.arraycopy(block, within, buffer, offset, n);
                pos += n;
                offset += n;
                len -= n;
            }
        }
    }

    // ---- PFS --------------------------------------------------------------------------------------------------------

    static final class PfsFile {
        String name;
        String path;
        PfsDir parent;
        long size;
        Reader reader;
    }

    static final class PfsDir {
        String name;
        PfsDir parent;
        final List<PfsFile> files = new ArrayList<>();
        final List<PfsDir> dirs = new ArrayList<>();
    }

    private static final int MODE_SIGNED = 0x1;
    private static final int MODE_ENCRYPTED = 0x4;

    private static final class Inode {
        long size;
        long blocks;
        int[] direct = new int[12];
        int[] indirect = new int[5];
    }

    private static final class Pfs {
        final Reader image;
        final int mode;
        final long blockSize;
        final Inode[] inodes;
        final PfsDir uroot;

        Pfs(Reader raw, byte[] ekpfs, boolean newCrypt) throws IOException {
            byte[] header = new byte[0x400];
            raw.read(0, header, 0, header.length);
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getLong(0) != 1 || h.getLong(8) != 20130315) throw new IOException("not a PFS image");
            mode = h.getShort(0x1C) & 0xFFFF;
            blockSize = h.getInt(0x20) & 0xFFFFFFFFL;
            long inodeCount = h.getLong(0x30);
            long inodeBlocks = h.getLong(0x40);
            if (blockSize < 0x1000 || blockSize > (1 << 24) || inodeCount < 1 || inodeCount > 10_000_000 || inodeBlocks < 1) {
                throw new IOException("damaged PFS header");
            }
            byte[] seed = Arrays.copyOfRange(header, 0x370, 0x380);
            Reader reader = raw;
            if ((mode & MODE_ENCRYPTED) != 0) {
                if (ekpfs == null) throw new IOException("encrypted PFS without a key");
                try {
                    byte[] keyBase = newCrypt ? hmacSha256(ekpfs, seed) : ekpfs;
                    byte[] d = new byte[4 + seed.length];
                    d[0] = 1;  // index 1, little-endian
                    System.arraycopy(seed, 0, d, 4, seed.length);
                    byte[] enc = hmacSha256(keyBase, d);
                    reader = new XtsReader(raw, Arrays.copyOfRange(enc, 16, 32), Arrays.copyOfRange(enc, 0, 16), blockSize / 0x1000);
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
                }
            }
            image = reader;

            final boolean signed = (mode & MODE_SIGNED) != 0;
            final int inodeSize = signed ? 0x2C8 : 0xA8;
            final int perBlock = (int) (blockSize / inodeSize);
            inodes = new Inode[(int) inodeCount];
            byte[] blockBuf = new byte[(int) blockSize];
            int total = 0;
            for (long b = 0; b < inodeBlocks && total < inodeCount; ++b) {
                image.read(blockSize + blockSize * b, blockBuf, 0, blockBuf.length);
                ByteBuffer bb = ByteBuffer.wrap(blockBuf).order(ByteOrder.LITTLE_ENDIAN);
                for (int j = 0; j < perBlock && total < inodeCount; ++j) {
                    int at = j * inodeSize;
                    Inode ino = new Inode();
                    ino.size = bb.getLong(at + 0x08);
                    ino.blocks = bb.getInt(at + 0x60) & 0xFFFFFFFFL;
                    for (int i = 0; i < 12; ++i) {
                        ino.direct[i] = signed ? bb.getInt(at + 0x64 + i * 36 + 32) : bb.getInt(at + 0x64 + i * 4);
                    }
                    for (int i = 0; i < 5; ++i) {
                        ino.indirect[i] = signed ? bb.getInt(at + 0x64 + 12 * 36 + i * 36 + 32) : bb.getInt(at + 0x64 + 12 * 4 + i * 4);
                    }
                    inodes[total++] = ino;
                }
            }
            PfsDir root = loadDir(0, null, "", 0);
            PfsDir found = null;
            for (PfsDir d : root.dirs) {
                if ("uroot".equals(d.name)) found = d;
            }
            if (found == null) throw new IOException("PFS image without uroot");
            uroot = found;
        }

        private Inode inode(long number) throws IOException {
            if (number < 0 || number >= inodes.length) throw new IOException("damaged PFS inode number");
            return inodes[(int) number];
        }

        private PfsDir loadDir(long number, PfsDir parent, String name, int depth) throws IOException {
            if (depth > 32) throw new IOException("PFS directories nested too deep");
            Inode ino = inode(number);
            PfsDir dir = new PfsDir();
            dir.name = name;
            dir.parent = parent;
            int start = ino.direct[0];
            if (ino.blocks < 1 || ino.blocks > 100_000 || start < 1) throw new IOException("damaged PFS directory");
            byte[] buf = new byte[(int) blockSize];
            List<long[]> subdirs = new ArrayList<>();
            List<String> subdirNames = new ArrayList<>();
            for (long x = start; x < start + ino.blocks; ++x) {
                image.read(blockSize * x, buf, 0, buf.length);
                ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                int pos = 0;
                while (pos + 16 <= blockSize) {
                    long child = bb.getInt(pos) & 0xFFFFFFFFL;
                    int type = bb.getInt(pos + 4);
                    int nameLength = bb.getInt(pos + 8);
                    int entSize = bb.getInt(pos + 12);
                    if (entSize == 0) break;
                    if (entSize < 16 || nameLength < 0 || pos + 16 + nameLength > blockSize) throw new IOException("damaged PFS directory entry");
                    String childName = new String(buf, pos + 16, nameLength, StandardCharsets.US_ASCII);
                    int nul = childName.indexOf('\0');
                    if (nul >= 0) childName = childName.substring(0, nul);
                    if (type == 2) {
                        dir.files.add(loadFile(child, dir, childName));
                    } else if (type == 3) {
                        subdirs.add(new long[] { child });
                        subdirNames.add(childName);
                    }
                    pos += entSize;
                }
            }
            for (int i = 0; i < subdirs.size(); ++i) {
                dir.dirs.add(loadDir(subdirs.get(i)[0], dir, subdirNames.get(i), depth + 1));
            }
            return dir;
        }

        private PfsFile loadFile(long number, PfsDir parent, String name) throws IOException {
            Inode ino = inode(number);
            int[] blocks = null;
            if (ino.blocks > 1 && ino.direct[1] != -1) {
                // a file in non-contiguous blocks (signed images): the direct blocks, then the block numbers in the
                // signature tables of the indirect blocks (36 bytes per entry, the number at +32)
                if ((mode & MODE_SIGNED) == 0) throw new IOException("unsigned PFS image with a fragmented file");
                if (ino.blocks > 100_000_000) throw new IOException("damaged PFS file");
                blocks = new int[(int) ino.blocks];
                long remaining = ino.blocks;
                int sigsPerBlock = (int) (blockSize / 36);
                for (int i = 0; i < 12 && i < remaining; ++i) blocks[i] = ino.direct[i];
                remaining -= 12;
                int next = 12;
                byte[] entry = new byte[4];
                for (int i = 0; i < remaining && i < sigsPerBlock; ++i) {
                    image.read((long) ino.indirect[0] * blockSize + i * 36L + 32, entry, 0, 4);
                    blocks[next + i] = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getInt();
                }
                remaining -= sigsPerBlock;
                next += sigsPerBlock;
                for (int j = 0; (long) j * sigsPerBlock < remaining; ++j) {
                    image.read((long) ino.indirect[1] * blockSize + j * 36L + 32, entry, 0, 4);
                    int table = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    for (int i = 0; i < sigsPerBlock && i + (long) j * sigsPerBlock < remaining; ++i) {
                        image.read((long) table * blockSize + i * 36L + 32, entry, 0, 4);
                        blocks[next + i] = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    }
                    next += sigsPerBlock;
                }
                boolean contiguous = true;
                for (int i = 1; i < blocks.length; ++i) {
                    if (blocks[i - 1] + 1 != blocks[i]) {
                        contiguous = false;
                        break;
                    }
                }
                if (contiguous) blocks = null;
            }
            PfsFile file = new PfsFile();
            file.name = name;
            file.parent = parent;
            file.path = (parent != null && parent.name != null && !parent.name.isEmpty() && !"uroot".equals(parent.name) ? pathOf(parent) + "/" : "") + name;
            file.size = ino.size;
            file.reader = new FileReader(image, (long) ino.direct[0] * blockSize, blocks, blockSize, ino.size);
            return file;
        }

        private static String pathOf(PfsDir dir) {
            if (dir.parent == null || "uroot".equals(dir.name)) return "";
            String up = pathOf(dir.parent);
            return up.isEmpty() ? dir.name : up + "/" + dir.name;
        }
    }

    // ---- crypto -----------------------------------------------------------------------------------------------------

    private static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** RSA-2048 private-key decryption with PKCS#1 v1.5 padding; null when the padding does not check out. */
    private static byte[] rsaDecrypt(byte[] ciphertext, FakeKeys.Keyset keyset) {
        BigInteger c = new BigInteger(1, ciphertext);
        if (c.compareTo(keyset.modulus) >= 0) return null;
        byte[] m = c.modPow(keyset.privateExponent, keyset.modulus).toByteArray();
        byte[] em = new byte[256];
        if (m.length > 256) {
            if (m.length != 257 || m[0] != 0) return null;
            System.arraycopy(m, 1, em, 0, 256);
        } else {
            System.arraycopy(m, 0, em, 256 - m.length, m.length);
        }
        if (em[0] != 0 || em[1] != 2) return null;
        int i = 2;
        while (i < em.length && em[i] != 0) ++i;
        if (i < 10 || i >= em.length) return null;  // at least 8 bytes of padding, then the separator
        return Arrays.copyOfRange(em, i + 1, em.length);
    }

    // ---- the package -----------------------------------------------------------------------------------------------

    private static final int ENTRY_KEYS = 0x10;
    private static final int IMAGE_KEY = 0x20;
    private static final String[][] RELEASES = { { "CUSA01127", "US" }, { "CUSA01114", "Europe" }, { "CUSA01098", "Japan" } };

    private static long be64(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static long be32(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).order(ByteOrder.BIG_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    /** True when the first bytes are a PS4 package's ("\x7FCNT"). */
    public static boolean isPkg(Source source) throws IOException {
        if (source.size() < 0x1000) return false;
        byte[] magic = new byte[4];
        source.read(0, magic, 0, 4);
        return magic[0] == 0x7F && magic[1] == 'C' && magic[2] == 'N' && magic[3] == 'T';
    }

    /** Opens the package and finds P.T.'s archives in it, without extracting anything. */
    public static Contents open(Source source) throws IOException {
        long fileSize = source.size();
        if (!isPkg(source)) throw new IOException("not a PS4 package");
        byte[] header = new byte[0x1000];
        source.read(0, header, 0, header.length);
        Contents contents = new Contents();
        int idEnd = 0x40;
        while (idEnd < 0x70 && header[idEnd] != 0) ++idEnd;
        contents.contentId = new String(header, 0x40, idEnd - 0x40, StandardCharsets.US_ASCII);
        contents.titleId = contents.contentId.length() >= 16 ? contents.contentId.substring(7, 16) : "";
        long entryCount = be32(header, 0x10);
        long entryTable = be32(header, 0x18);
        long pfsFlags = be64(header, 0x408);
        long pfsOffset = be64(header, 0x410);
        long pfsSize = be64(header, 0x418);
        if (entryCount > 4096 || entryTable + entryCount * 32 > fileSize) throw new IOException("damaged PKG entry table");
        if (pfsOffset < 0 || pfsSize <= 0 || pfsOffset > fileSize || pfsSize > fileSize - pfsOffset) {
            throw new IOException("the PKG is incomplete (truncated PFS image)");
        }

        // the EKPFS, as LibOrbisPkg's Pkg.GetEkpfs: entry key 3 opens the image key, which holds the EKPFS
        byte[] table = new byte[(int) entryCount * 32];
        source.read(entryTable, table, 0, table.length);
        byte[] key3 = null;
        byte[] imageKey = null;
        byte[] imageKeyMeta = null;
        for (int i = 0; i < entryCount; ++i) {
            int at = i * 32;
            long id = be32(table, at);
            long dataOffset = be32(table, at + 16);
            long dataSize = be32(table, at + 20);
            if (dataOffset + dataSize > fileSize) continue;
            if (id == ENTRY_KEYS && dataSize >= 32 + 7 * 32 + 7 * 256) {
                key3 = new byte[256];
                source.read(dataOffset + 32 + 7 * 32 + 3 * 256, key3, 0, 256);
            } else if (id == IMAGE_KEY && dataSize > 0 && dataSize <= 0x1000 && dataSize % 16 == 0) {
                imageKey = new byte[(int) dataSize];
                source.read(dataOffset, imageKey, 0, imageKey.length);
                imageKeyMeta = new byte[32];
                System.arraycopy(table, at, imageKeyMeta, 0, 24);  // id .. data size; the 8 padding bytes as zeroes
            }
        }
        if (key3 == null || imageKey == null) throw new RetailException();
        byte[] ekpfs;
        try {
            byte[] dk3 = rsaDecrypt(key3, FakeKeys.PKG_DERIVED_KEY3);
            if (dk3 == null) throw new RetailException();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(imageKeyMeta);
            sha.update(dk3);
            byte[] ivKey = sha.digest();
            Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
            cbc.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(ivKey, 16, 32), "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(ivKey, 0, 16)));
            byte[] decrypted = cbc.doFinal(imageKey);
            if (decrypted.length < 256) throw new RetailException();
            ekpfs = rsaDecrypt(Arrays.copyOf(decrypted, 256), FakeKeys.FAKE);
            if (ekpfs == null || ekpfs.length != 32) throw new RetailException();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }

        Pfs outer = new Pfs(new OffsetReader(source, pfsOffset, pfsSize), ekpfs, (pfsFlags & 0x2000000000000000L) != 0);
        PfsFile image = null;
        for (PfsFile f : outer.uroot.files) {
            if ("pfs_image.dat".equals(f.name)) image = f;
        }
        if (image == null) throw new IOException("the PKG has no pfs_image.dat");
        PfscReader pfsc = new PfscReader(image.reader);
        Pfs inner = new Pfs(pfsc, null, false);

        List<PfsFile> all = new ArrayList<>();
        collect(inner.uroot, all);
        contents.files[0] = choose(all, inner.uroot, contents.notes, "chunk1.psarc", ".psarc", PkgExtractor::isPsarc);
        contents.files[1] = choose(all, inner.uroot, contents.notes, "texture.qar", ".qar", PkgExtractor::isQar);
        contents.files[2] = choose(all, inner.uroot, contents.notes, "pathid_list_ps4.bin", "pathid_list", f -> f.size >= 32);
        if (contents.files[0] == null || contents.files[1] == null) throw new NotPtException();
        byte[] pathid = new byte[0];
        PfsFile list = contents.files[2];
        if (list != null && list.size < 64L * 1024 * 1024) {
            pathid = new byte[(int) list.size];
            list.reader.read(0, pathid, 0, pathid.length);
        }
        boolean known = false;
        String region = null;
        for (String[] release : RELEASES) {
            if (release[0].equals(contents.titleId)) {
                known = true;
                region = release[1];
            }
        }
        if (!new String(pathid, StandardCharsets.ISO_8859_1).contains("pt14_") && !known) throw new NotPtException();
        if (region == null) contents.notes.add("content ID " + contents.contentId + " is not a known P.T. release");
        else if (!"US".equals(region)) contents.notes.add(contents.titleId + " is the " + region + " release; the port is tested with CUSA01127");
        if (list == null) contents.notes.add("no pathid_list_ps4.bin");
        return contents;
    }

    private interface Check {
        boolean ok(PfsFile file) throws IOException;
    }

    private static void collect(PfsDir dir, List<PfsFile> out) {
        out.addAll(dir.files);
        for (PfsDir d : dir.dirs) {
            if (!"sce_sys".equals(d.name) && !"sce_module".equals(d.name)) collect(d, out);
        }
    }

    private static PfsFile choose(List<PfsFile> files, PfsDir root, List<String> notes, String name, String kind, Check valid) throws IOException {
        for (PfsFile f : files) {
            if (f.name.equals(name) && f.parent == root && valid.ok(f)) return f;
        }
        PfsFile best = null;
        for (PfsFile f : files) {
            String lower = f.name.toLowerCase(Locale.ROOT);
            boolean matches = kind.startsWith(".") ? lower.endsWith(kind) : lower.contains(kind);
            if (matches && valid.ok(f) && (best == null || f.size > best.size)) best = f;
        }
        if (best != null) notes.add(name + " not at the package root; using " + best.path);
        return best;
    }

    private static boolean isPsarc(PfsFile f) throws IOException {
        if (f.size < 4) return false;
        byte[] b = new byte[4];
        f.reader.read(0, b, 0, 4);
        return b[0] == 'P' && b[1] == 'S' && b[2] == 'A' && b[3] == 'R';
    }

    private static boolean isQar(PfsFile f) throws IOException {
        if (f.size < 0x24) return false;
        byte[] b = new byte[0x24];
        f.reader.read(f.size - 0x24, b, 0, 0x24);
        return b[0x16] == 'a' && b[0x17] == 'q';
    }

    /** Writes the three archives into the folder (name.part, then renamed), with progress over all of them. */
    public static void extract(Contents contents, File folder, Progress progress) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        long total = contents.totalSize();
        long done = 0;
        byte[] buffer = new byte[1 << 20];
        for (int i = 0; i < GAME_FILES.length; ++i) {
            PfsFile file = contents.files[i];
            if (file == null) continue;
            if (file.size < 0 || file.size > (8L << 30)) throw new IOException("unexpected archive size");
            String name = GAME_FILES[i];
            File part = new File(folder, name + ".part");
            File target = new File(folder, name);
            try (OutputStream out = new FileOutputStream(part)) {
                for (long pos = 0; pos < file.size;) {
                    int n = (int) Math.min(buffer.length, file.size - pos);
                    file.reader.read(pos, buffer, 0, n);
                    out.write(buffer, 0, n);
                    pos += n;
                    done += n;
                    if (progress != null) progress.update(name, done, total);
                    if (Thread.interrupted()) throw new IOException("cancelled");
                }
            } catch (IOException e) {
                part.delete();
                throw e;
            }
            target.delete();
            if (!part.renameTo(target)) throw new IOException("cannot save " + name);
        }
        StringBuilder source = new StringBuilder();
        source.append("content_id=").append(contents.contentId).append('\n');
        source.append("title_id=").append(contents.titleId).append('\n');
        source.append("source=pkg\n");
        for (String note : contents.notes) source.append("note=").append(note).append('\n');
        try (OutputStream out = new FileOutputStream(new File(folder, "source.txt"))) {
            out.write(source.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
