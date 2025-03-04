package com.epam.aidial.core.storage.util;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

@UtilityClass
@Slf4j
public class Compression {

    @SneakyThrows
    public byte[] compress(String type, byte[] data) {
        if (!type.equals("gzip")) {
            throw new IllegalArgumentException("Unsupported compression: " + type);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream stream = new GZIPOutputStream(output)) {
            stream.write(data);
        }
        return output.toByteArray();
    }

    @SneakyThrows
    public byte[] decompress(String type, byte[] input) {
        if (!type.equals("gzip")) {
            throw new IllegalArgumentException("Unsupported compression: " + type);
        }
        try (InputStream decompressed = new GZIPInputStream(new ByteArrayInputStream(input))) {
            return decompressed.readAllBytes();
        } catch (ZipException e) {
            // special case for GCP cloud storage, due to jclouds bug https://issues.apache.org/jira/projects/JCLOUDS/issues/JCLOUDS-1633
            log.warn("Failed to decompress provided input: {}", e.getMessage());
            return input;
        }
    }
}