package com.comet.opik.utils;

import lombok.Getter;

import java.io.OutputStream;

/**
 * {@link OutputStream}，只统计写入的字节数且不持有缓冲区，因此值的 UTF-8 序列化
 * 大小可以通过把它流经该流来测量，而不必物化其完整
 * 字节数组。一次性使用：每次测量都创建新实例。
 */
@Getter
final class CountingOutputStream extends OutputStream {

    private long count;

    @Override
    public void write(int b) {
        count++;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        count += length;
    }
}
