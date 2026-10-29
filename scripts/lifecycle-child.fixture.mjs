import Fastify from 'fastify';
import { install, attach, closeStartup, stopping } from '../target/lifecycle/lifecycle.js';

const pool = {
  end() {
    console.log('POOL_END');
    return process.env.FIXTURE_POOL_FAILURE === '1'
      ? Promise.reject(new Error('fixture pool failure')) : Promise.resolve();
  },
};
const schemaPool = {
  end() {
    console.log('SCHEMA_END');
    return process.env.FIXTURE_POOL_BLOCKED === '1' ? new Promise(() => {}) : Promise.resolve();
  },
};
const controller = install(pool, schemaPool, 20000);
if (process.env.FIXTURE_INIT === 'blocked') {
  setInterval(() => {}, 1000);
  console.log('INIT_STARTED');
  await new Promise(() => {});
}
if (process.env.FIXTURE_INIT === 'delayed') {
  console.log('INIT_STARTED');
  await new Promise(resolve => setTimeout(resolve, 400));
}
await closeStartup(controller);
if (stopping(controller)) {
  console.log('INIT_CANCELED');
} else {
  const app = Fastify();
  attach(controller, app);
  app.get('/hold', async () => {
    console.log('REQUEST_STARTED');
    await new Promise(resolve => setTimeout(resolve, 400));
    console.log('REQUEST_RELEASED');
    return { result: 'drained' };
  });
  if (process.env.FIXTURE_SETUP_DELAY === '1') {
    console.log('SETUP_STARTED');
    await new Promise(resolve => setTimeout(resolve, 400));
  }
  if (stopping(controller)) {
    console.log('SETUP_CANCELED');
  } else {
    await app.listen({ host: '127.0.0.1', port: 0 });
    if (process.env.FIXTURE_LINGER === '1') setInterval(() => {}, 1000);
    console.log('LISTEN ' + app.server.address().port);
  }
}
