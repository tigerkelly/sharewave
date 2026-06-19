package com.sharewave.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal POSIX ustar tar archive writer.
 *
 * Supports plain files only (no directories, symlinks, or extended/PAX
 * headers) — exactly what's needed for bundling a flat set of uploaded
 * files into one .tar for download. No external dependencies; java.util.zip
 * provides ZIP support but the JDK has no built-in tar writer, so this
 * implements just enough of the ustar format (POSIX 1003.1-1990) to be
 * read by GNU tar, BSD tar, 7-Zip, and other standard tools.
 */
final class TarWriter implements AutoCloseable {
    private static final int BLOCK = 512;

    private final OutputStream out;

    TarWriter(OutputStream out) {
        this.out = out;
    }

    /**
     * Writes one file entry: a 512-byte ustar header followed by the file
     * content padded to the next 512-byte boundary.
     *
     * @param name      entry name as it should appear in the archive
     * @param size      exact number of bytes that will be read from {@code in}
     * @param in        file content; read fully and not closed by this method
     */
    void putEntry(String name, long size, InputStream in) throws IOException {
        out.write(buildHeader(name, size));

        byte[] buf = new byte[8192];
        long remaining = size;
        int n;
        while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
            out.write(buf, 0, n);
            remaining -= n;
        }

        long padding = (BLOCK - (size % BLOCK)) % BLOCK;
        if (padding > 0) out.write(new byte[(int) padding]);
    }

    /** Writes the two 512-byte zero blocks that mark the end of a tar archive. */
    void finish() throws IOException {
        out.write(new byte[BLOCK]);
        out.write(new byte[BLOCK]);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private static byte[] buildHeader(String name, long size) {
        byte[] header = new byte[BLOCK];

        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        // ustar "name" field is 100 bytes; names longer than that aren't
        // supported by this minimal writer (not needed for flat filenames).
        int nameLen = Math.min(nameBytes.length, 100);
        System.arraycopy(nameBytes, 0, header, 0, nameLen);

        writeOctal(header, 100, 8, 0644);              // mode
        writeOctal(header, 108, 8, 0);                  // uid
        writeOctal(header, 116, 8, 0);                  // gid
        writeOctal(header, 124, 12, size);               // size
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000); // mtime
        // checksum field (148..156) intentionally left as spaces for now, filled below
        for (int i = 148; i < 156; i++) header[i] = ' ';
        header[156] = '0';                              // typeflag: '0' = regular file
        // magic + version: "ustar\0" + "00"
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[263] = '0';
        header[264] = '0';

        long checksum = 0;
        for (byte b : header) checksum += (b & 0xFF);
        // Checksum field format is special: 6 octal digits, then NUL, then
        // space (not the generic "digits + trailing NUL" used elsewhere).
        String checksumOctal = String.format("%06o", checksum);
        byte[] checksumBytes = checksumOctal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksumBytes, 0, header, 148, 6);
        header[154] = 0;
        header[155] = ' ';

        return header;
    }

    /** Writes an unsigned octal number, left-padded with zeros, NUL-terminated, right-aligned in the field. */
    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        int padLen = length - 1; // reserve last byte for NUL
        if (octal.length() > padLen) octal = octal.substring(octal.length() - padLen);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < padLen - octal.length(); i++) sb.append('0');
        sb.append(octal);
        byte[] bytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + length - 1] = 0;
    }
}
