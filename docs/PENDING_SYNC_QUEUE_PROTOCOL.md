# Durable property sync queue

The app saves property changes and pending immutable snapshots together in one AtomicFile envelope (storageFormat 3). Legacy array files remain readable. A failed save publishes neither a new property nor a queue entry. Unknown legacy acknowledgement state is review-only; explicitly pending legacy records can be recovered into the queue.

Each write has a stable operationId, snapshot, attempts, nextAttemptAt, state and lastError. Unattempted edits may be coalesced under a new operationId. Once attempted, an operationId always refers to the same snapshot. A newer local edit waits behind any earlier unresolved operation for the same property. Retryable network/HTTP 429/5xx failures use exponential backoff (30 seconds to 6 hours). In-flight leases survive process death. Other failures require review.

An authenticated endpoint must explicitly advertise all of these capabilities in its successful GET response before receiving queued writes:

~~~json
{
  "status": "success",
  "capabilities": {
    "queueProtocolVersion": 1,
    "idempotentWrites": true,
    "operationAcknowledgements": true
  }
}
~~~

This capability response does not implement authentication. Do not advertise it on a legacy or anonymous script. The existing deployment placeholder stays disabled.

The queued POST includes action=UPSERT, operationId, propertyCode, recordId and the immutable property snapshot. The server must authenticate/authorize the employee, validate schema and approval rules, enforce unique property identity, atomically record operationId plus a payload hash and its committed result, and return the same result for identical replays. Reusing an operationId with different content must fail. Perform revision/conflict checks before committing; never silently overwrite a newer upstream record.

Only the following matching application acknowledgement clears the operation:

~~~json
{
  "status": "success",
  "action": "UPSERTED",
  "operationId": "<exact operation ID>",
  "propertyCode": "<matching property code>",
  "recordId": "<matching permanent ID when supplied>"
}
~~~

Acknowledging an older snapshot never marks a newer local edit synced. Local acknowledgement-save failures retain the operation for safe idempotent replay. Deletes are not sent automatically: an attempted write for a locally deleted record requires review.

Runner: startup/manual actions, 60-second checks while the app is open, and Android WorkManager when background work is allowed. Successful local saves and configured endpoint changes schedule unique connected-network work, with a 15-minute periodic safety check and exponential retry backoff. WorkManager requests contain no property/contact payloads. The worker uses the same capability and acknowledgement gates without starting repository polling loops. Review-blocked records do not cause automatic retry loops.

Android controls actual execution timing; Doze, battery/OEM policies and force-stop can defer work. This is not an exact 15-minute delivery guarantee. Queue state survives interruption/reboot, and immutable operation IDs protect retries with a compatible idempotent server. Authenticated backend deployment, review-resolution UI and Samsung/device end-to-end testing remain separate work. Pending counts and reasons appear in Master Sheet Sync.

WorkManager dependency: androidx.work:work-runtime 2.11.2; tests use matching work-testing. Reference: https://developer.android.com/jetpack/androidx/releases/work and https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work .


Background drain hardening: foreground and worker repositories share one process mutex. Each worker handles at most three operations within a two-minute cooperative time budget, checks cancellation before each new operation, and closes its repository scope. Blocking HTTP requests still finish or time out according to the transport limits; durable operation leases and idempotency keys protect uncertain outcomes. This does not promise exact execution timing or verified live backend delivery.
