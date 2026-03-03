/*******************************************************************************
 * Copyright (c) 2026 Groovy Language Server Contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.groovy.ls.core.providers;

import org.eclipse.lsp4j.Position;

/**
 * Shared utility for converting between character offsets and LSP
 * {@link Position} (line, column) in source text.
 * <p>
 * Two modes of use:
 * <ol>
 *   <li><b>Static one-shot methods</b> — identical to the duplicated
 *       helpers in every provider class; O(n) per call.</li>
 *   <li><b>{@link LineIndex}</b> — pre-computes line-start offsets once,
 *       then provides O(log n) lookups. Use this when multiple offset↔
 *       position conversions are needed on the same content.</li>
 * </ol>
 */
public final class PositionUtils {

    private PositionUtils() {
        // utility class
    }

    // ---- One-shot helpers (O(n) per call, no allocation) ----

    /**
     * Convert a character offset to an LSP {@link Position}.
     *
     * @param content the full source text
     * @param offset  the 0-based character offset
     * @return the corresponding LSP position
     */
    public static Position offsetToPosition(String content, int offset) {
        int line = 0;
        int col = 0;
        int safeOffset = Math.min(offset, content.length());
        for (int i = 0; i < safeOffset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new Position(line, col);
    }

    /**
     * Convert an LSP {@link Position} to a character offset.
     *
     * @param content  the full source text
     * @param position the LSP position (0-based line and column)
     * @return the 0-based character offset
     */
    public static int positionToOffset(String content, Position position) {
        int line = 0;
        int offset = 0;
        while (offset < content.length() && line < position.getLine()) {
            if (content.charAt(offset) == '\n') {
                line++;
            }
            offset++;
        }
        return Math.min(offset + position.getCharacter(), content.length());
    }

    // ---- Pre-computed line index (O(n) build, O(log n) per lookup) ----

    /**
     * A pre-computed index of line-start offsets for a source string.
     * Build once with {@link #buildLineIndex(String)}, then call
     * {@link #offsetToPosition(int)} or {@link #positionToOffset(Position)}
     * in O(log n) / O(1) time.
     */
    public static final class LineIndex {
        /** Offset of the first character of each line. */
        private final int[] lineStarts;
        private final int contentLength;

        private LineIndex(int[] lineStarts, int contentLength) {
            this.lineStarts = lineStarts;
            this.contentLength = contentLength;
        }

        /**
         * Convert an offset to an LSP position in O(log n).
         */
        public Position offsetToPosition(int offset) {
            int safeOffset = Math.min(offset, contentLength);
            int line = java.util.Arrays.binarySearch(lineStarts, safeOffset);
            if (line < 0) {
                // binarySearch returns -(insertion point) - 1
                line = -line - 2;
            }
            if (line < 0) line = 0;
            int col = safeOffset - lineStarts[line];
            return new Position(line, col);
        }

        /**
         * Convert an LSP position to an offset in O(1).
         */
        public int positionToOffset(Position position) {
            int line = position.getLine();
            if (line < 0 || line >= lineStarts.length) {
                return contentLength;
            }
            return Math.min(lineStarts[line] + position.getCharacter(), contentLength);
        }

        /**
         * Returns the text of a given line (without the trailing newline).
         * Useful for visitors that need line content (e.g., inlay hints,
         * semantic tokens).
         *
         * @param source the original source string used to build this index
         * @param line   0-based line number
         * @return the line text, or empty string if out of range
         */
        public String getLineText(String source, int line) {
            if (line < 0 || line >= lineStarts.length) {
                return "";
            }
            int start = lineStarts[line];
            int end = (line + 1 < lineStarts.length)
                    ? lineStarts[line + 1] - 1  // -1 to exclude the \n
                    : contentLength;
            if (end > start && end <= source.length() && source.charAt(end - 1) == '\r') {
                end--; // strip trailing \r
            }
            return source.substring(start, Math.min(end, source.length()));
        }

        /**
         * Returns the number of lines.
         */
        public int lineCount() {
            return lineStarts.length;
        }
    }

    /**
     * Build a {@link LineIndex} for the given source content.
     * Time: O(n), space: O(lines).
     */
    public static LineIndex buildLineIndex(String content) {
        // Count lines first (avoids ArrayList / resizing overhead)
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }

        int[] lineStarts = new int[lines];
        lineStarts[0] = 0;
        int lineIdx = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lineStarts[lineIdx++] = i + 1;
            }
        }

        return new LineIndex(lineStarts, content.length());
    }
}
