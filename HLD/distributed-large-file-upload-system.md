## Large File Upload System (Resumable, Scalable, Fault‑Tolerant) — Production‑Grade HLD

This document designs a **distributed large-file upload platform** that supports **GB–TB uploads**, **resumability**, **parallel chunk uploads**, **multi-device resume**, and **post-upload processing** (virus scan, unzip, validation) while staying **cost-efficient** and **highly scalable**.

---

## Executive summary

### Key idea

- **Never proxy the full file through your backend**.
- The backend owns **auth + orchestration + metadata**.
- The client uploads directly to **object storage** (e.g., **Amazon S3**) using **pre-signed URLs**.

### Why this is the right pattern

- **Scalability**: upload bandwidth does not hit your service; it hits storage/CDN.
- **Reliability**: storage services are built for massive concurrency + durability.
- **Cost**: avoids expensive egress/compute on your backend.
- **Security**: least-privilege, time-bound upload permissions via signed URLs.

---

## Requirements

### Functional requirements

- Upload **very large files** (e.g., 1\text{ GB} \rightarrow 5\text{ TB}).
- **Resumable uploads** after:
  - network failures
  - browser/app crashes
  - device power loss
- **Parallel chunk upload** (configurable concurrency).
- Pause / resume / cancel upload.
- **Multi-device resume** (same user can resume from another device).
- Track progress and show per-chunk status.
- Post-upload workflow:
  - virus scan
  - unzip (safe extraction)
  - validation/transcoding/etc.
- Optional: de-duplication and “instant upload” when file already exists.

### Non-functional requirements (targets)

- **Availability**: 99.99% for control-plane APIs (initiate/status/complete).
- **Durability**: delegated to object storage (e.g., “11 9s” class durability).
- **Integrity**: end-to-end file verification (SHA-256).
- **Resume latency**: status fetch < 1s (typical).
- **Concurrency**: up to ~1M concurrent in-progress uploads (order-of-magnitude).
- **Security**: least-privilege access, encryption, WAF/rate limits, abuse protection.

### Constraints (important for design)

- **S3 multipart upload limits** (commonly relied upon):
  - max object size: **5 TB**
  - min part size: **5 MB** (except last)
  - max parts: **10,000**
- Browsers/mobile constraints:
  - memory (don’t load whole file into RAM)
  - intermittent connectivity
  - background/foreground lifecycle

---

## System overview (HLD)

### Data-plane vs control-plane

- **Data-plane**: actual bytes of the file.
  - goes **Client → Object Storage**
- **Control-plane**: metadata + orchestration.
  - goes **Client → API Gateway → Upload Service (+ DB/Cache/Queue)**

### Deployment view (where things run)

```text
                (Multi-AZ / Multi-instance)

        [ Clients ] 
            |
            v
  [ API Gateway / LB / WAF ]  (edge)
            |
            v
  [ Upload Service pods/VMs ] (stateless, autoscaled)
       |            |
       |            +--> [ Redis cluster ]      (fast state)
       +----------------> [ DB cluster ]        (source of truth)
       +----------------> [ Object Storage ]    (data-plane + multipart control)
       +----------------> [ Queue ]             (async processing)
                              |
                              v
                        [ Worker fleet ]        (autoscaled)
```

### Primary architecture (arrow diagram)

```text
[ Client (Web/Mobile) ]
        |
        | (1) POST /uploads/initiate  { fileName, fileSize, mimeType, optional fileHashSha256 }
        v
+------------------------------+
| API Gateway / LB / WAF       |
+------------------------------+
        |
        v
+------------------------------+        +------------------+
| Upload Service (stateless)   | -----> | Auth (JWT)       |
+------------------------------+        +------------------+
        |
        |-- write/read metadata ----------------------------+
        |                                                   |
        v                                                   v
+------------------------------+        +------------------------------+
| Metadata DB (sharded)        |        | Redis (fast part tracking)   |
+------------------------------+        +------------------------------+
        |
        |-- S3 control-plane (no file bytes)  CreateMultipartUpload / ListParts
        v
+------------------------------+
| Object Storage (S3)          |
| - Multipart session created  |
+------------------------------+
        ^
        |
        | (2) UploadSvc returns:
        |     uploadId, chunkSize, totalParts, presign-range endpoint (or URLs)
        |
[ Client (Web/Mobile) ]

================================================================================
DATA-PLANE (BYTES): Client uploads parts directly to storage
================================================================================

[ Client ] -- (3) PUT Part #n (parallel, via pre-signed URL) ------------------> [ S3 ]
   ^                                                                               |
   |                                                                               | returns ETag for part n
   +-- (4) POST parts/{n}:complete { etag, optional checksum } --> [ Gateway ] --> [ UploadSvc ] --> [ DB/Redis ]

================================================================================
RESUME / COMPLETE
================================================================================

[ Client ] -- (5) GET /uploads/{uploadId} ------------------> [ UploadSvc ] --> [ Redis/DB ]  -- (6) missing parts --> [ Client ]

[ Client ] -- (7) POST /uploads/{uploadId}:complete --------> [ UploadSvc ] -- CompleteMultipartUpload --> [ S3 ]
                                                |
                                                +-- verify size + (optional async) SHA-256 of final object
                                                +-- status -> COMPLETED / PROCESSING
                                                v
                                      [ Queue (Kafka/SQS) ] ---> [ Workers ] ---> [ Processed storage / DB ]

================================================================================
BACKGROUND
================================================================================

[ Scheduled jobs ] -> cleanup DB rows + AbortMultipartUpload for stale sessions
```

