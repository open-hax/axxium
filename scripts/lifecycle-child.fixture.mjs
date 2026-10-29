import Fastify from 'fastify';
import { install } from '../target/lifecycle/lifecycle.js';

const app = Fastify();
const pool = {
  end() {
    console.log('POOL_END');
    return process.env.FIXTURE_POOL_FAILURE === '1'
      ? Promise.reject(new Error('fixture pool failure')) : Promise.resolve();
  },
};

app.get('/hold', async () => {
  console.log('REQUEST_STARTED');
  await new Promise(resolve => setTimeout(resolve, 400));
  console.log('REQUEST_RELEASED');
  return { result: 'drained' };
});

await app.listen({ host: '127.0.0.1', port: 0 });
install(app, pool);
console.log('LISTEN ' + app.server.address().port);
