# Bounded cancellation and resource audit

Frozen [plan](cloud-ocr-iteration16-plan.json), source and JAR revision
`2fe947d156b34339735de3078dd047ec665e8267`; JAR SHA-256
`718eeb8181bcd31d4a86dc41954736e4ee2c4ade73b328724687e502cbdc62ed`.
Eight disruptive HTTP→independent Worker scenarios completed: enhanced-input
cancellation three times, original-page timeout twice, whole-task timeout once,
queued cancellation once and OCR-permit-wait cancellation once. Each was followed
by successful numeric OCR recovery on the same server/slot. No product cleanup
fault reproduced; no production cleanup change made.

Cancellation retained CANCELLED/TASK_CANCELLED, page timeout FAILED/OCR_TIMEOUT,
task timeout FAILED/CONVERSION_TIMEOUT; no download. Within ten seconds there
were no **live** registered Worker/engine/child processes, work/output files or
enhanced temporary PNGs; the cross-process POSIX record lock was obtainable.
DELETE removed the task directory. Input retention before DELETE is intentional.
An owned sidecar outside the server tree remained alive.

## Harness corrections and limits

The first run completed six cases, then stopped because the script expected
status QUEUED; the actual API correctly uses **WAITING with stage QUEUED**.
Only the two outstanding queued/permit cases were run after that assertion fix.
Both passed, including a waiting worker observed at PARSING with the first engine
still live. Raw initial and remaining reports are retained separately.

The harness also terminated its subreaper before its Java backend, leaving six
owned backend processes. This was a harness fault, not a successful teardown.
Only the six registered matching PID/start-time identities and matching command
lines were signalled. They became zombies parented to PID 1; this process cannot
reap them. A later observer/engine-marker check also identifies four controlled
engine/child zombie records from the stopped initial queued case: ten matching
registered PID records remain (six Java, four Python), all Z under PID 1. The six-Java
cleanup receipt was not a complete count of residual descendants.
Future runner teardown terminates the backend first and keeps the subreaper alive.
The corrected shutdown path was not used in the eight completed scenarios;
subsequent iteration17 HTTP runs independently exercise that order.

The `processesGone` receipt actually means no matching **live** process; the
observer treats Z as inactive. It does not prove every PID record disappeared.
Injection used a foreground controlled Python engine waiting on one registered
child with a 60-second bound, not a detached daemon. Discovery launches no child.
Concurrency/permit cap one; normal OCR/task limits 30/20 seconds, page-timeout
probe two seconds, task-timeout probe five seconds, whole audit 240 seconds.
Injected TSV/confidence is a process contract, not native OCR accuracy.
Detached-child races, all scheduler interleavings and native desktop behavior
remain unrun. This finite negative cannot exclude every cleanup race.

Raw evidence: ignored `qa-samples/work/iteration16-cleanup/results.json`,
`iteration16-remaining/results.json`, separate stdout/stderr and
`iteration16-harness-cleanup.json`. Public
[runner](../qa-samples/run_ocr_iteration16_cleanup.py) depends on the frozen
iteration15 PNG/TSVs, accepted JAR and Linux `/proc`/POSIX locks. It refuses to
overwrite output. No completed cleanup scenario was repeated.