### Primary architecture (Mermaid flowchart — prettier in many viewers)

```mermaid
flowchart TB
  C[Client<br/>(Web / Mobile)]
  G[API Gateway / LB / WAF]
  U[Upload Service<br/>(Stateless)]
  A[Auth / JWT validation]
  D[(Metadata DB<br/>(Sharded))]
  R[(Redis<br/>(Uploaded parts cache))]
  S3[(Object Storage<br/>(S3 / Blob))]
  Q[Message Queue<br/>(Kafka / SQS)]
  W[Workers<br/>(Scan / Unzip / Validate)]
  J[Scheduled jobs<br/>(Cleanup / Abort multipart)]

  C -->|1) Initiate| G --> U
  U --> A
  U --> D
  U --> R
  U -->|CreateMultipartUpload / ListParts| S3
  U -->|2) uploadId + chunkSize + presign| C

  C -->|3) Upload parts (parallel)<br/>PUT pre-signed URL| S3
  C -->|4) Notify part complete| G --> U
  U --> D
  U --> R

  C -->|5) Status / Resume| G --> U
  U -->|6) Missing parts| C

  C -->|7) Complete| G --> U -->|CompleteMultipartUpload| S3
  U -->|8) Publish event| Q --> W --> S3

  J -->|AbortMultipartUpload + DB cleanup| S3
  J --> D
```

---

## Why these components (and why not the alternatives)

### API Gateway / Load Balancer

- **Why used**
  - central point for **TLS**, **routing**, **auth enforcement**, **rate limiting**, **WAF**, **request sizing**, and **observability**.
  - protects upload-control APIs from abuse.
- **Why not “client directly calls Upload Service”**
  - you lose standardized edge protections and operational simplicity.
- **Alternatives**
  - Managed API gateways (AWS API Gateway, GCP API Gateway, Azure APIM)
  - Ingress (NGINX/Envoy) + service mesh for internal auth

### Upload Service (stateless)

- **Why used**
  - handles **upload lifecycle**: initiate, status, complete, cancel.
  - generates **pre-signed URLs** and coordinates multipart sessions.
  - stateless design allows **horizontal scaling**.
- **Why not stateful uploads on backend**
  - backend becomes a **bandwidth bottleneck** and a scaling/cost pain.
- **Alternatives**
  - TUS protocol servers (good for some cases, but still often proxy bytes unless integrated with object storage)
  - Direct-to-storage with client-managed multipart (works, but you still need a control-plane for security/metadata)

### Auth Service (JWT validation)

- **Why used**
  - ensures only authorized users can initiate/complete uploads.
  - provides **userId/tenantId** for sharding + quota enforcement.
- **Why JWT**
  - stateless verification at gateway/service, low latency, scales well.
- **Why not “session stored in DB for every request”**
  - adds DB load on hot path.
- **Alternatives**
  - OAuth2 access tokens, opaque tokens + introspection (more control, more latency)

### Metadata DB (sharded)

- **Why used**
  - system needs a **source of truth** for:
    - upload status
    - chosen chunk size
    - total parts
    - received ETags/checksums per part
    - ownership and policy enforcement
- **Why sharding**
  - at scale, “uploads × parts” rows can be very large; sharding keeps write QPS manageable.
- **Why not only Redis**
  - Redis alone is volatile (unless using persistence) and not a good long-term system of record.
- **Alternatives**
  - Relational (Postgres/MySQL) with sharding
  - DynamoDB/Cassandra (natural horizontal scale; different query tradeoffs)

### Redis cache (uploaded part tracking)

- **Why used**
  - speeds up **status/resume** queries and **idempotency checks**.
  - reduces DB reads when clients poll frequently for progress.
- **Why not only DB**
  - status polling can create hot partitions and high read load.
- **Alternatives**
  - DB read replicas
  - Cache in CDN edge (not typical for per-user upload state)

### Object Storage (S3 / Blob)

- **Why used**
  - built for massive parallel writes, high durability, and cost-effective storage.
  - supports **multipart upload**, which is the core primitive for resumable large files.
- **Why S3 specifically**
  - mature multipart API, broad tooling, lifecycle policies, encryption, eventing, replication.
- **Why not block storage or your own file servers**
  - operational burden (scaling, durability, upgrades) and cost.
- **Alternatives**
  - GCS (Google Cloud Storage), Azure Blob Storage, MinIO (self-hosted S3-compatible)

### Pre-signed URL generator

- **Why used**
  - lets clients upload directly to storage **without** exposing storage credentials.
  - enforces **time-bound** and **scope-bound** permissions.
- **Why not store long-lived keys on clients**
  - credential leakage and account compromise risk.
- **Alternatives**
  - Temporary STS credentials (more flexible; more complexity on client)
  - Upload via edge function that re-signs (useful in some enterprise setups)

### Message queue + workers

