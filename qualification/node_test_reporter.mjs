// Native node:test events preserve assertion failures vs errors/timeouts.
export default async function* reporter(events) {
  const summary = {tests: 0, failures: 0, errors: 0, timed_out: false};
  for await (const event of events) {
    if (!['test:pass', 'test:fail'].includes(event.type) || event.data.details?.type === 'suite') continue;
    summary.tests += 1;
    if (event.type === 'test:fail') {
      let error = event.data.details?.error;
      let assertion = false;
      for (let depth = 0; error && depth < 8; depth += 1, error = error.cause) {
        if (error.code === 'ERR_ASSERTION') assertion = true;
        if (['testTimeoutFailure', 'cancelledByParent'].includes(error.failureType)) summary.timed_out = true;
      }
      if (assertion) summary.failures += 1;
      else summary.errors += 1;
    }
  }
  yield 'AXXIUM_NODE_TEST_SUMMARY=' + JSON.stringify(summary) + '\n';
}
