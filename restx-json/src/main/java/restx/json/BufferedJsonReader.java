package restx.json;

import java.io.IOException;
import java.io.Reader;

/**
 */
final class BufferedJsonReader extends JsonReader {
    private final Buffers buffers;
    private final TxBuffer tbuf = new TxBuffer();
    private char[] buffer;
    private char[] buffer2;
    private final char[] textbuffer;

    private boolean buffer2Loaded;
    private boolean mayHaveMore;
    private int loaded; // currently loaded chars from bufferStartIndex.

    private final Reader reader;

    private int bufferStartIndex;   // index among all chars of current buffer first char
    private int pos = -1;   // current position in current buffer
    private int line = -1;
    private int startLineIndex = -1;

    BufferedJsonReader(Reader reader, Buffers buffers, int loaded) throws IOException {
        this.buffers = buffers;
        buffer = this.buffers.readerBuffer;
        buffer2 = this.buffers.readerBuffer2;
        textbuffer = this.buffers.textBuffer;
        this.loaded = loaded;
        mayHaveMore = true;
        bufferStartIndex = 0;
        pos = startLineIndex = 0;
        line = 1;
        this.reader = reader;
    }

    public final int readChar() throws IOException {
        pos++;
        loadCurrent();
        return buffer[pos];
    }

    public final int current() {
        return buffer[pos];
    }

    public final int index() {
        return bufferStartIndex + pos;
    }

    public void skip(int skip) throws IOException {
        pos += skip;
        loadCurrent();
    }

    private void loadCurrent() throws IOException {
        if (pos >= loaded) {
            if (!mayHaveMore) {
                if (pos == loaded) {
                    return;
                } else {
                    throw new IOException("can't read at " + index() + ": end of source reached");
                }
            }

            int toskip = pos - loaded;
            if (toskip > 0) {
                long skipped = reader.skip(toskip);
                if (skipped != toskip) {
                    if (skipped == pos - loaded - 1) {
                        return;
                    } else {
                        throw new IOException("can't read at " + index() + ": end of source reached");
                    }
                }

                loaded += skipped;
            }

            buffer2Loaded = false;

            System.out.println("reading from reader");
            int r = reader.read(buffer, 0, buffer.length);
            if (r == -1) {
                if (pos == loaded) {
                    return;
                } else {
                    throw new IOException("can't read at " + index() + ": end of source reached");
                }
            }

            mayHaveMore = r == buffer.length;
            loaded = r;
            bufferStartIndex += pos;
            pos = 0;
        }

        if (buffer2Loaded && pos >= buffer.length) {
            gotoBuffer2();
        }
    }

    public char lookAhead(int i) throws IOException {
        int aheadPos = pos + i;
        if (aheadPos >= loaded) {
            // not enough chars currently available
            if (!mayHaveMore) {
                throw new IOException("can't read at " + (aheadPos + bufferStartIndex) + ": end of source reached");
            }

            if (buffer2Loaded) {
                // we already loaded buffer2, so len already includes what has been loaded in buffer2,
                // and still not sufficient => not supported
                throw new IOException("can't read at " + (aheadPos + bufferStartIndex) + ":" +
                        " looking ahead of more than one buffer (" + buffer2.length + ") is not allowed.");
            }

            int r = reader.read(buffer2, 0, buffer2.length);
            if (r == -1) {
                throw new IOException("can't read at " + (aheadPos + bufferStartIndex) + ": end of source reached");
            }
            mayHaveMore = r == buffer2.length;
            buffer2Loaded = true;

            loaded += r;

            if (aheadPos >= loaded) {
                throw new IOException("can't read at " + (aheadPos + bufferStartIndex) + ": end of source reached");
            }
        }
        if (buffer2Loaded && aheadPos >= buffer.length) {
            return buffer2[aheadPos - buffer.length];
        } else {
            return buffer[aheadPos];
        }
    }

    public String readString() throws IOException {
        int len = buffer.length;
        tbuf.resetWithBuffer(textbuffer);
        for (int j = pos; j < len; j++) {
            if (buffer[j] == '"') {
                String s = new String(buffer, pos, j - pos);
                pos = j;
                return s;
            } else if (buffer[j] == '\\') {
                int charsLen = j - pos;
                tbuf.append(buffer, pos, charsLen);
                pos += charsLen + 1;

                for (j+=2; j < len; j++) {
                    if (buffer[j] == '"') {
                        charsLen = j - pos;
                        tbuf.append(buffer, pos, charsLen);
                        pos += charsLen;
                        String s = tbuf.asString();
                        return s;
                    } else if (buffer[j] == '\\') {
                        charsLen = j - pos;
                        tbuf.append(buffer, pos, charsLen);
                        pos += charsLen + 1;
                        j++;
                    }
                }
            }
        }

        // reached end of current buffer and still not end of String

        // copy chars at end of current buffer if any
        int c = len - pos;
        if (c > 0) {
            tbuf.append(buffer, pos, c);
            pos += c;
        }

        if (buffer2Loaded) {
            gotoBuffer2();
        } else {
            int r = reader.read(buffer, 0, len);
            if (r == -1) {
                throw new IOException("unterminated string " + tbuf.asString() + ": end of source reached");
            }

            mayHaveMore = r == len;
            bufferStartIndex += len;
            loaded = r;
            pos = 0;
        }

        do {
            for (int j = pos; j < len; j++) {
                if (buffer[j] == '"') {
                    int charsLen = j - pos;
                    tbuf.append(buffer, pos, charsLen);
                    pos += charsLen;
                    String s = tbuf.asString();
                    return s;
                } else if (buffer[j] == '\\') {
                    int charsLen = j - pos;
                    tbuf.append(buffer, pos, charsLen);
                    pos += charsLen + 1;
                    j++;
                }
            }
            // reached end of current buffer and still not end of String

            // copy chars at end of current buffer if any
            c = len - pos;
            if (c > 0) {
                tbuf.append(buffer, pos, c);
                pos += c;
            }

            int r = reader.read(buffer, 0, len);
            if (r == -1) {
                throw new IOException("unterminated string " + tbuf.asString() + ": end of source reached");
            }

            mayHaveMore = r == len;
            bufferStartIndex += len;
            loaded = r;
            pos = 0;
        } while (true);
    }

    public String newString(int l) throws IOException {
        if (pos + l > loaded) {
            throw new IllegalArgumentException("can't read string up to never reached char, you must call lookAhead before");
        }

        String s;
        if (buffer2Loaded && pos + l > buffer.length) {
            // string spanned over buffer and buffer2
            char[] c = new char[l];
            System.arraycopy(buffer, pos, c, 0, buffer.length - (pos));
            System.arraycopy(buffer2, 0, c, buffer.length - (pos),
                    l - (buffer.length - (pos)));
            s = new String(c);
        } else {
            s = new String(buffer, pos, l);
        }
        skip(l);
        return s;
    }

    private void gotoBuffer2() {
        bufferStartIndex += buffer.length;
        buffer = buffer2;
        buffer2 = buffer;
        buffer2Loaded = false;
        loaded -= buffer.length;
        pos -= buffer.length;
    }

    @Override
    public void close() throws Exception {
        reader.close();
    }

    @Override
    public void nextLine() {
        line++;
        startLineIndex = bufferStartIndex + pos + 1;
    }

    @Override
    public int line() {
        return line;
    }

    @Override
    public int column() {
        return bufferStartIndex + pos - startLineIndex + 1;
    }
}