import { drumClip, melodyClip, writeClipsToMidi } from './packages/beat-agent/src/index.js';

const kick = drumClip({ pattern: 'x---x-x-', instrument: 'C1' });
const snare = drumClip({ pattern: '--x-x---', instrument: 'D1' });
const hat = drumClip({ pattern: 'x-x-x-x-', instrument: 'F#1' });
const mel = melodyClip({ root: 'C3', scaleName: 'minor', pattern: 'x-x-xxx-' });

writeClipsToMidi([kick, snare, hat, mel], '/tmp/sillyshit_drop.mid');
console.log('MIDI written to /tmp/sillyshit_drop.mid');
