package com.systemdesign.backoff;

import java.time.Duration;

public sealed interface BackoffStrategy permits ExponentialBackoff, DecorrelatedJitter {

    Duration nextDelay(int attempt);
}
