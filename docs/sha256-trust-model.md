# SHA-256 trust boundary

## Current guarantee

`expectedSha256` is calculated by the browser and is used as a stable, user-scoped
deduplication and upload-session idempotency key. Finalization verifies that:

- every expected part exists in MinIO;
- every part and the composed object have the expected byte size;
- the final object has an MP4 `ftyp` header;
- a hash repeated in the finalize request matches the declaration stored on the session.

The application does **not** claim that `expectedSha256` is a cryptographically trusted
digest of the final MinIO object.

## Why no full-object SHA-256 claim is made

The current protocol uploads independent temporary objects and then uses MinIO
`ComposeObject`. MinIO Java SDK 8.5.17 exposes ETag and user metadata for the composed
object, but not a server-calculated standard full-object SHA-256. Multipart/composed
ETags are not standard SHA-256 digests.

S3 additional SHA-256 checksums for multipart uploads are composite checksums derived
from part checksums; SHA-256 is not a supported linearizable full-object multipart
checksum. Therefore neither the composed ETag nor a checksum assembled from part
digests can be compared honestly with the browser's standard whole-file SHA-256.

## Minimum viable follow-up

Choose one of these explicitly, because each changes the cost or upload protocol:

1. Run a trusted asynchronous verifier next to object storage, stream the object once,
   record `VERIFIED`/`MISMATCH`, and block analysis until verification succeeds.
2. Move to an S3 implementation and checksum algorithm that supports a server-validated
   full-object multipart checksum (currently CRC-based, not SHA-256), while retaining
   SHA-256 only as the deduplication identifier.

Do not replace either option with ETag comparison or client-supplied object metadata.

References:

- [Amazon S3 object-integrity checks](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)
- [Amazon S3 multipart upload checksums](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)
