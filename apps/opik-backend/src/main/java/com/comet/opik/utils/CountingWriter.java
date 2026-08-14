package com.comet.opik.utils;

import lombok.Getter;

import java.io.Writer;

/**
 * {@link Writer}，只统计写入的字符数且不持有缓冲区，因此值的序列化大小
 * 可以通过把它流经该 writer 来测量，而不必物化其完整字符串。
 * 一次性使用：每次测量都创建新实例。
 */
@Getter
final class CountingWriter extends Writer {

    private long count;

    @Override
    public void write(char[] buffer, int offset, int length) {
        count += length;
    }

    @Override
    public void write(int character) {
        count++;
    }

    @Override
    public void write(String string, int offset, int length) {
        count += length;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
