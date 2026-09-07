package com.videoagent.upload.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

final class UploadKeyPolicy {

    private UploadKeyPolicy() {
    }

    static String finalObjectKey() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        return "videos/%d/%02d/%02d/%s.mp4".formatted(
            date.getYear(), date.getMonthValue(), date.getDayOfMonth(), UUID.randomUUID()
        );
    }

    static String tempPrefix(String uploadId) {
        return "uploads/" + uploadId + "/parts";
    }

    static String partObjectKey(String tempPrefix, int partNumber) {
        if (isLegacy(tempPrefix)) {
            return tempPrefix + "/part-%05d".formatted(partNumber);
        }
        return tempPrefix + "/" + partNumber;
    }

    static int firstPartNumber(String tempPrefix) {
        return isLegacy(tempPrefix) ? 1 : 0;
    }

    private static boolean isLegacy(String tempPrefix) {
        return tempPrefix.startsWith("upload-parts/");
    }
}