- **Why used**
  - decouples upload completion from expensive processing.
  - prevents long synchronous APIs and improves resilience with retries/DLQ.
- **Why not process inline on “complete” request**
  - user-facing latency spikes, retries become painful, and failures cause bad UX.
- **Alternatives**
  - Serverless triggers (S3 event → Lambda), good for simpler pipelines
  - Workflow engines (Temporal/Cadence) for complex multi-step processing

---

## Core concepts explained (deep but practical)

### What is a UUID?

- **UUID** = Universally Unique Identifier: a 128-bit identifier usually rendered like:
  - `018fa3b2-6c7a-7c1f-bd31-9b2d4e9a52aa`
- **Why use it for `uploadId`**
  - extremely low collision probability without coordination
  - safe to generate in distributed systems
- **Why not auto-increment integers**
  - predictable (security/info leak), harder in sharded setups, can hotspot.

#### UUIDv4 vs UUIDv7 vs ULID (what we prefer and why)

- **UUIDv4**: random; great uniqueness; not time-sortable.
- **UUIDv7**: time-ordered (better DB locality and indexing), still unique.
- **ULID**: time-sortable, shorter, human-friendly; needs a library.
- **Recommendation**: **UUIDv7 or ULID** when you expect very high write volume and want better index locality.

### What is a pre-signed URL?

