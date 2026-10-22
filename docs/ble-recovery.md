# Bluetooth recovery and action safeguards

The service owns one Bluetooth client while a companion camera is present. A temporary
disconnect retains that auto-connect client. A disappearance of BLE advertisements does
not close an established GATT link or cancel its timers. The client is disposed once the
camera is both absent and disconnected, or on unpairing or service destruction. Disposal
unregisters its bond receiver and cancels its collectors and timers.
Callbacks from replaced or disposed clients are ignored.

Every GATT request must be accepted by Android and complete within 30 seconds. This deadline
applies only to an actual request awaiting its Bluetooth callback; custom self-timer and
hold countdowns run independently. A rejected or failed button command cancels the current
action and attempts releases while keeping the connection. A missing callback, failed
setup or lost permission can invalidate the connection and trigger bounded recovery.
Setup is retried at most twice after a failure; uncertain shutter or recording writes are
never replayed. Successful setup and release of controls reset the retry budget. If recovery
is exhausted, turn the camera off and on to start a new presence session.

The camera becomes ready only after status notification registration and its descriptor
write succeed. When camera services change, unsent operations are dropped and the accepted
request is drained before rediscovery. A missing callback follows the normal timeout path.

Action safeguards are defined in `SequenceSafety.kt`:

Self-timers can beep once per second, then twice per second in their final three seconds.
This is enabled by default and can be disabled under Settings → Self-timer sound → Countdown
beeps. Sound uses the phone's notification volume and is muted in silent/vibrate mode or
Do Not Disturb. Shutter-hold, bulb, focus and interval countdowns stay silent. Beeps follow
the existing timer deadline and stop on completion, cancellation or disconnection. Audio
failure does not cancel a camera action or affect its Bluetooth connection.

Intervalometer → Refocus after every Shot is saved separately and defaults to unchecked.
Unchecked holds shutter half-press across the entire sequence; each interval only pulses
full shutter. Use AF-S when a fixed focus lock is wanted, since camera AF-C can continue
focusing while half-press is held. Checked leaves half-press released during the interval,
then half-presses and waits for a fresh successful focus report before each shot, including
the first. The previous shot's focus report cannot satisfy that wait, and accepted button
writes must finish first. Failure to focus cancels and releases controls after 15 seconds,
without disconnecting Bluetooth. Actual time between shots includes autofocus time.
Bulb closing pulses retain half-press so they do not refocus an open exposure. Completion,
Abort and connection cleanup release half-press in both modes. Focus bracketing requires
manual focus, disables the checkbox and retains its existing manual lens-drive sequence.

| Safeguard | Limit |
| --- | --- |
| Wake-lock lease | 2 minutes, renewed every minute while work remains |
| Focus feedback wait | 15 seconds |
| Shutter-opening or recording feedback wait | 30 seconds |
| Shutter-closing feedback wait | 30 minutes, to accommodate long exposures |
| Manual full-shutter, focus-jog or zoom hold without a release | 60 seconds |
| Explicit timer or hold duration | Up to 24 hours; must be finite and nonnegative |

Cancellation drops unsent presses before queuing releases for controls whose presses were
actually accepted by Android. Unrelated AF, zoom or other controls are not sent. Failed
cleanup writes are logged without recursively issuing more releases. An accepted
write remains serialized ahead of those releases. Wake locks remain held until the release
queue completes or the connection fails. If Bluetooth communication is lost, delivery of a
release command cannot be guaranteed; recovery sends releases again after setup succeeds.

Run `gradlew.bat testDebugUnitTest lintDebug` with a JDK and the configured Android SDK.
The regression tests cover client ownership, rejected/failed/timed-out operations, stale
deadlines, cancellation ordering, rediscovery ordering, advertising loss during a 6-second
timer with a 0.5-second hold, rejected commands and duration bounds. Real-camera
validation should also exercise power cycling, out-of-range reconnection, cancellation
during a burst, manual button holds and a bulb exposure longer than the wake-lock lease.
