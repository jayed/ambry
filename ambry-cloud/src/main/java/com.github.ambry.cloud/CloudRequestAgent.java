/**
 * Copyright 2020 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.cloud;

import com.github.ambry.config.CloudConfig;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Utility class to issue cloud requests with retries and throttling.
 */
class CloudRequestAgent {

  private static final Logger logger = LoggerFactory.getLogger(CloudRequestAgent.class);
  private final long defaultRetryDelay;
  private final int maxAttempts;
  private final VcrMetrics vcrMetrics;

  CloudRequestAgent(CloudConfig cloudConfig, VcrMetrics vcrMetrics) {
    defaultRetryDelay = cloudConfig.cloudDefaultRetryDelay;
    maxAttempts = cloudConfig.cloudMaxAttempts;
    this.vcrMetrics = vcrMetrics;
  }

  /**
   * Execute an action up to the configured number of attempts.
   * @param action the {@link Callable} to call.
   * @param actionName the name of the action.
   * @return the return value of the action.
   * @throws CloudStorageException
   */
  <T> T doWithRetries(Callable<T> action, String actionName) throws CloudStorageException {
    int attempts = 0;
    while (attempts < maxAttempts) {
      try {
        return action.call();
      } catch (Exception e) {
        attempts++;
        throwOrDelay(e, actionName, attempts);
      }
    }
    // Line should never be reached
    throw new CloudStorageException(actionName + " failed " + attempts + " attempts");
  }

  /**
   * Utility to either throw the input exception or sleep for a specified retry delay.
   * @param e the input exception to check.
   * @param actionName the name of the action that threw the exception.
   * @param attempts the number of attempts made so far.
   * @throws CloudStorageException
   */
  private void throwOrDelay(Throwable e, String actionName, int attempts) throws CloudStorageException {
    if (e instanceof CloudStorageException) {
      CloudStorageException cse = (CloudStorageException) e;
      if (cse.isRetryable() && attempts < maxAttempts) {
        long delay = (cse.getRetryDelayMs() > 0) ? cse.getRetryDelayMs() : defaultRetryDelay;
        logger.error("{} failed attempt {}, retrying after {} ms.", actionName, attempts, delay);
        try {
          Thread.sleep(delay);
        } catch (InterruptedException iex) {
        }
        vcrMetrics.retryCount.inc();
        vcrMetrics.retryWaitTimeMsec.inc(delay);
      } else {
        // Either not retryable or exhausted attempts.
        throw cse;
      }
    } else {
      // Unexpected exception
      throw new CloudStorageException(actionName + " failed", e);
    }
  }
}