- A **pre-signed URL** is a URL that contains a **cryptographic signature** proving:
  - who signed it (your backend’s IAM principal)
  - what action is allowed (e.g., PUT part #17)
  - what object/key is allowed
  - what headers are required
  - when it expires

#### How is it generated (conceptually)?

1. Your backend has permission (IAM role) to upload to `bucket/uploads/...`.
2. Backend constructs a canonical request: method + path + query + headers.
3. Backend signs it with **HMAC-SHA256** (or equivalent mechanism in the cloud provider).
4. Storage validates the signature and expiry at request time.

#### Why it’s better than “upload via backend”

- **No backend bandwidth**.
- **No long-lived credentials** on client.
- **Hard limits**: expiry, path, part number, content-type, size constraints (depending on provider).

#### What exactly is bound in a multipart pre-signed URL (S3 example)?

When generating a pre-signed URL for `UploadPart`, you typically bind:

- **bucket + key**: the exact object location (prefix-scoped by policy)
- **multipart UploadId**: the storage session id
- **partNumber**: which part this URL is for
- **HTTP method**: usually `PUT`
- **expiry**: e.g., 10 minutes
- **(optional) signed headers**: e.g., `Content-MD5`, `x-amz-checksum-sha256`, `Content-Type`

This is why a pre-signed URL is safer than “give client bucket credentials”: the client can only do *exactly* what the URL allows.

#### How it’s generated in practice (AWS SDK pseudocode)

Your backend uses an IAM role with least privilege (only `s3:PutObject`, `s3:AbortMultipartUpload`, `s3:ListMultipartUploadParts`, etc. within the upload prefix).

Java-style pseudocode (AWS SDK v2):

```java
import java.net.URL;
import java.time.Duration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

// 1) Create multipart session (server-side)
CreateMultipartUploadResponse create = s3.createMultipartUpload(
  CreateMultipartUploadRequest.builder()
    .bucket(bucket)
    .key(objectKey)
    .contentType(mimeType)
    .build()
);
String storageUploadId = create.uploadId();

// 2) Pre-sign a URL for a specific part
UploadPartRequest uploadPart = UploadPartRequest.builder()
  .bucket(bucket)
  .key(objectKey)
  .uploadId(storageUploadId)
  .partNumber(partNumber)
  // Optional but good: bind expected part size if known
  // .contentLength(partSizeBytes)
  .build();

PresignedUploadPartRequest presigned = presigner.presignUploadPart(
  PresignUploadPartRequest.builder()
    .signatureDuration(Duration.ofMinutes(10))
    .uploadPartRequest(uploadPart)
    .build()
);

URL url = presigned.url(); // send this URL to the client
```

**Why not let the client create the multipart session?**

- you’d need to grant broader permissions to clients (riskier), and it becomes harder to enforce quotas/policies centrally.

### What is an ETag?

- **ETag** stands for **Entity Tag**.
- In S3 multipart uploads, each uploaded part returns an **ETag**.
- During completion, the backend supplies a list of `{PartNumber, ETag}` so S3 can assemble the final object.
- Note: ETag is often “MD5-like” for single-part uploads, but for multipart it is not a simple MD5 of the full object.

---

## Detailed upload lifecycle (end-to-end)

### Sequence diagram (arrow diagram)

```text
Client -> Gateway -> UploadSvc : Initiate (metadata, idempotencyKey)
UploadSvc -> DB                : create uploads row
UploadSvc -> S3                : CreateMultipartUpload
UploadSvc -> Client            : uploadId + chunkSize + presign template/range

loop for each part (parallel)
  Client -> S3                 : PUT UploadPart (pre-signed URL)
  S3 -> Client                 : 200 OK + ETag
  Client -> UploadSvc          : parts/{n}:complete (ETag)
  UploadSvc -> DB/Redis        : record part
end

Client -> UploadSvc            : complete
UploadSvc -> S3                : CompleteMultipartUpload (Parts list)
UploadSvc -> DB                : status=COMPLETED/PROCESSING
UploadSvc -> Queue             : UPLOAD_COMPLETED
Workers -> S3                  : scan/unzip/validate
```

### Step 1 — Initiate upload

#### Where does `fileSize` come from if the client can’t upload the file to the server?

The **client does not need to upload the bytes** to know file size. File pickers and OS file APIs expose metadata immediately.

- **Web**:
  - the browser provides a `File` object with `file.size` (bytes) and `file.name` after the user selects a file.
- **Mobile/Desktop**:
  - local filesystem APIs return file length/metadata.

So the flow is:

1. user selects a file
2. client reads local metadata: `fileName`, `fileSize`, (often `lastModified`)
3. client sends those values to `POST /v1/uploads/initiate`

The server later **verifies** the final stored object size using storage metadata (e.g., S3 `HeadObject.Content-Length`) after completion.

#### API

- `POST /v1/uploads/initiate`

#### Request (example)

```json
{
  "fileName": "data.zip",
  "fileSize": 1073741824,
  "mimeType": "application/zip",
  "fileHashSha256": "base64-or-hex (optional)",
  "idempotencyKey": "client-generated-uuid"
}
```

#### What the backend does (and why)

- **AuthN/AuthZ**: ensure user can upload (quota/plan).
- **Validate**: file size, mime type (don’t trust mime fully), policy.
- **Pick chunk size**:
  - must satisfy storage constraints (min part size, max parts).
  - must balance resume granularity vs overhead.
- **Create upload record** in DB:
  - `uploadId`, owner, status `IN_PROGRESS`, chunkSize, totalParts, requestedHash.
- **Create multipart session** in object storage:
  - S3 `CreateMultipartUpload` returns `storageUploadId` (multipart session id).
  - store it in DB (critical to resume/abort/complete).
- **Generate pre-signed URLs** for part uploads (either a batch of URLs or a template endpoint).

#### Chunk sizing rule (safe with S3 limits)

Let:

- `maxParts = 10000`
- `minPartSize = 5MB`
- `chunkSize = max(preferredSize, ceil(fileSize / maxParts))` rounded up to a “nice” size (e.g., 8MB, 16MB, 32MB, 64MB)
- `chunkSize >= 5MB` (except last)

Then compute:

- `totalParts = ceil(fileSize / chunkSize)`

**Why does the server compute `chunkSize` and `totalParts` if the client knows the file size?**

- The server owns platform constraints (S3 limits, quotas, tuning) and must prevent a buggy/malicious client from choosing:
  - too-small chunks → >10,000 parts (upload can’t complete)
  - too-large chunks → poor resumability and higher wasted retry cost

**Why not always 5MB?**

- too many requests for TB-scale files (overhead, throttling risk).

**Why not always 100MB?**

- poor resume granularity; one failed chunk wastes more bandwidth.

### Step 2 — Client uploads parts in parallel

- Client splits file into byte ranges and uploads:
  - concurrency: 3–8 (mobile lower, desktop higher)
  - retry: exponential backoff + jitter
  - checksum: per-part optional (cost vs integrity)

#### Where does `fileHashSha256` come from if we don’t upload the full file to the backend?

`fileHashSha256` means: **the SHA-256 digest of the entire file bytes** (from byte 0 to end), computed on the client.

- **Why it’s called `fileHashSha256`**:
  - it encodes both “what this value is” (file hash) and “which algorithm” (SHA-256), so you never confuse it with MD5/SHA-1/etc.
- **How it is represented**:
  - the raw SHA-256 output is 32 bytes; clients typically send it as:
    - hex (64 characters) or
    - base64 (44 characters with padding)
- **What it’s used for**:
  - confirm the client is resuming the same file
  - dedupe hints (“file already uploaded”)
  - end-to-end integrity check *if* you also verify server-side from the stored object
- **What it is NOT**:
  - it is not a storage ETag
  - it is not a per-chunk checksum (it covers the whole file)
  - it is not automatically trustworthy for security (a malicious client can lie)

The client can compute SHA-256 **locally** by streaming the file content in chunks and updating a hash incrementally.

- **Web**:
  - you can stream the file and compute SHA-256 using a streaming hashing library (WebCrypto’s `subtle.digest` is not always streaming-friendly by itself; many apps use a streaming SHA-256 implementation).
- **Mobile/Desktop**:
  - typical crypto libraries support incremental hashing (`update()` / `final()`).

The key point: **hashing is read-only**; it doesn’t require sending the file to your server.

#### Important: the server does not “trust” client hashes for security

A malicious client can claim any hash. You treat `fileHashSha256` as:

- useful for UX (detect “wrong file selected” on resume)
- helpful for dedupe hints
- but not an authoritative proof unless you also verify server-side from the stored object

#### Java pseudocode: compute SHA-256 of a local file (streaming)

```java
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

byte[] sha256(Path filePath) throws Exception {
  MessageDigest md = MessageDigest.getInstance("SHA-256");
  byte[] buffer = new byte[8 * 1024 * 1024]; // 8MB buffer (tune)

  try (InputStream in = Files.newInputStream(filePath)) {
    int read;
    while ((read = in.read(buffer)) != -1) {
      md.update(buffer, 0, read);
    }
  }
  return md.digest(); // 32 bytes
}

String sha256Base64(Path filePath) throws Exception {
  return Base64.getEncoder().encodeToString(sha256(filePath));
}
```

**Practical note**: computing a full-file hash before uploading can delay “time to first byte” on very large files. Many systems either:

- compute it in parallel while uploading and submit it later, or
- compute it after upload (server-side) and treat client hash as optional.

#### Client algorithm (practical pseudo-flow)

```text
Given uploadId, chunkSize, totalParts:
  load local state (uploaded parts) from IndexedDB/SQLite
  missingParts = server.missingParts (or local ∩ server)

  start N parallel workers:
    take next missing partNumber
    get presigned URL (from initiate response or presign-range endpoint)
    PUT bytes (range) to URL
    on success: capture ETag
    call parts/{partNumber}:complete with ETag
    persist local state (partNumber -> done) for crash resilience

  when all parts done:
    call complete
```

#### Data-plane request

- `PUT {preSignedUrlForPartN}`
- body: bytes for part N
- on success, storage returns `ETag`.

### Step 3 — Notify part completion (control-plane)

Even though storage has the bytes, **your system still needs metadata** so it can:

- show progress
- support resume across devices
- reliably complete (server-side) using the correct ETags

#### API

- `POST /v1/uploads/{uploadId}/parts/{partNumber}:complete`

#### Request (example)

```json
{
  "etag": "\"9b2cf535f27731c974343645a3985328\"",
  "partChecksum": "optional-sha256-of-part",
  "partSizeBytes": 10485760
}
```

#### Backend behavior

- Validate ownership, upload status, and `partNumber` bounds.
- **Idempotency**:
  - if `(uploadId, partNumber)` already recorded with same ETag → return 200 OK.
  - if recorded with different ETag → mark suspicious; require reconciliation (client may have uploaded multiple times).
- Persist part metadata:
  - DB row in `upload_parts`
  - cache membership in Redis (e.g., bitmap/set)

### Step 4 — Resume

#### API

- `GET /v1/uploads/{uploadId}`

#### Response (example)

```json
{
  "uploadId": "018fa3b2-6c7a-7c1f-bd31-9b2d4e9a52aa",
  "status": "IN_PROGRESS",
  "fileName": "data.zip",
  "fileSize": 1073741824,
  "chunkSize": 10485760,
  "totalParts": 103,
  "uploadedParts": [1,2,3,7,8],
  "missingParts": [4,5,6,9,10],
  "expiresAt": "2026-04-20T18:25:43Z"
}
```

#### Why we need both Redis and DB

- Redis answers quickly for hot polling.
- DB is the authoritative fallback for correctness.

#### Important resume edge case: “client uploaded part but never notified backend”

This happens if the client crashed after upload success.
Options:

- **Option A (recommended)**: backend calls S3 `ListParts` using server credentials to reconcile truth with storage.
- **Option B**: rely on notify-only (simpler, but can strand uploaded bytes until cleanup).

### Step 5 — Complete upload (server-side completion)

#### API

- `POST /v1/uploads/{uploadId}:complete`

#### Backend behavior

- Verify upload is `IN_PROGRESS` and caller owns it.
- Validate that all parts are accounted for:
  - use DB/Redis list, optionally reconcile with S3 `ListParts`.
- Call object storage completion:
  - S3 `CompleteMultipartUpload` with ordered list of `{PartNumber, ETag}`.
- Set DB status → `COMPLETED` (or `PROCESSING` if async hash verification / post-processing).
- Publish event to queue: `UPLOAD_COMPLETED`.

#### Why complete is server-side (not client-side)

- prevents a malicious client from completing an upload they don’t own.
- allows policy enforcement (size, part count, checksums).
- simplifies auditing and consistent state transitions.

---

## File integrity + consistency verification (step-by-step, including “file changed during upload”)

This section answers two production questions:

- “How do we know the uploaded bytes match the intended file?”
- “What if the user modifies the file while uploading?”

### What can go wrong

- **Accidental change**: user edits the file during upload; later chunks read different bytes.
- **Client crash**: some parts reached storage, but client never notified backend.
- **Network ambiguity**: client times out and retries, uploading the same part twice.
- **Malicious client**: tries to upload unexpected bytes but claim a “good” hash.

### How the system detects and handles it (layered approach)

#### 1) Initiate: freeze the expected contract

On `POST /v1/uploads/initiate`, the backend stores:

- `expectedSizeBytes` (from client metadata)
- `chunkSizeBytes`, `totalParts` (server-chosen)
- `requested_sha256` (client-provided, optional but recommended)
- `objectKey` + `storage_multipart_upload_id` (storage session)

This establishes “what we believe we’re uploading” for later checks.

#### 2) Part upload: protect each part (optional but recommended)

During each `PUT` part upload to storage, you can require a checksum header.

- If storage validates checksums, corrupted transit is rejected at the edge.
- This is strictly stronger than “trust the client to upload correctly”.

#### 3) Notify part completion: record what storage accepted

After S3 accepts a part, it returns an **ETag**.
Client calls `parts/{n}:complete` with that ETag.
Backend stores `(uploadId, partNumber) -> ETag` (idempotent).

If the same part is notified twice:

- if ETag matches: treat as safe retry
- if ETag differs: reconcile via `ListParts` and pick the authoritative part that exists in storage

#### 4) Resume correctness: don’t rely only on notifications

If the client uploaded a part but crashed before notifying:

- backend can call `ListParts` on the multipart session to learn which parts exist in storage
- then return accurate missing parts to the client

#### 5) Complete: server-side assembly with the final part list

On `POST /v1/uploads/{uploadId}:complete`:

- verify parts coverage (DB/Redis and optionally `ListParts`)
- call `CompleteMultipartUpload` with ordered `{PartNumber, ETag}`

At this point, storage assembles the final object.

#### 6) Post-complete verification: detect “file modified mid-upload” (and malicious mismatch)

After completion, run these checks:

1. **Size check** (fast, should always be done):
  - fetch storage metadata (e.g., `HeadObject`)
  - assert `Content-Length == expectedSizeBytes`
2. **Content hash check** (strongest end-to-end consistency):
  - compute SHA-256 by streaming the final stored object (often async via worker for huge files)
  - compare to `requested_sha256` (if provided)

If the file changed mid-upload (mixed bytes across parts), the final SHA-256 almost certainly **won’t match** the original.

**If mismatch:**

- mark upload `FAILED` (or `QUARANTINED`)
- optionally delete the object (or keep for forensics depending on policy)
- require client to restart upload from scratch (or re-initiate)

### Practical UX safeguard (optional but effective)

Have the client capture `lastModified` (where available) at selection time and refuse to continue an upload if:

- the user re-selects a file with different `size` or `lastModified`
- or the computed hash differs from the stored `requested_sha256`

This prevents many “I edited the file while uploading” cases before they waste bandwidth.

---

## S3 multipart APIs used (what, why, and where)

### APIs (AWS terminology; similar primitives exist elsewhere)

- **CreateMultipartUpload**
  - creates a multipart session; returns `UploadId`
  - called by backend during initiate
- **UploadPart**
  - uploads bytes for a specific `PartNumber`
  - performed by client using a pre-signed URL for that part
- **CompleteMultipartUpload**
  - asks S3 to assemble parts into final object (atomic from client perspective)
  - called by backend on complete
- **AbortMultipartUpload**
  - releases storage resources for an unfinished upload
  - called by cleanup job or cancel endpoint
- **ListParts**
  - lists which parts exist in S3 for a multipart session
  - used for reconciliation/resume correctness

### Why not “single PUT” for big files

- single PUT is fragile for multi-GB uploads (retries restart from zero).
- multipart enables **resume** at part granularity.

---

## Data model (DB) — source of truth

Below is one practical relational model. (No markdown tables; everything is explicit.)

### `uploads` (one row per upload)

Fields:

- `upload_id` (UUID/ULID, PK)
- `user_id` (UUID, indexed)
- `file_name` (varchar)
- `mime_type` (varchar)
- `file_size_bytes` (bigint)
- `chunk_size_bytes` (int)
- `total_parts` (int)
- `status` (enum: `IN_PROGRESS`, `COMPLETING`, `COMPLETED`, `CANCELLED`, `FAILED`, `EXPIRED`)
- `requested_sha256` (varchar) — hash provided by client (optional but recommended)
- `storage_provider` (varchar) — e.g., `s3`
- `bucket` (varchar)
- `object_key` (varchar) — deterministic path e.g. `uploads/{tenant}/{uploadId}`
- `storage_multipart_upload_id` (varchar) — S3 `UploadId`
- `created_at`, `updated_at`
- `expires_at` (timestamp) — when we consider it abandoned
- `idempotency_key` (varchar, unique per user) — optional (helps with retries on initiate)

Indexes (examples):

- `(user_id, status, updated_at)`
- `(user_id, idempotency_key)` unique
- `(expires_at)` for cleanup scans (or partitioned by time)

### `upload_parts` (one row per part)

Fields:

- `upload_id` (FK)
- `part_number` (int)
- `etag` (varchar)
- `part_size_bytes` (int)
- `part_checksum` (varchar, nullable)
- `status` (enum: `UPLOADED`, `VERIFIED`) (keep simple)
- `created_at`, `updated_at`

Primary key:

- `(upload_id, part_number)`

Indexes:

- `(upload_id)` (supports listing)

### Sharding strategy

Common and simple:

- shard by `**user_id`** (or tenant id)
Why:
- aligns with quotas, per-user listing, and keeps hot users isolated.

Alternative sharding:

- shard by `upload_id` hash (more even, but less locality for per-user queries).

### Example SQL DDL (Postgres-flavored, for concreteness)

```sql
CREATE TYPE upload_status AS ENUM (
  'IN_PROGRESS','COMPLETING','PROCESSING','COMPLETED','CANCELLED','FAILED','EXPIRED'
);

CREATE TABLE uploads (
  upload_id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  file_name TEXT NOT NULL,
  mime_type TEXT,
  file_size_bytes BIGINT NOT NULL,
  chunk_size_bytes INT NOT NULL,
  total_parts INT NOT NULL,
  status upload_status NOT NULL,
  requested_sha256 TEXT,
  bucket TEXT NOT NULL,
  object_key TEXT NOT NULL,
  storage_multipart_upload_id TEXT NOT NULL,
  idempotency_key TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uploads_user_idempotency_uq ON uploads(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX uploads_user_status_updated_idx ON uploads(user_id, status, updated_at DESC);
CREATE INDEX uploads_expires_at_idx ON uploads(expires_at);

CREATE TABLE upload_parts (
  upload_id UUID NOT NULL REFERENCES uploads(upload_id) ON DELETE CASCADE,
  part_number INT NOT NULL,
  etag TEXT NOT NULL,
  part_size_bytes INT NOT NULL,
  part_checksum TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (upload_id, part_number)
);
```

---

## Redis usage (fast-path state)

### What to cache

- uploaded parts set/bitmap for a given `uploadId`
- current upload progress counters (optional)

### Suggested structures

- **Bitmap** keyed by `uploadId` for part presence (memory-efficient):
  - bit i = part i uploaded
- Or a **Set** for smaller files (simpler, more overhead):
  - members = part numbers

### Cache consistency

- DB remains source of truth.
- On read:
  - use Redis if present
  - fallback to DB if missing
- On write:
  - write DB first (or transactional outbox)
  - then update Redis

---

## API design (control-plane) — recommended endpoints

### 1) Initiate

- `POST /v1/uploads/initiate`
Returns:
- `uploadId`
- `chunkSizeBytes`
- `totalParts`
- `presignedParts` (either full list or “fetch-on-demand” endpoint)

Why “fetch-on-demand” can be better:

- for a 10,000-part file you don’t want to return 10,000 URLs at once.

Recommended pattern:

- Initiate returns a **URL template** approach:
  - `GET /v1/uploads/{uploadId}/parts:presign?from=1&to=50`

### 2) Presign a range (optional, scalable)

- `GET /v1/uploads/{uploadId}/parts:presign?from=1&to=50`
Returns URLs for those parts only.

### 3) Notify part complete

- `POST /v1/uploads/{uploadId}/parts/{partNumber}:complete`

### 4) Status

- `GET /v1/uploads/{uploadId}`

### 5) Complete

- `POST /v1/uploads/{uploadId}:complete`

### 6) Cancel

- `POST /v1/uploads/{uploadId}:cancel`
Backend aborts multipart upload + marks upload cancelled.

### 7) List user uploads (optional)

- `GET /v1/uploads?status=IN_PROGRESS&limit=50`

---

## Idempotency & correctness (critical at scale)

### Where idempotency is needed

- **Initiate**: client may retry on timeout.
  - use `idempotencyKey` unique per `(userId, idempotencyKey)`.
- **Part completion**: client may retry after receiving no response.
  - enforce uniqueness via `(uploadId, partNumber)` PK.

### Handling “same part uploaded twice”

Causes:

- client retry after ambiguous timeout
- user resume from another device

Safe handling:

- accept idempotent replay if ETag matches.
- if ETag differs:
  - treat latest as “candidate”
  - reconcile by checking S3 `ListParts` for the authoritative ETag
  - store the final ETag you will use for completion

### Upload state machine (what transitions are allowed)

Why this matters: it prevents “impossible states” when retries and concurrent devices occur.

```text
IN_PROGRESS
  | (client cancels) ------------------> CANCELLED
  | (expires) -------------------------> EXPIRED
  | (client requests complete) -------> COMPLETING

COMPLETING
  | (CompleteMultipartUpload ok) -----> PROCESSING or COMPLETED
  | (complete fails; retryable) ------> COMPLETING
  | (complete fails; fatal) ----------> FAILED

PROCESSING
  | (workers succeed) ----------------> COMPLETED
  | (workers fail) -------------------> FAILED
```

### Failure modes and how the system recovers

- **Client PUT succeeded, notify failed**:
  - client retries notify (idempotent), or backend reconciles using `ListParts`.
- **Client uploaded wrong bytes for a part**:
  - ETag mismatch shows up; choose authoritative ETag from `ListParts` and re-upload missing/incorrect parts.
- **Backend crashed mid-initiate**:
  - idempotency key prevents duplicate uploads; DB row is the anchor.
- **DB write succeeded but Redis write failed**:
  - status reads fall back to DB; Redis heals lazily.
- **CompleteMultipartUpload timed out**:
  - safe to retry complete (server checks state, uses stored parts list).
- **Abandoned uploads**:
  - scheduled job marks expired and aborts multipart session to prevent leaked storage.

---

## Security design (what you must do in production)

### Bucket/object authorization

- Use **bucket policies** that only allow uploads under a controlled prefix:
  - `uploads/{tenant}/{uploadId}/...`
- Pre-signed URLs should be:
  - short-lived (e.g., 10–20 minutes)
  - scoped to exactly one part and one object key

### What not to trust from clients

- `mimeType` (can be spoofed)
- `fileHash` (can be wrong; verify independently if needed)
- `fileName` (sanitize; don’t allow path traversal patterns)

### Abuse prevention

- rate limiting:
  - initiate requests per user/IP
  - presign requests per user/IP
- quotas per plan:
  - max concurrent uploads
  - max daily bytes
- WAF rules:
  - block suspicious patterns, bot traffic

### Transport & encryption

- HTTPS everywhere (TLS).
- encryption at rest:
  - S3 SSE-S3 or SSE-KMS
- client-side encryption (optional for end-to-end privacy; adds complexity).

### Multi-tenant isolation

- include `tenantId` in:
  - DB partition keys/shards
  - object key prefix
  - IAM policy conditions (where possible)

---

## Post-upload processing pipeline (event-driven)

### Why event-driven

- Upload completion is a quick state transition.
- Processing is slow/variable and can fail/retry safely.

### Event

- `UPLOAD_COMPLETED` with payload:
  - `uploadId`, `userId`, `bucket`, `objectKey`, `size`, `mimeType`, `sha256`

### Workers

Typical steps:

- virus scan (streaming if possible)
- file type detection (magic bytes)
- zip extraction (safe):
  - cap total extracted bytes
  - cap nesting depth
  - cap file count
- validation/transcode/indexing as needed

### DLQ & retries

- Use exponential backoff retries for transient errors.
- After N failures, move to **dead-letter queue** and mark upload as `FAILED_PROCESSING`.

---

## Cleanup & lifecycle management

### Why cleanup matters

Multipart uploads can leak storage and cost if never completed/aborted.

### Scheduled jobs

- **Abandoned upload cleanup**
  - if `IN_PROGRESS` and `updated_at < now - 24h` (tune per product)
  - mark as `EXPIRED`
  - call `AbortMultipartUpload`
  - delete metadata rows
- **Orphan reconciliation** (optional)
  - ensure DB and storage agree (especially if notifications were missed)

### Storage lifecycle policies

- auto-expire temporary objects/prefixes
- move completed objects to cheaper storage tiers if applicable

---

## Observability (what to measure)

### Metrics

- initiate QPS / p95 latency
- presign QPS / p95 latency
- part-complete QPS / error rate
- complete QPS / error rate
- average parts per upload, average retries per part
- abandoned upload rate
- processing pipeline success/failure rates

### Tracing

- include `uploadId` as a trace attribute in every request/log line.

### Logs (minimum)

- state transitions: `IN_PROGRESS → COMPLETING → COMPLETED/FAILED`
- security events: suspicious ETag mismatch, quota violations, blocked MIME types

---

## Capacity planning (back-of-envelope)

Assumptions (example):

- 1M concurrent in-progress uploads
- average file size = 1 GB
- chunk size = 16 MB → ~64 parts per upload
- average concurrency per client = 4 parallel parts

Implications:

- data-plane throughput mostly hits object storage.
- control-plane load comes from:
  - initiate (once)
  - presign (per batch)
  - part-complete (per part, can be batched if you design it so)
  - status polling (optimize with Redis; encourage client backoff)

---

## Alternatives and when you’d choose them

### Use STS temporary credentials instead of pre-signed URLs

- **Pros**
  - client can call storage APIs directly for list parts/resume, fewer backend calls
- **Cons**
  - more complex client, more IAM surface area, harder to lock down perfectly

### Upload through CDN/edge compute

- **Pros**
  - improved latency, can enforce policies near user, can re-sign
- **Cons**
  - operational complexity, sometimes cost

### TUS protocol

- **Pros**
  - standardized resumable uploads for some ecosystems
- **Cons**
  - still need storage integration; large-scale direct-to-object-storage pattern remains best

### If you’re not on AWS: equivalent primitives

- **GCS**: Signed URLs + resumable upload sessions + object composition (or provider-specific multipart).
- **Azure Blob**: SAS tokens + block blobs (stage blocks, commit block list).
- **MinIO**: S3-compatible multipart + pre-signed URLs (self-hosted).

---

## Common interview “why” answers (quick)

### Why not upload through backend?

- It multiplies your infra cost by your customers’ upload bandwidth and makes scaling harder.

### Why object storage (S3) for this?

- Built-in durability, concurrency, multipart primitives, lifecycle policies, replication, and mature IAM.

### How do you guarantee correctness?

- Use multipart ETags + DB constraints for idempotency; optionally reconcile via `ListParts`; complete server-side; verify hashes.

### How do you support resume across devices?

- Persist upload state in DB keyed by `uploadId` and owned by `userId`; client can re-fetch status and continue missing parts.

---

## Glossary (short)

- **Upload session**: one logical file upload tracked by `uploadId`.
- **Multipart upload**: storage-side mechanism where a file is assembled from parts.
- **Part / chunk**: a byte range uploaded independently.
- **Pre-signed URL**: time-limited signed URL granting permission for a specific upload action.
- **ETag**: identifier returned by storage for an uploaded part; used during completion.
- **Idempotency key**: client-provided unique key to make retries safe.

---

## Final summary

This design is “production-grade” because:

- it keeps the backend stateless and off the data-plane
- uses durable object storage multipart primitives for large files
- stores authoritative metadata for correctness and multi-device resume
- uses caching to handle polling and reduce DB load
- completes uploads server-side for security and policy control
- decouples heavy processing via an event-driven pipeline
- includes cleanup jobs and observability from day 1

